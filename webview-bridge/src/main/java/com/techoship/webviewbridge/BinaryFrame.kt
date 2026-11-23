package com.techoship.webviewbridge

/**
 * A download chunk sent as a binary message: a 4-byte big-endian transfer id, then the bytes.
 * The page builds it in `webview_bridge.js` with `DataView.setUint32(0, id)`.
 */
internal object BinaryFrame {
    const val HEADER_BYTES = 4

    fun transferId(frame: ByteArray): Int {
        require(frame.size >= HEADER_BYTES) { "Frame is shorter than its header" }
        return ((frame[0].toInt() and 0xff) shl 24) or
            ((frame[1].toInt() and 0xff) shl 16) or
            ((frame[2].toInt() and 0xff) shl 8) or
            (frame[3].toInt() and 0xff)
    }

    fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(HEADER_BYTES, frame.size)
}
