package com.techoship.webviewbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class BinaryFrameTest {
    private fun frame(id: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(id).put(payload).array()

    @Test fun readsTheTransferIdBigEndian() {
        assertEquals(1, BinaryFrame.transferId(frame(1, byteArrayOf())))
        assertEquals(0x01020304, BinaryFrame.transferId(frame(0x01020304, byteArrayOf(9))))
        // DataView.setUint32 writes ids above 2^31 too; they must not come back negative-garbled.
        assertEquals(-1, BinaryFrame.transferId(frame(-1, byteArrayOf())))
    }

    @Test fun returnsThePayloadAfterTheHeader() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        assertArrayEquals(payload, BinaryFrame.payload(frame(7, payload)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAFrameShorterThanItsHeader() {
        BinaryFrame.transferId(byteArrayOf(1, 2))
    }
}
