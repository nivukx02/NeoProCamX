package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RgbaImageCopyPolicyTest {

    @Test
    fun copyToArgb_handlesPixelStrideAndRowPadding() {
        val buffer = ByteBuffer.allocateDirect(2 * 12)
        putRgba(buffer, 0, 255, 0, 0, 255)
        putRgba(buffer, 4, 0, 255, 0, 255)
        putRgba(buffer, 12, 0, 0, 255, 255)
        putRgba(buffer, 16, 255, 255, 255, 128)

        val pixels = RgbaImageCopyPolicy.copyToArgb(
            source = buffer,
            sourceWidth = 2,
            sourceHeight = 2,
            sourceRowStride = 12,
            sourcePixelStride = 4,
            targetWidth = 2,
            targetHeight = 2
        )

        assertArrayEquals(
            intArrayOf(
                argb(255, 255, 0, 0),
                argb(255, 0, 255, 0),
                argb(255, 0, 0, 255),
                argb(128, 255, 255, 255)
            ),
            pixels
        )
    }

    @Test
    fun copyToArgb_scalesWithCenterSampling() {
        val buffer = ByteBuffer.allocateDirect(2 * 2 * 4)
        putRgba(buffer, 0, 10, 20, 30, 255)
        putRgba(buffer, 4, 40, 50, 60, 255)
        putRgba(buffer, 8, 70, 80, 90, 255)
        putRgba(buffer, 12, 100, 110, 120, 255)

        val pixels = RgbaImageCopyPolicy.copyToArgb(
            source = buffer,
            sourceWidth = 2,
            sourceHeight = 2,
            sourceRowStride = 8,
            sourcePixelStride = 4,
            targetWidth = 1,
            targetHeight = 1
        )

        assertEquals(argb(255, 100, 110, 120), pixels.single())
    }

    private fun putRgba(buffer: ByteBuffer, offset: Int, red: Int, green: Int, blue: Int, alpha: Int) {
        buffer.put(offset, red.toByte())
        buffer.put(offset + 1, green.toByte())
        buffer.put(offset + 2, blue.toByte())
        buffer.put(offset + 3, alpha.toByte())
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
