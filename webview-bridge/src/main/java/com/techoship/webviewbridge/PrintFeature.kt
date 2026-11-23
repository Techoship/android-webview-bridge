package com.techoship.webviewbridge

import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log

/**
 * `window.print()` does nothing in a WebView. The page script replaces it with a call here,
 * which prints the WebView's current content through Android's print service, where the
 * user can pick a printer or "Save as PDF".
 */
internal class PrintFeature(private val bridge: WebViewBridge) {
    fun install() {
        bridge.registerHandler("print", ::print)
    }

    private fun print(call: WebViewBridge.Call) {
        val title = call.params.optString("title").trim().take(MAX_TITLE).ifEmpty { DEFAULT_JOB_NAME }
        if (bridge.listener.onPrintRequested(title)) {
            call.resolve()
            return
        }
        val manager = bridge.webView.context.getSystemService(PrintManager::class.java)
        if (manager == null) {
            call.reject("print_unavailable", "This device has no print service")
            return
        }
        try {
            val adapter = bridge.webView.createPrintDocumentAdapter(title)
            manager.print(title, adapter, PrintAttributes.Builder().build())
            call.resolve()
        } catch (error: RuntimeException) {
            // PrintManager needs an Activity context; a WebView built from the application context cannot print.
            Log.w(WebViewBridge.TAG, "Print failed", error)
            call.reject("print_failed", error.message ?: "Printing failed")
        }
    }

    private companion object {
        const val MAX_TITLE = 100
        const val DEFAULT_JOB_NAME = "Document"
    }
}
