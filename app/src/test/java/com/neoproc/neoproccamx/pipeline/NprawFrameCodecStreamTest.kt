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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    @Test
    fun playbackSkipperDiscardsPayloadWithoutReadingItIntoAFrameArray() {
        val w = 64
        val h = 32
        val first = IntArray(w * h) { (it * 17) % 1_001 }
        val second = IntArray(w * h) { 1_000 - (it % 1_001) }
        val stream = ByteArrayOutputStream().apply {
            write(encode(first, w, h, 11L))
            write(encode(second, w, h, 22L))
        }.toByteArray()
        val input = LargestReadTrackingInputStream(stream)
        val skipper = NprawFrameCodec.StreamFrameSkipper()

        assertTrue(skipper.skipNext(input))
        assertTrue(
            "Skipped payload must use InputStream.skip, not a payload-sized read",
            input.largestReadRequest <= 42
        )

        val decoded = NprawFrameCodec.readFrame(input)
        requireNotNull(decoded)
        assertEquals(22L, decoded.timestamp)
        assertTrue(second.contentEquals(decoded.samples))
    }

    @Test
    fun fixedRealtimeStreamCountIsExactWithoutWalkingPayloads() {
        val width = 8
        val height = 4
        val plane = ByteBuffer.allocate(width * height * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height) { plane.putShort((it * 3).toShort()) }
        val packet = NprawFrameCodec.encodeRealtimeLinear16Frame(
            rawPlane = plane,
            width = width,
            height = height,
            rowStride = width * Short.SIZE_BYTES,
            pixelStride = Short.SIZE_BYTES,
            timestamp = 1L,
            originalSize = plane.capacity()
        ).bytes
        val stream = packet + packet + packet

        assertEquals(
            3,
            NprawFrameCodec.estimateFixedFrameCount(
                ByteArrayInputStream(stream),
                stream.size.toLong()
            )
        )
        assertNull(
            NprawFrameCodec.estimateFixedFrameCount(
                ByteArrayInputStream(stream),
                stream.size.toLong() - 1L
            )
        )
        val variablePacket = encode(IntArray(width * height) { it }, width, height, 2L)
        assertNull(
            NprawFrameCodec.estimateFixedFrameCount(
                ByteArrayInputStream(variablePacket),
                variablePacket.size.toLong()
            )
        )
    }

    private class LargestReadTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var largestReadRequest: Int = 0
            private set

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            largestReadRequest = maxOf(largestReadRequest, length)
            return super.read(target, offset, length)
        }
    }
}
