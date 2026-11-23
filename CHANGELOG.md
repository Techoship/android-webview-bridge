# Changelog

## 0.1.0 (unreleased)

First release.

- `webview-bridge`: `window.print()` through Android's print service; `blob:` and `data:` downloads
  saved to Downloads (MediaStore on Android 10+, the public folder on 8–9), including blob URLs revoked
  straight after the click; `<a download>` and `http(s)` downloads through DownloadManager with cookies;
  origin-restricted messaging via `WebViewCompat.addWebMessageListener`; binary chunking with a base64
  fallback; size limits and file-name sanitising.
- `webview-bridge-billing`: Google Play Billing 9 as `window.AndroidBilling`: products, one-time
  purchases, subscriptions with offers, pending purchases, acknowledge, consume, purchase updates.
- `js/webview-bridge.d.ts`: TypeScript types for the page-side API.
