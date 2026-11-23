package com.techoship.webviewbridge

import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Saves files the page hands over. `blob:` and `data:` files arrive from the page script in
 * chunks (binary messages where supported, base64 otherwise); `http(s)` files go to
 * DownloadManager with the page's cookies.
 *
 * Transfers are tracked on the main thread; all file IO runs in order on one IO thread, so a
 * `flush` or `end` queued after a chunk always sees that chunk written.
 */
internal class DownloadFeature(private val bridge: WebViewBridge) {
    private val context = bridge.webView.context.applicationContext
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "webview-bridge-io") }
    private val transfers = HashMap<Int, Transfer>()
    private var nextId = 1

    private class Transfer(val id: Int, val name: String, val mimeType: String, val expectedBytes: Long) {
        @Volatile var sink: DownloadSink? = null
        @Volatile var failure: BridgeException? = null
        var receivedBytes = 0L
        var reported = false
    }

    fun install() {
        bridge.registerHandler("download.begin", ::begin)
        bridge.registerHandler("download.chunk", ::chunk)
        bridge.registerHandler("download.flush", ::flush)
        bridge.registerHandler("download.end", ::end)
        bridge.registerHandler("download.abort", ::abort)
        bridge.registerHandler("download.url", ::downloadUrl)
        bridge.registerHandler("download.error", ::pageError)
        bridge.binaryReceiver = ::onBinaryChunk
        if (bridge.httpDownloads) {
            bridge.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                onDownloadStart(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    fun shutdown() {
        val open = transfers.values.toList()
        transfers.clear()
        open.forEach { transfer -> io.execute { transfer.sink?.abort() } }
        io.shutdown()
    }

    private fun begin(call: WebViewBridge.Call) {
        val size = call.params.optLong("size", -1L)
        val mimeType = call.params.optString("mime").ifBlank { DEFAULT_MIME }
        val name = FileNames.sanitize(call.params.optString("name"), mimeType, ::extensionFor)
        if (size > bridge.maxDownloadBytes) {
            val error = tooLarge()
            notifyFailed(name, error)
            call.reject(error.code, error.message.orEmpty())
            return
        }
        val transfer = Transfer(nextId++, name, mimeType, size)
        transfers[transfer.id] = transfer
        io.execute {
            try {
                transfer.sink = DownloadStore.open(context, name, mimeType)
            } catch (error: Exception) {
                transfer.failure = asBridgeException(error)
            }
            bridge.runOnMain {
                val failure = transfer.failure
                if (failure == null) {
                    call.resolve(JSONObject().put("id", transfer.id).put("name", name))
                } else {
                    transfers.remove(transfer.id)
                    notifyFailed(name, failure)
                    call.reject(failure.code, failure.message.orEmpty())
                }
            }
        }
    }

    /** Base64 chunk, for WebViews without binary messages. */
    private fun chunk(call: WebViewBridge.Call) {
        val transfer = transferFor(call) ?: return
        val bytes = try {
            Base64.getDecoder().decode(call.params.optString("data"))
        } catch (_: IllegalArgumentException) {
            fail(transfer, BridgeException(BridgeException.WRITE_FAILED, "The page sent a malformed chunk"))
            call.reject(BridgeException.WRITE_FAILED, "Malformed chunk")
            return
        }
        write(transfer, bytes) { failure ->
            if (failure == null) call.resolve() else call.reject(failure.code, failure.message.orEmpty())
        }
    }

    /** Binary chunk: framed as a 4-byte transfer id followed by the bytes. */
    private fun onBinaryChunk(frame: ByteArray) {
        val transfer = try {
            transfers[BinaryFrame.transferId(frame)]
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        write(transfer, BinaryFrame.payload(frame)) {}
    }

    /** Answers once every chunk sent before it has been written. */
    private fun flush(call: WebViewBridge.Call) {
        val transfer = transferFor(call) ?: return
        io.execute {
            bridge.runOnMain {
                val failure = transfer.failure
                if (failure == null) {
                    call.resolve(JSONObject().put("written", transfer.receivedBytes))
                } else {
                    call.reject(failure.code, failure.message.orEmpty())
                }
            }
        }
    }

    private fun end(call: WebViewBridge.Call) {
        val transfer = transferFor(call) ?: return
        transfers.remove(transfer.id)
        io.execute {
            val outcome = try {
                transfer.failure?.let { throw it }
                Result.success(checkNotNull(transfer.sink).finish())
            } catch (error: Exception) {
                transfer.sink?.abort()
                Result.failure(asBridgeException(error))
            }
            bridge.runOnMain {
                outcome.onSuccess { (savedName, uri) ->
                    val saved = SavedDownload(savedName, transfer.mimeType, uri, transfer.receivedBytes)
                    bridge.listener.onDownloadComplete(saved)
                    bridge.toast(context.getString(R.string.webview_bridge_saved, savedName))
                    call.resolve(
                        JSONObject().put("name", savedName).put("uri", uri.toString()).put("size", transfer.receivedBytes),
                    )
                }.onFailure { error ->
                    val failure = error as BridgeException
                    fail(transfer, failure)
                    call.reject(failure.code, failure.message.orEmpty())
                }
            }
        }
    }

    private fun abort(call: WebViewBridge.Call) {
        val transfer = transfers.remove(call.params.optInt("id", -1))
        if (transfer != null) io.execute { transfer.sink?.abort() }
        call.resolve()
    }

    /** `<a download href="https://…">`, routed here by the page script. */
    private fun downloadUrl(call: WebViewBridge.Call) {
        val url = call.params.optString("url")
        if (!URLUtil.isNetworkUrl(url)) {
            call.reject(BridgeException.UNSUPPORTED_URL, "Only http and https URLs can be downloaded this way")
            return
        }
        val failure = enqueue(url, null, null, null, call.params.optString("name"))
        if (failure == null) call.resolve(JSONObject().put("queued", true)) else call.reject(failure.code, failure.message.orEmpty())
    }

    /** The page could not read the file itself (for example, the blob was already revoked). */
    private fun pageError(call: WebViewBridge.Call) {
        val name = call.params.optString("name").ifBlank { null }
        notifyFailed(name, BridgeException(BridgeException.PAGE_ERROR, call.params.optString("message").ifBlank { "The page could not read the file" }))
        call.resolve()
    }

    private fun onDownloadStart(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            URLUtil.isNetworkUrl(url) -> enqueue(url, userAgent, contentDisposition, mimeType, null)
            url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true) -> {
                // A navigation the page script did not intercept: ask the page to read the file.
                val name = if (contentDisposition.isNullOrBlank()) "" else URLUtil.guessFileName(url, contentDisposition, mimeType)
                bridge.webView.evaluateJavascript(
                    "window.TechoshipBridge && window.TechoshipBridge.download(${JSONObject.quote(url)}, ${JSONObject.quote(name)})",
                    null,
                )
            }
            else -> notifyFailed(null, BridgeException(BridgeException.UNSUPPORTED_URL, "Cannot download $url"))
        }
    }

    /** Hands an http(s) URL to DownloadManager. Returns the failure, or null once queued. */
    private fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, suggestedName: String?): BridgeException? {
        val type = mimeType?.takeIf { it.isNotBlank() && it != DEFAULT_MIME }
            ?: MimeTypeMap.getFileExtensionFromUrl(url)?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        val guessed = suggestedName?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(url, contentDisposition, type)
        val name = FileNames.sanitize(guessed, type, ::extensionFor)
        if (DownloadStore.missingLegacyPermission(context)) {
            return BridgeException(BridgeException.PERMISSION_REQUIRED, "Saving to Downloads on Android 8–9 needs the WRITE_EXTERNAL_STORAGE permission")
                .also { notifyFailed(name, it) }
        }
        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent ?: bridge.webView.settings.userAgentString)
                if (type != null) setMimeType(type)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            bridge.toast(context.getString(R.string.webview_bridge_downloading, name))
            null
        } catch (error: RuntimeException) {
            Log.w(WebViewBridge.TAG, "DownloadManager refused $url", error)
            BridgeException(BridgeException.WRITE_FAILED, error.message ?: "Download could not start", error).also { notifyFailed(name, it) }
        }
    }

    /** Counts bytes on the main thread (enforcing the limit) and writes them on the IO thread. */
    private fun write(transfer: Transfer, bytes: ByteArray, done: (BridgeException?) -> Unit) {
        transfer.failure?.let { done(it); return }
        transfer.receivedBytes += bytes.size
        val overExpected = transfer.expectedBytes >= 0 && transfer.receivedBytes > transfer.expectedBytes
        if (transfer.receivedBytes > bridge.maxDownloadBytes || overExpected) {
            val error = tooLarge()
            fail(transfer, error)
            done(error)
            return
        }
        io.execute {
            val failure = if (transfer.failure != null) {
                transfer.failure
            } else {
                try {
                    checkNotNull(transfer.sink).write(bytes)
                    null
                } catch (error: Exception) {
                    asBridgeException(error).also { transfer.failure = it }
                }
            }
            bridge.runOnMain {
                if (failure != null) fail(transfer, failure)
                done(failure)
            }
        }
    }

    /** Records the first failure of a transfer and reports it to the app once. Main thread. */
    private fun fail(transfer: Transfer, error: BridgeException) {
        if (transfer.failure == null) transfer.failure = error
        if (!transfer.reported) {
            transfer.reported = true
            notifyFailed(transfer.name, transfer.failure ?: error)
        }
    }

    private fun transferFor(call: WebViewBridge.Call): Transfer? {
        val transfer = transfers[call.params.optInt("id", -1)]
        if (transfer == null) call.reject("unknown_transfer", "No download with that id is in progress")
        return transfer
    }

    private fun notifyFailed(name: String?, error: BridgeException) {
        Log.w(WebViewBridge.TAG, "Download failed (${error.code}): ${error.message}")
        bridge.listener.onDownloadFailed(name, error)
        bridge.toast(context.getString(R.string.webview_bridge_failed, name ?: context.getString(R.string.webview_bridge_file)))
    }

    private fun tooLarge() = BridgeException(
        BridgeException.TOO_LARGE,
        "The file is larger than the ${bridge.maxDownloadBytes / (1024 * 1024)} MB limit",
    )

    private fun asBridgeException(error: Exception): BridgeException =
        error as? BridgeException ?: BridgeException(BridgeException.WRITE_FAILED, error.message ?: "Could not save the file", error)

    private companion object {
        const val DEFAULT_MIME = "application/octet-stream"

        fun extensionFor(mimeType: String): String? = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    }
}
