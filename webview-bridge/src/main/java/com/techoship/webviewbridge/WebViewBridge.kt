package com.techoship.webviewbridge

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Makes the browser features a web app expects work inside an Android [WebView]:
 * `window.print()`, `blob:` / `data:` downloads, and `<a download>` links.
 *
 * The page talks to the app through an origin-restricted [WebViewCompat.addWebMessageListener]
 * channel, so only pages from the origins you allow can reach native code.
 *
 * ```kotlin
 * val bridge = WebViewBridge.Builder(webView)
 *     .allowOrigin("https://app.example.com")
 *     .install()
 * webView.loadUrl("https://app.example.com")
 * ```
 *
 * Install before the first `loadUrl`: the page script is injected at document start, so it
 * only reaches documents loaded after [Builder.install].
 */
// Every WebViewCompat call here runs only after Builder.install() has checked isSupported().
@SuppressLint("RequiresFeature")
class WebViewBridge private constructor(
    /** The WebView this bridge is attached to. */
    val webView: WebView,
    private val options: Options,
) {
    /** Callbacks for what the bridge does on the page's behalf. All are called on the main thread. */
    interface Listener {
        /**
         * The page called `window.print()`. Return `true` if you handled it yourself; return
         * `false` (the default) to open the system print dialog for the WebView's content.
         */
        fun onPrintRequested(title: String): Boolean = false

        /** A file was saved to the shared Downloads folder. */
        fun onDownloadComplete(download: SavedDownload) {}

        /** A download could not be saved. [fileName] is null when the name was never known. */
        fun onDownloadFailed(fileName: String?, error: BridgeException) {}
    }

    /** Handles one method called from the page. Called on the main thread. */
    fun interface MessageHandler {
        fun onCall(call: Call)
    }

    /**
     * One call from the page. Answer it exactly once with [resolve] or [reject]; later answers
     * are ignored. Answers may be sent from any thread.
     */
    class Call internal constructor(
        /** The method name the page called, for example `print`. */
        val method: String,
        /** The parameters the page passed. Never null; empty when none were passed. */
        val params: JSONObject,
        /** The origin of the frame that sent the call, as checked by WebView. */
        val sourceOrigin: Uri,
        /** Whether the call came from the top-level frame rather than an iframe. */
        val isMainFrame: Boolean,
        private val id: Long?,
        private val replyProxy: JavaScriptReplyProxy,
        private val bridge: WebViewBridge,
    ) {
        private var answered = false

        /** Resolves the page's promise with [result]: a JSONObject, JSONArray, String, Number, Boolean or null. */
        fun resolve(result: Any? = null) {
            answer(JSONObject().put("ok", true).put("result", result ?: JSONObject.NULL))
        }

        /** Rejects the page's promise with an `Error` whose `code` is [code]. */
        fun reject(code: String, message: String) {
            val error = JSONObject().put("code", code).put("message", message)
            answer(JSONObject().put("ok", false).put("error", error))
        }

        private fun answer(body: JSONObject) {
            synchronized(this) {
                if (answered || id == null) return
                answered = true
            }
            bridge.post(replyProxy, body.put("id", id).toString())
        }
    }

    /** Configures and installs a [WebViewBridge]. */
    class Builder(private val webView: WebView) {
        private val origins = linkedSetOf<String>()
        private var print = true
        private var downloads = true
        private var httpDownloads = true
        private var maxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES
        private var showToasts = true
        private var listener: Listener = object : Listener {}

        /**
         * Allows pages from [origin] to use the bridge, for example `https://app.example.com`.
         * Subdomain wildcards follow the rules of `WebViewCompat.addWebMessageListener`.
         * Call at least once. A bare wildcard is rejected: allow the origins you serve, not
         * every page the WebView might open.
         */
        fun allowOrigin(origin: String) = apply {
            require(origin.isNotBlank()) { "Origin must not be blank" }
            require(origin.trim() != "*") { "Allowing every origin would expose the bridge to any page; list your origins" }
            origins += origin.trim()
        }

        /** Routes `window.print()` to Android's print service. On by default. */
        fun print(enabled: Boolean) = apply { print = enabled }

        /** Saves `blob:` and `data:` downloads and `<a download>` links to Downloads. On by default. */
        fun downloads(enabled: Boolean) = apply { downloads = enabled }

        /**
         * Also sets the WebView's [android.webkit.DownloadListener] so ordinary `http(s)` file
         * downloads go through Android's DownloadManager with the page's cookies. On by default.
         * Turn it off if your app sets its own DownloadListener.
         */
        fun httpDownloads(enabled: Boolean) = apply { httpDownloads = enabled }

        /** The largest `blob:` or `data:` file the page may save. 100 MB by default. */
        fun maxDownloadBytes(bytes: Long) = apply {
            require(bytes > 0) { "maxDownloadBytes must be positive" }
            maxDownloadBytes = bytes
        }

        /** Shows a short toast when a download starts, is saved or fails. On by default. */
        fun showToasts(enabled: Boolean) = apply { showToasts = enabled }

        fun listener(listener: Listener) = apply { this.listener = listener }

        /**
         * Attaches the bridge. Must be called on the main thread, before the page loads.
         *
         * @throws IllegalStateException if this device's WebView lacks the features the bridge
         *   needs. Check [isSupported] first to fall back gracefully.
         */
        @MainThread
        fun install(): WebViewBridge {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Install the bridge on the main thread" }
            check(origins.isNotEmpty()) { "Call allowOrigin() at least once" }
            check(isSupported()) { "This WebView is too old for WebViewBridge; check WebViewBridge.isSupported()" }
            if (!webView.settings.javaScriptEnabled) {
                Log.w(TAG, "JavaScript is disabled on this WebView, so the bridge will not run")
            }
            val options = Options(origins.toSet(), print, downloads, httpDownloads, maxDownloadBytes, showToasts, listener)
            return WebViewBridge(webView, options).also { it.start() }
        }
    }

    internal class Options(
        val allowedOrigins: Set<String>,
        val print: Boolean,
        val downloads: Boolean,
        val httpDownloads: Boolean,
        val maxDownloadBytes: Long,
        val showToasts: Boolean,
        val listener: Listener,
    )

    private val main = Handler(Looper.getMainLooper())
    private val handlers = HashMap<String, MessageHandler>()
    private val scripts = ArrayList<ScriptHandler>()
    private val features = ArrayList<String>()
    private var downloadFeature: DownloadFeature? = null
    private var mainFrameProxy: JavaScriptReplyProxy? = null
    private var destroyed = false

    /** Whether binary messages are available, so downloads skip base64. */
    internal val binaryMessages: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

    internal var binaryReceiver: ((ByteArray) -> Unit)? = null

    internal val listener: Listener get() = options.listener
    internal val maxDownloadBytes: Long get() = options.maxDownloadBytes
    internal val httpDownloads: Boolean get() = options.httpDownloads

    /** The origins allowed to use the bridge. */
    val allowedOrigins: Set<String> get() = options.allowedOrigins

    private val messageListener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
        if (!destroyed) receive(message, sourceOrigin, isMainFrame, replyProxy)
    }

    private fun start() {
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT_NAME, options.allowedOrigins, messageListener)
        if (options.print) {
            PrintFeature(this).install()
            features += FEATURE_PRINT
        }
        if (options.downloads) {
            downloadFeature = DownloadFeature(this).also { it.install() }
            features += FEATURE_DOWNLOADS
        }
        addScript(coreScript())
    }

    /**
     * Registers the native side of a page method. For extension modules such as the billing
     * bridge. Call on the main thread, before the page loads.
     */
    @MainThread
    fun registerHandler(method: String, handler: MessageHandler) {
        check(!destroyed) { "This bridge has been destroyed" }
        require(method !in handlers) { "A handler for $method is already registered" }
        handlers[method] = handler
    }

    /**
     * Injects [script] at document start into every allowed frame, after the bridge's own
     * script. For extension modules. Call on the main thread, before the page loads.
     */
    @MainThread
    fun addScript(script: String) {
        check(!destroyed) { "This bridge has been destroyed" }
        scripts += WebViewCompat.addDocumentStartJavaScript(webView, script, options.allowedOrigins)
    }

    /**
     * Sends an event to the current top-level page, which receives it through
     * `TechoshipBridge.on(event, handler)`. Dropped if no allowed page is loaded. Any thread.
     */
    fun emit(event: String, data: Any? = null) {
        val body = JSONObject().put("event", event).put("data", data ?: JSONObject.NULL).toString()
        runOnMain {
            mainFrameProxy?.let { post(it, body) }
        }
    }

    /**
     * Detaches the bridge and cancels unfinished downloads. Call from your Activity's
     * `onDestroy`, before destroying the WebView.
     */
    @MainThread
    fun destroy() {
        if (destroyed) return
        destroyed = true
        scripts.forEach { runCatching { it.remove() } }
        scripts.clear()
        runCatching { WebViewCompat.removeWebMessageListener(webView, JS_OBJECT_NAME) }
        downloadFeature?.shutdown()
        if (options.httpDownloads) webView.setDownloadListener(null)
        handlers.clear()
        mainFrameProxy = null
        binaryReceiver = null
    }

    private fun receive(message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (binaryMessages && message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            binaryReceiver?.invoke(message.arrayBuffer)
            return
        }
        val text = message.data ?: return
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return
        }
        val method = json.optString("method")
        if (method == HELLO) {
            if (isMainFrame) mainFrameProxy = replyProxy
            return
        }
        val id = if (json.has("id") && !json.isNull("id")) json.optLong("id") else null
        val params = json.optJSONObject("params") ?: JSONObject()
        val call = Call(method, params, sourceOrigin, isMainFrame, id, replyProxy, this)
        val handler = handlers[method]
        if (handler == null) {
            call.reject("unknown_method", "The app has no handler for '$method'")
            return
        }
        try {
            handler.onCall(call)
        } catch (error: Exception) {
            Log.w(TAG, "Handler for $method failed", error)
            call.reject("internal_error", error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun post(proxy: JavaScriptReplyProxy, text: String) {
        runOnMain {
            if (destroyed) return@runOnMain
            try {
                proxy.postMessage(text)
            } catch (error: RuntimeException) {
                // The frame went away before the reply arrived.
                Log.d(TAG, "Reply dropped: ${error.message}")
            }
        }
    }

    internal fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    internal fun toast(text: String) {
        if (!options.showToasts) return
        runOnMain { Toast.makeText(webView.context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun coreScript(): String {
        val source = webView.context.resources.openRawResource(R.raw.webview_bridge)
            .bufferedReader().use { it.readText() }
        val config = JSONObject()
            .put("objectName", JS_OBJECT_NAME)
            .put("version", VERSION)
            .put("features", JSONArray(features))
            .put("binary", binaryMessages)
            .put("chunkSize", CHUNK_SIZE)
        return "$source($config);"
    }

    companion object {
        /** The library version, also exposed to the page as `TechoshipBridge.version`. */
        const val VERSION = "0.1.0"

        internal const val TAG = "WebViewBridge"
        internal const val JS_OBJECT_NAME = "__techoshipBridgePort"
        internal const val HELLO = "bridge.hello"
        internal const val FEATURE_PRINT = "print"
        internal const val FEATURE_DOWNLOADS = "downloads"
        internal const val CHUNK_SIZE = 512 * 1024
        internal const val DEFAULT_MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

        /**
         * Whether this device's WebView supports the bridge (WebView 87 or later, from 2020).
         * Call on the main thread.
         */
        @JvmStatic
        fun isSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}
