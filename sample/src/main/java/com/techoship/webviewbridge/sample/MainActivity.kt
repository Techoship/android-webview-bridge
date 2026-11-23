package com.techoship.webviewbridge.sample

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.techoship.webviewbridge.BridgeException
import com.techoship.webviewbridge.SavedDownload
import com.techoship.webviewbridge.WebViewBridge
import com.techoship.webviewbridge.billing.BillingBridge
import java.util.concurrent.LinkedBlockingQueue

/**
 * Loads `assets/index.html` from a real https origin (via WebViewAssetLoader) and installs the
 * bridge for that origin. The demo page exercises print, downloads and billing.
 */
class MainActivity : Activity() {
    lateinit var webView: WebView
        private set
    private var bridge: WebViewBridge? = null
    private var billing: BillingBridge? = null

    /** What the bridge reported, so the instrumented tests can wait on it. */
    val savedDownloads = LinkedBlockingQueue<SavedDownload>()
    val failedDownloads = LinkedBlockingQueue<String>()
    val printRequests = LinkedBlockingQueue<String>()

    /** When true, print requests are recorded instead of opening the print dialog. */
    @Volatile var interceptPrint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this)
        setContentView(FrameLayout(this).apply {
            addView(webView)
            // Keep the page clear of the status and navigation bars (edge-to-edge on Android 15+).
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars()).let { intArrayOf(it.left, it.top, it.right, it.bottom) }
                } else {
                    @Suppress("DEPRECATION")
                    intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                view.setPadding(bars[0], bars[1], bars[2], bars[3])
                insets
            }
        })
        useDarkBarIconsOnLightTheme()
        webView.settings.javaScriptEnabled = true

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assets.shouldInterceptRequest(request.url)
        }

        if (WebViewBridge.isSupported()) {
            val bridge = WebViewBridge.Builder(webView)
                .allowOrigin(ORIGIN)
                .listener(object : WebViewBridge.Listener {
                    override fun onPrintRequested(title: String): Boolean {
                        printRequests += title
                        return interceptPrint
                    }

                    override fun onDownloadComplete(download: SavedDownload) {
                        savedDownloads += download
                    }

                    override fun onDownloadFailed(fileName: String?, error: BridgeException) {
                        failedDownloads += "${error.code}: ${fileName ?: "?"}"
                    }
                })
                .install()
            this.bridge = bridge
            billing = BillingBridge.install(bridge, this)
        }

        webView.loadUrl("$ORIGIN/assets/index.html")
    }

    private fun useDarkBarIconsOnLightTheme() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
    }

    override fun onDestroy() {
        billing?.destroy()
        bridge?.destroy()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
    }
}
