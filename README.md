# Android WebView Bridge

Make a web app work properly inside an Android `WebView`: **`window.print()`**, **file downloads**
(including `blob:` and `data:` links), and **Google Play Billing**, with no changes to the web app
for printing and downloads.

| In a plain WebView | With the bridge |
|---|---|
| `window.print()` does nothing | Opens Android's print dialog (printers and "Save as PDF") |
| `blob:` and `data:` downloads fail silently | Saved to the shared **Downloads** folder under their real file names |
| `<a download href="https://…">` opens the file or does nothing | Downloaded by DownloadManager with the page's cookies |
| No way to sell through Google Play | `window.AndroidBilling` for products, subscriptions and purchases |

It covers the common "create a link, click it, revoke the URL" pattern that many web apps use for
exports: the file is still saved even when the page revokes the blob URL straight away.

## Install

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the modules you need:

```kotlin
dependencies {
    implementation("com.github.Techoship.android-webview-bridge:webview-bridge:0.1.0")
    // Only if you sell through Google Play:
    implementation("com.github.Techoship.android-webview-bridge:webview-bridge-billing:0.1.0")
}
```

## Use

Install the bridge before the first `loadUrl`, and allow only the origins you serve:

```kotlin
webView.settings.javaScriptEnabled = true

if (WebViewBridge.isSupported()) {
    val bridge = WebViewBridge.Builder(webView)
        .allowOrigin("https://app.example.com")
        .listener(object : WebViewBridge.Listener {
            override fun onDownloadComplete(download: SavedDownload) { /* optional */ }
            override fun onDownloadFailed(fileName: String?, error: BridgeException) { /* optional */ }
        })
        .install()

    // Optional: Google Play Billing for the same pages.
    val billing = BillingBridge.install(bridge, activity)
}

webView.loadUrl("https://app.example.com")
```

Call `billing.destroy()` and `bridge.destroy()` in `onDestroy`, before destroying the WebView.

Builder options:

| Option | Default | What it does |
|---|---|---|
| `allowOrigin(origin)` | — (required) | Pages allowed to use the bridge. A bare `*` is rejected. |
| `print(Boolean)` | `true` | Route `window.print()` to the print service. |
| `downloads(Boolean)` | `true` | Save `blob:`/`data:` downloads and `<a download>` links. |
| `httpDownloads(Boolean)` | `true` | Set the WebView's `DownloadListener` for ordinary `http(s)` files. Turn off if you set your own. |
| `maxDownloadBytes(Long)` | 100 MB | Largest `blob:`/`data:` file a page may save. |
| `showToasts(Boolean)` | `true` | Short toasts for "Downloading…", "Saved to Downloads…" and failures. |

## The page side

**Printing and downloads need no changes to your web app.** `window.print()`, `<a download>` links
and the blob-link pattern just work. For explicit control, `window.TechoshipBridge` is there when
the page runs inside the app:

```js
if (window.TechoshipBridge?.has('downloads')) {
  const saved = await TechoshipBridge.download(blob, 'report.pdf');   // { name, uri, size }
}
```

**Billing** is exposed as `window.AndroidBilling`:

```js
const { products } = await AndroidBilling.getProducts({ subs: ['pro'], inapp: ['credits_100'] });

const result = await AndroidBilling.purchase('pro');   // status: purchased | pending | cancelled | already_owned
if (result.status === 'purchased') {
  // 1. Send result.purchases[0].purchaseToken to your server.
  // 2. The server verifies it with the Google Play Developer API and grants access.
  // 3. Acknowledge it (from the server, or here), or consume it for consumables:
  await AndroidBilling.acknowledge(result.purchases[0].purchaseToken);
}

AndroidBilling.onPurchasesUpdated(({ purchases }) => { /* e.g. a pending purchase completed */ });
const { purchases } = await AndroidBilling.getPurchases();   // restore on launch
```

Play refunds purchases that aren't acknowledged within three days. For apps without a backend,
`BillingBridge.install(bridge, activity, BillingBridge.Options(autoAcknowledge = true))`
acknowledges on the device.

TypeScript types for both globals are in [`js/webview-bridge.d.ts`](js/webview-bridge.d.ts).
Errors are ordinary `Error`s with a `code`, such as `too_large`, `permission_required`,
`user_canceled` or `billing_unavailable`.

## Security

- **Only allowed origins get the bridge.** It uses `WebViewCompat.addWebMessageListener` with your
  origin list, so pages from other origins, including ones the user navigates to, never see it.
  There is no `addJavascriptInterface`.
- **Billing is top-level only.** Iframes can't start purchases, even on an allowed origin.
- **Downloads are bounded.** File names are sanitised (no paths or reserved characters), sizes are
  capped, and a page can't write more bytes than it declared.
- **Verify purchases on your server.** The bridge passes `purchaseToken`, `originalJson` and
  `signature` to the page; treat anything the page reports as untrusted until your server checks it.

## Requirements and limits

- `minSdk` 26 (Android 8.0). WebView 87 or later; check `WebViewBridge.isSupported()`.
- **Android 8–9:** saving to Downloads needs the `WRITE_EXTERNAL_STORAGE` runtime permission
  (declared by the library with `maxSdkVersion="28"`); request it before downloads. Android 10 and
  later write through MediaStore and need no permission.
- **`http(s)` downloads** carry the page's cookies but not headers set by JavaScript (for example
  `Authorization: Bearer …`). For authenticated API files, fetch them in the page and save the
  resulting blob instead.
- Large files are streamed in 512 KB chunks, as binary messages where the WebView supports them
  and base64 otherwise.

## Status

`0.1.0`, first release.

- **Tested on a device:** printing, downloads and origin isolation are covered by instrumented tests
  in [`sample/`](sample/), run on Android 16 with WebView 157. The tests cover a revoked blob link,
  a 3 MB multi-chunk file checked byte for byte, a `data:` link, the base64 fallback, the size
  limit and an unregistered method.
- **Billing:** the full purchase flow needs an app published to a Play Console testing track with
  licence testers, so it isn't covered by the automated tests yet. Issues and reports are welcome.

## Develop

```bash
./gradlew testDebugUnitTest                 # unit tests
./gradlew :sample:connectedDebugAndroidTest # instrumented tests on a connected device or emulator
```

The sample app (`sample/`) loads a demo page from `https://appassets.androidplatform.net` through
`WebViewAssetLoader` and shows every feature.

## License

[MIT](LICENSE) © Techoship LLC. Built by [Techoship](https://techoship.com/?utm_source=github&utm_medium=repo&utm_campaign=webview-bridge).
