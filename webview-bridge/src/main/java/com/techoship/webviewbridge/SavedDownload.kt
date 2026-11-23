package com.techoship.webviewbridge

import android.net.Uri

/** A file the bridge saved to the shared Downloads folder. */
class SavedDownload(
    /** The name it was saved under. Android may add " (1)" if the name was taken. */
    val fileName: String,
    val mimeType: String,
    /** A `content://` URI on Android 10+, a `file://` URI on Android 8–9. */
    val uri: Uri,
    val sizeBytes: Long,
) {
    override fun toString() = "SavedDownload(fileName=$fileName, mimeType=$mimeType, sizeBytes=$sizeBytes, uri=$uri)"
}

/** Why a bridge operation failed. [code] is also what the page sees as `error.code`. */
class BridgeException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        const val TOO_LARGE = "too_large"
        const val PERMISSION_REQUIRED = "permission_required"
        const val WRITE_FAILED = "write_failed"
        const val UNSUPPORTED_URL = "unsupported_url"
        const val PAGE_ERROR = "page_error"
    }
}
