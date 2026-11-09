package com.neoproc.neoproccamx.pipeline

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoproc.neoproccamx.recording.RawCompressionMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NprawRealtimeNativeInstrumentedTest {
    @Test
    fun nativeDirectPacketIsByteIdenticalToKotlinContainer() {
        val width = 67
        val height = 9
        val rowStride = 144
        val pixelStride = 2
        val timestamp = 9_876_543_210L
        val source = ByteBuffer.allocateDirect(rowStride * height)
        for (index in 0 until source.capacity()) {
            source.put(index, ((index * 37 + index / 11) and 0xFF).toByte())
        }

        val expected = NprawFrameCodec.encodeRealtimeLinear16Frame(
            rawPlane = source,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            timestamp = timestamp,
            originalSize = source.capacity()
        )
        val packet = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeLinear16PacketSize(width, height)
        )
        val actual = NprawFrameCodec.encodeRealtimeLinear16FrameInto(
            rawPlane = source,
            destination = packet,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            timestamp = timestamp,
            originalSize = source.capacity()
        )

        assertNotNull("Native realtime NPRAW bridge must be available in the APK", actual)
        val actualBytes = ByteArray(actual!!.packetSize)
        packet.duplicate().apply {
            position(0)
            limit(actualBytes.size)
            get(actualBytes)
        }
        assertArrayEquals(expected.bytes, actualBytes)
    }

    @Test
    fun borrowedContiguousPacketIsByteIdenticalWithoutPayloadCopy() {
        val width = 64
        val height = 11
        val rowStride = width * 2
        val timestamp = 11_223_344_556L
        val source = ByteBuffer.allocateDirect(rowStride * height)
        for (index in 0 until source.capacity()) {
            source.put(index, ((index * 53 + index / 7) and 0xFF).toByte())
        }

        val expected = NprawFrameCodec.encodeRealtimeLinear16Frame(
            rawPlane = source,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 2,
            timestamp = timestamp,
            originalSize = source.capacity()
        )
        val borrowed = NprawFrameCodec.encodeRealtimeLinear16BorrowedFrame(
            rawPlane = source,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 2,
            timestamp = timestamp,
            originalSize = source.capacity()
        )

        assertNotNull("Contiguous RAW16 must use the borrowed packet path", borrowed)
        val actual = ByteArray(borrowed!!.packetSize)
        System.arraycopy(borrowed.header, 0, actual, 0, borrowed.header.size)
        source.duplicate().apply {
            position(0)
            limit(borrowed.payloadSize)
            get(actual, borrowed.header.size, borrowed.payloadSize)
        }
        assertArrayEquals(expected.bytes, actual)
        assertEquals(16, borrowed.bitDepth)
    }

    @Test
    fun borrowedPacketRejectsPaddedPlaneSoExactPackerCanTakeOver() {
        val width = 64
        val height = 4
        val paddedStride = width * 2 + 16
        val source = ByteBuffer.allocateDirect(paddedStride * height)

        assertNull(
            NprawFrameCodec.encodeRealtimeLinear16BorrowedFrame(
                rawPlane = source,
                width = width,
                height = height,
                rowStride = paddedStride,
                pixelStride = 2,
                timestamp = 1L,
                originalSize = source.capacity()
            )
        )
    }

    @Test
    fun nativePacked12PacketRoundTripsEverySensorCodeBitExact() {
        val width = 67
        val height = 9
        val rowStride = width * 2 + 14
        val timestamp = 77_889_900L
        val source = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val expected = IntArray(width * height) { index ->
            ((index * 313) xor (index ushr 2)) and 0x03FF
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                source.putShort(y * rowStride + x * 2, expected[y * width + x].toShort())
            }
        }
        val packet = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeLosslessPacketSize(width, height, sensorWhiteLevel = 1023)
        )
        val encoded = NprawFrameCodec.encodeRealtimePacked12FrameInto(
            rawPlane = source,
            destination = packet,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 2,
            timestamp = timestamp,
            originalSize = source.capacity(),
            sensorWhiteLevel = 1023
        )

        assertNotNull("Native packed-12 NPRAW bridge must be available in the APK", encoded)
        assertEquals(12, encoded!!.bitDepth)
        assertEquals((expected.size * 3 + 1) / 2, encoded.payloadSize)
        val bytes = ByteArray(encoded.packetSize)
        packet.duplicate().apply {
            position(0)
            limit(bytes.size)
            get(bytes)
        }
        NprawFrameCodec.validateEncodedFrameContainer(bytes)
        val decoded = NprawFrameCodec.decodeFrame(bytes)
        assertEquals(RawCompressionMode.LOSSLESS_REALTIME, decoded.mode)
        assertEquals(timestamp, decoded.timestamp)
        assertArrayEquals(expected, decoded.samples)
    }

    @Test
    fun nativePacked12PacketRejectsAnyOutOfRangeCodeInsteadOfClipping() {
        val source = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 1023.toShort())
            putShort(2, 4096.toShort())
        }
        val packet = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeLosslessPacketSize(2, 1, sensorWhiteLevel = 1023)
        )

        assertNull(
            NprawFrameCodec.encodeRealtimePacked12FrameInto(
                rawPlane = source,
                destination = packet,
                width = 2,
                height = 1,
                rowStride = 4,
                pixelStride = 2,
                timestamp = 1L,
                originalSize = source.capacity(),
                sensorWhiteLevel = 1023
            )
        )
    }

    @Test
    fun nativeBlockRangePacketRoundTripsEverySensorCodeBitExact() {
        val width = 131
        val height = 11
        val rowStride = width * 2 + 18
        val timestamp = 88_990_011L
        val source = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val expected = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (48 + x / 3 + y * 2 + ((index * 17) and 15)).coerceAtMost(1023)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                source.putShort(y * rowStride + x * 2, expected[y * width + x].toShort())
            }
        }
        val packet = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeBlockRangePacketCapacity(width, height, sensorWhiteLevel = 1023)
        )
        val encoded = NprawFrameCodec.encodeRealtimeBlockRangeFrameInto(
            rawPlane = source,
            destination = packet,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 2,
            timestamp = timestamp,
            originalSize = source.capacity(),
            sensorWhiteLevel = 1023
        )

        assertNotNull("Native block-range NPRAW bridge must be available in the APK", encoded)
        assertEquals(NprawFrameCodec.VERSION_BLOCK_RANGE_LOSSLESS, encoded!!.version)
        assertEquals(10, encoded.bitDepth)
        assertTrue(
            "Structured sensor data should require less storage than packed-12",
            encoded.payloadSize < (expected.size * 3 + 1) / 2
        )
        val bytes = ByteArray(encoded.packetSize)
        packet.duplicate().apply {
            position(0)
            limit(bytes.size)
            get(bytes)
        }
        NprawFrameCodec.validateEncodedFrameContainer(bytes)
        val decoded = NprawFrameCodec.decodeFrame(bytes)
        assertEquals(RawCompressionMode.LOSSLESS_REALTIME, decoded.mode)
        assertEquals(timestamp, decoded.timestamp)
        assertArrayEquals(expected, decoded.samples)
    }

    @Test
    fun nativeBlockRangePacketRejectsOutOfRangeCodeWithoutClipping() {
        val source = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 1023.toShort())
            putShort(2, 1024.toShort())
        }
        val packet = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeBlockRangePacketCapacity(2, 1, sensorWhiteLevel = 1023)
        )

        assertNull(
            NprawFrameCodec.encodeRealtimeBlockRangeFrameInto(
                rawPlane = source,
                destination = packet,
                width = 2,
                height = 1,
                rowStride = 4,
                pixelStride = 2,
                timestamp = 1L,
                originalSize = source.capacity(),
                sensorWhiteLevel = 1023
            )
        )
    }

    @Test
    fun fullActiveArrayBlockRangeMeetsRealtimeFrameBudget() {
        val width = 4096
        val height = 3072
        val rowStride = width * 2
        val sampleCount = width * height
        val source = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val words = source.asShortBuffer()
        for (index in 0 until sampleCount) {
            val x = index % width
            val y = index / width
            words.put(index, (32 + ((x * 3 + y * 5 + (index and 31)) and 255)).toShort())
        }
        val destination = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeBlockRangePacketCapacity(width, height, sensorWhiteLevel = 1023)
        )

        repeat(FULL_FRAME_WARMUP_RUNS) { iteration ->
            destination.clear()
            assertNotNull(
                NprawFrameCodec.encodeRealtimeBlockRangeFrameInto(
                    rawPlane = source,
                    destination = destination,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    pixelStride = 2,
                    timestamp = iteration.toLong(),
                    originalSize = source.capacity(),
                    sensorWhiteLevel = 1023
                )
            )
        }

        var payloadBytes = 0
        val durationsNs = LongArray(FULL_FRAME_MEASURED_RUNS) { iteration ->
            destination.clear()
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val encoded = NprawFrameCodec.encodeRealtimeBlockRangeFrameInto(
                rawPlane = source,
                destination = destination,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = 2,
                timestamp = (FULL_FRAME_WARMUP_RUNS + iteration).toLong(),
                originalSize = source.capacity(),
                sensorWhiteLevel = 1023
            )
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs
            assertNotNull(encoded)
            payloadBytes = encoded!!.payloadSize
            elapsedNs
        }.sortedArray()
        val medianNs = durationsNs[durationsNs.size / 2]
        val p95Ns = durationsNs.last()
        Log.i(
            BENCHMARK_TAG,
            "NPRAW_BLOCK_RANGE_FULL_FRAME width=$width height=$height payloadBytes=$payloadBytes " +
                "medianMs=${medianNs / 1_000_000.0} p95Ms=${p95Ns / 1_000_000.0} " +
                "samples=${durationsNs.joinToString() { (it / 1_000_000.0).toString() }}"
        )
        assertTrue(payloadBytes < (sampleCount * 3 + 1) / 2)
        assertTrue(
            "Block-range p95 ${p95Ns / 1_000_000.0}ms exceeds the 40ms/25fps frame budget",
            p95Ns <= FRAME_BUDGET_25_FPS_NS
        )
    }

    @Test
    fun fullActiveArrayPacked12MeetsRealtimeFrameBudget() {
        val width = 4096
        val height = 3072
        val rowStride = width * 2
        val sampleCount = width * height
        val source = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val words = source.asShortBuffer()
        for (index in 0 until sampleCount) {
            words.put(index, (((index * 313) xor (index ushr 5)) and 0x03FF).toShort())
        }
        val destination = ByteBuffer.allocateDirect(
            NprawFrameCodec.realtimeLosslessPacketSize(width, height, sensorWhiteLevel = 1023)
        )

        repeat(FULL_FRAME_WARMUP_RUNS) { iteration ->
            destination.clear()
            assertNotNull(
                NprawFrameCodec.encodeRealtimePacked12FrameInto(
                    rawPlane = source,
                    destination = destination,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    pixelStride = 2,
                    timestamp = iteration.toLong(),
                    originalSize = source.capacity(),
                    sensorWhiteLevel = 1023
                )
            )
        }

        val durationsNs = LongArray(FULL_FRAME_MEASURED_RUNS) { iteration ->
            destination.clear()
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val encoded = NprawFrameCodec.encodeRealtimePacked12FrameInto(
                rawPlane = source,
                destination = destination,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = 2,
                timestamp = (FULL_FRAME_WARMUP_RUNS + iteration).toLong(),
                originalSize = source.capacity(),
                sensorWhiteLevel = 1023
            )
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs
            assertNotNull(encoded)
            assertEquals((sampleCount * 3 + 1) / 2, encoded!!.payloadSize)
            elapsedNs
        }.sortedArray()
        val medianNs = durationsNs[durationsNs.size / 2]
        val p95Ns = durationsNs.last()
        Log.i(
            BENCHMARK_TAG,
            "NPRAW_PACKED12_FULL_FRAME width=$width height=$height " +
                "medianMs=${medianNs / 1_000_000.0} p95Ms=${p95Ns / 1_000_000.0} " +
                "samples=${durationsNs.joinToString() { (it / 1_000_000.0).toString() }}"
        )
        assertTrue(
            "Packed-12 p95 ${p95Ns / 1_000_000.0}ms exceeds the 40ms/25fps frame budget",
            p95Ns <= FRAME_BUDGET_25_FPS_NS
        )
    }

    private companion object {
        const val BENCHMARK_TAG = "NprawRealtimeBench"
        const val FULL_FRAME_WARMUP_RUNS = 2
        const val FULL_FRAME_MEASURED_RUNS = 7
        const val FRAME_BUDGET_25_FPS_NS = 40_000_000L
    }
}
