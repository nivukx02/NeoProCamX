package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class Npv10FramePackerTest {

    @Test
    fun packerUpsamplesYuv420ToTenBitPlanarLittleEndian() {
        val frame = frame(
            y = byteArrayOf(0, 64, 128.toByte(), 255.toByte()),
            u = byteArrayOf(16),
            v = byteArrayOf(240.toByte())
        )

        val packed = Npv10FramePacker.pack(frame)

        assertEquals(12, packed.size)
        assertSampleEquals(0, packed, 0)
        assertSampleEquals(256, packed, 2)
        assertSampleEquals(512, packed, 4)
        assertSampleEquals(1020, packed, 6)
        assertSampleEquals(64, packed, 8)
        assertSampleEquals(960, packed, 10)
    }

    @Test
    fun packerFillsMissingChromaWithNeutralTenBitSamples() {
        val frame = frame(
            y = byteArrayOf(32, 48, 64, 80),
            u = ByteArray(0),
            v = ByteArray(0),
            uRowStride = 0,
            vRowStride = 0,
            uPixelStride = 0,
            vPixelStride = 0
        )

        val packed = Npv10FramePacker.pack(frame)

        assertSampleEquals(128, packed, 0)
        assertSampleEquals(192, packed, 2)
        assertSampleEquals(256, packed, 4)
        assertSampleEquals(320, packed, 6)
        assertSampleEquals(512, packed, 8)
        assertSampleEquals(512, packed, 10)
    }

    @Test
    fun packerPreservesNativeTenBitSamplesFromSixteenBitSourcePlanes() {
        val frame = frame(
            y = tenBitPlane(0, 256, 512, 1023),
            u = tenBitPlane(700),
            v = tenBitPlane(900),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            bitDepth = 10
        )

        val packed = Npv10FramePacker.pack(frame)

        assertSampleEquals(0, packed, 0)
        assertSampleEquals(256, packed, 2)
        assertSampleEquals(512, packed, 4)
        assertSampleEquals(1023, packed, 6)
        assertSampleEquals(700, packed, 8)
        assertSampleEquals(900, packed, 10)
    }

    @Test
    fun packerCanWriteP010LittleEndianLayoutForMovSampleEntry() {
        val frame = frame(
            y = tenBitPlane(0, 256, 512, 1023),
            u = tenBitPlane(700),
            v = tenBitPlane(900),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            bitDepth = 10
        )

        val packed = Npv10FramePacker.pack(frame, Npv10PayloadFormat.P010_LE)

        assertP010SampleEquals(0, packed, 0)
        assertP010SampleEquals(256, packed, 2)
        assertP010SampleEquals(512, packed, 4)
        assertP010SampleEquals(1023, packed, 6)
        assertP010SampleEquals(700, packed, 8)
        assertP010SampleEquals(900, packed, 10)
    }

    @Test
    fun directP010PackerReturnsNonEmptyPayloadForValidFrame() {
        val frame = frame(
            y = tenBitPlane(0, 256, 512, 1023),
            u = tenBitPlane(700),
            v = tenBitPlane(900),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            bitDepth = 10
        )

        val packed = Npv10FramePacker.packIntoDirectP010(frame)

        assertEquals(12, packed.remaining())
    }

    @Test
    fun directP010PackerDoesNotRejectNativeSuccessWhenPositionIsUnchanged() {
        val source = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt")
            .readText()

        assertTrue(source.contains("target.position(payloadBytes)"))
        assertFalse(source.contains("target.position() > 0"))
    }

    @Test
    fun paddedNativeP010PlanesCopyDirectlyWithoutFrameSizedArrays() {
        val y = directBuffer(
            1, 2, 3, 4, 99, 99,
            5, 6, 7, 8, 99, 99
        )
        val uv = directBuffer(9, 10, 11, 12, 88, 88)
        val target = ByteBuffer.allocateDirect(12)

        val copied = Npv10FramePacker.copyP010PlaneBuffersDirect(
            yBuffer = y,
            uvBuffer = uv,
            width = 2,
            height = 2,
            yRowStride = 6,
            uvRowStride = 6,
            yPixelStride = 2,
            uvPixelStride = 4,
            target = target
        )

        assertEquals(12, copied)
        target.flip()
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            List(target.remaining()) { target.get().toInt() and 0xFF }
        )
    }

    @Test
    fun qtiStyleSeparateP010ChromaViewsCopyIntoCanonicalInterleavedPayload() {
        val y = directBuffer(
            1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16
        )
        val u = directBuffer(21, 22, 99, 99, 23, 24)
        val v = directBuffer(31, 32, 88, 88, 33, 34)
        val target = ByteBuffer.allocateDirect(24)

        val copied = Npv10FramePacker.copyP010ThreePlaneBuffersDirect(
            yBuffer = y,
            uBuffer = u,
            vBuffer = v,
            width = 4,
            height = 2,
            yRowStride = 8,
            uRowStride = 8,
            vRowStride = 8,
            yPixelStride = 2,
            uPixelStride = 4,
            vPixelStride = 4,
            target = target
        )

        assertEquals(24, copied)
        target.flip()
        assertEquals(
            listOf(
                1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16,
                21, 22, 31, 32, 23, 24, 33, 34
            ),
            List(target.remaining()) { target.get().toInt() and 0xFF }
        )
    }

    @Test
    fun p010ImageCopyLoadsNativeLibraryAndHasDirectBufferFallback() {
        val source = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt")
            .readText()

        assertTrue(source.contains("System.loadLibrary(\"neoproc_vulkan\")"))
        assertTrue(source.contains("copyP010ImageIntoDirectBufferWithResult"))
        assertTrue(source.contains("P010CopyPath.NATIVE_DIRECT"))
        assertTrue(source.contains("P010CopyPath.DIRECT_BUFFER"))
        assertTrue(source.contains("copyP010PlaneBuffersDirect("))
    }

    @Test
    fun raw12PackingPreservesEverySensorCodeInP010PaddingWithCrc4() {
        val raw = intArrayOf(0, 1, 2, 3, 4, 17, 255, 1023, 2048, 3071, 4094, 4095)

        val packed = Npv10FramePacker.packRaw12ToNpv10P010Words(raw)
        val unpacked = Npv10FramePacker.unpackRaw12FromNpv10P010Words(packed)

        assertEquals(raw.toList(), unpacked.toList())
        raw.forEachIndexed { index, sample ->
            val word = readWord(packed, index * 2)
            assertEquals(sample ushr 2, word ushr 6)
            assertEquals(sample and 0x03, (word ushr 4) and 0x03)
            assertEquals(Npv10FramePacker.crc4Raw12(sample), word and 0x0F)
        }
    }

    @Test
    fun raw14PackingPreservesSensorCodesAndStoresParityInLowestBits() {
        val raw = intArrayOf(0, 1, 15, 16, 1023, 4095, 8192, 12001, 16382, 16383)

        val packed = Npv10FramePacker.packRaw14ToNpv10P010Words(raw, frameParitySeed = 2)
        val unpacked = Npv10FramePacker.unpackRaw14FromNpv10P010Words(packed)

        assertEquals(raw.toList(), unpacked.toList())
        raw.forEachIndexed { index, sample ->
            val word = readWord(packed, index * 2)
            assertEquals(sample ushr 4, word ushr 6)
            assertEquals(sample and 0x0F, (word ushr 2) and 0x0F)
            assertEquals((2 + index) and 0x03, word and 0x03)
        }
    }

    private fun assertSampleEquals(expected: Int, payload: ByteArray, offset: Int) {
        val actual = (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)
        assertEquals(expected, actual)
    }

    private fun assertP010SampleEquals(expected: Int, payload: ByteArray, offset: Int) {
        val actual = readWord(payload, offset)
        assertEquals(expected shl 6, actual)
    }

    private fun readWord(payload: ByteArray, offset: Int): Int =
        (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)

    private fun frame(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yRowStride: Int = 2,
        yPixelStride: Int = 1,
        uRowStride: Int = 1,
        vRowStride: Int = 1,
        uPixelStride: Int = 1,
        vPixelStride: Int = 1,
        bitDepth: Int = 8
    ) = RawYuvFrame(
        width = 2,
        height = 2,
        yBytes = y,
        uBytes = u,
        vBytes = v,
        yRowStride = yRowStride,
        yPixelStride = yPixelStride,
        uRowStride = uRowStride,
        vRowStride = vRowStride,
        uPixelStride = uPixelStride,
        vPixelStride = vPixelStride,
        timestamp = 123_000L,
        bitDepth = bitDepth
    )

    private fun tenBitPlane(vararg samples: Int): ByteArray {
        val output = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(0, 1023)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0x03).toByte()
        }
        return output
    }

    private fun directBuffer(vararg bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            bytes.forEach { put(it.toByte()) }
            flip()
        }
}
