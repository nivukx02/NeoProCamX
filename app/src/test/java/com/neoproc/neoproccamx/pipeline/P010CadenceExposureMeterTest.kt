package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class P010CadenceExposureMeterTest {
    @Test
    fun `meter samples the complete P010 frame without changing buffer position`() {
        val width = 32
        val height = 18
        val rowStride = width * 2 + 8
        val buffer = ByteBuffer.allocate(rowStride * height + 3)
        buffer.position(3)
        writeConstantP010(buffer, width, height, rowStride, code10 = 512)
        val initialPosition = buffer.position()

        val metrics = P010CadenceExposureMeter.measure(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 2
        )

        requireNotNull(metrics)
        assertEquals(576, metrics.sampleCount)
        assertEquals(128f / 255f, metrics.averageLuma, 0.0001f)
        assertEquals(0f, metrics.highlightClipFraction, 0f)
        assertEquals(initialPosition, buffer.position())
    }

    @Test
    fun `highlight fraction is measured from left-aligned ten-bit codes`() {
        val width = 32
        val height = 18
        val rowStride = width * 2
        val buffer = ByteBuffer.allocate(rowStride * height)
        writeConstantP010(buffer, width, height, rowStride, code10 = 900)

        val metrics = requireNotNull(
            P010CadenceExposureMeter.measure(buffer, width, height, rowStride, 2)
        )

        assertEquals(1f, metrics.highlightClipFraction, 0f)
        assertTrue(metrics.averageLuma > 0.87f)
    }

    @Test
    fun `cadence sampling is isolated to every sixth 1K frame`() {
        assertTrue(P010CadenceExposureMeter.shouldMeasure(1280, 720, 6L))
        assertTrue(P010CadenceExposureMeter.shouldMeasure(720, 1280, 12L))
        assertFalse(P010CadenceExposureMeter.shouldMeasure(1280, 720, 5L))
        assertFalse(P010CadenceExposureMeter.shouldMeasure(1920, 1080, 6L))
    }

    @Test
    fun `invalid plane metadata fails closed`() {
        assertNull(P010CadenceExposureMeter.measure(ByteBuffer.allocate(8), 2, 2, 0, 2))
        assertNull(P010CadenceExposureMeter.measure(ByteBuffer.allocate(8), 2, 2, 4, 1))
    }

    private fun writeConstantP010(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        code10: Int
    ) {
        val packed = code10.coerceIn(0, 1023) shl 6
        val baseOffset = buffer.position()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = baseOffset + y * rowStride + x * 2
                buffer.put(index, (packed and 0xFF).toByte())
                buffer.put(index + 1, ((packed ushr 8) and 0xFF).toByte())
            }
        }
    }
}
