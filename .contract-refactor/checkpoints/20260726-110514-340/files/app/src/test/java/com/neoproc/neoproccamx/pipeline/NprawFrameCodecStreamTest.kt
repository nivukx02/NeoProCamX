package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RawCompressionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Contract for the streaming NPRAW frame reader that the offline converter uses to walk `stream.npraw`
 * (a concatenation of NprawFrameCodec frames) without loading the whole clip into memory. It must
 * return frames in order, round-trip the sensor samples, stop cleanly at end-of-stream (null), and
 * reject a truncated frame instead of yielding garbage.
 */
class NprawFrameCodecStreamTest {

    private fun encode(samples: IntArray, w: Int, h: Int, ts: Long): ByteArray =
        NprawFrameCodec.encodeFrame(
            mode = RawCompressionMode.HIGH_COMPRESSION,
            rawSamples = samples,
            width = w,
            height = h,
            rowStride = w * 2,
            pixelStride = 2,
            timestamp = ts,
            originalSize = w * h * 2,
            sensorWhiteLevel = 1000
        ).bytes

    @Test
    fun readsConcatenatedFramesInOrderThenNullAtEof() {
        val w = 6; val h = 2
        val f0 = IntArray(w * h) { it * 10 }
        val f1 = IntArray(w * h) { 500 }
        val f2 = IntArray(w * h) { 999 - it }
        val stream = ByteArrayOutputStream().apply {
            write(encode(f0, w, h, 1L))
            write(encode(f1, w, h, 2L))
            write(encode(f2, w, h, 3L))
        }.toByteArray()

        ByteArrayInputStream(stream).use { input ->
            val d0 = NprawFrameCodec.readFrame(input)
            assertNotNull(d0); requireNotNull(d0)
            assertEquals(w, d0.width); assertEquals(h, d0.height); assertEquals(1L, d0.timestamp)
            assertTrue(f0.contentEquals(d0.samples))

            val d1 = NprawFrameCodec.readFrame(input); requireNotNull(d1)
            assertTrue(f1.contentEquals(d1.samples))

            val d2 = NprawFrameCodec.readFrame(input); requireNotNull(d2)
            assertEquals(3L, d2.timestamp)
            assertTrue(f2.contentEquals(d2.samples))

            assertNull(NprawFrameCodec.readFrame(input)) // clean EOF between frames
        }
    }

    @Test
    fun emptyStreamReturnsNull() {
        ByteArrayInputStream(ByteArray(0)).use { assertNull(NprawFrameCodec.readFrame(it)) }
    }

    @Test
    fun truncatedFrameThrows() {
        val w = 6; val h = 2
        val full = encode(IntArray(w * h) { 300 }, w, h, 1L)
        val truncated = full.copyOf(full.size - 5)
        ByteArrayInputStream(truncated).use { input ->
            assertThrows(NprawFrameCodec.NprawFormatException::class.java) {
                NprawFrameCodec.readFrame(input)
            }
        }
    }
}
