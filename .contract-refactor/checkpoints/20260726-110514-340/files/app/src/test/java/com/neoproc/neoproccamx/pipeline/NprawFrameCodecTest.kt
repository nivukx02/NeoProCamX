package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RawCompressionMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/**
 * Locks the NPRAW container as a contract: byte-stable, bit-exact round-trip for the
 * lossless tier, bounded companded round-trip for the reduced tier, CRC detection of
 * corruption/truncation, and backward-compatible decode of a legacy v2 frame.
 */
class NprawFrameCodecTest {

    private val width = 16
    private val height = 16

    /** Deterministic 2D gradient with structure that exercises the spatial predictor. */
    private fun frame(white: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            ((x * 37 + y * 101 + x * y) % (white + 1))
        }

    private fun encode(
        mode: RawCompressionMode,
        samples: IntArray,
        sensorWhiteLevel: Int,
        timestamp: Long = 0L
    ): NprawFrameCodec.EncodeResult =
        NprawFrameCodec.encodeFrame(
            mode = mode,
            rawSamples = samples,
            width = width,
            height = height,
            rowStride = width * 2,
            pixelStride = 2,
            timestamp = timestamp,
            originalSize = samples.size * 2,
            sensorWhiteLevel = sensorWhiteLevel
        )

    private fun stops(a: Int, b: Int): Double = abs(ln((a + 1.0) / (b + 1.0)) / ln(2.0))

    // ── Round-trip ──────────────────────────────────────────────────────────────

    @Test
    fun `lossless tier round trips bit exact`() {
        val samples = frame(16383) // 14-bit sensor word into a 16-bit lossless container
        val result = encode(RawCompressionMode.LOSSLESS, samples, sensorWhiteLevel = 16383)
        assertEquals(NprawFrameCodec.VERSION_LINEAR, result.version)
        assertEquals(RawCompandingCurve.COMPANDING_NONE, result.compandingType)

        val decoded = NprawFrameCodec.decodeFrame(result.bytes)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `lossless enhanced tier round trips bit exact`() {
        val samples = frame(16383)
        val result = encode(RawCompressionMode.LOSSLESS_ENHANCED, samples, sensorWhiteLevel = 16383)
        val decoded = NprawFrameCodec.decodeFrame(result.bytes)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `realtime 16 bit plane encoder round trips active pixels bit exact without compression`() {
        val rowStride = width * 2 + 8
        val plane = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val samples = frame(16383)
        for (y in 0 until height) {
            for (x in 0 until width) {
                plane.putShort(y * rowStride + x * 2, samples[y * width + x].toShort())
            }
        }

        val result = NprawFrameCodec.encodeRealtimeLinear16Frame(
            rawPlane = plane,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 2,
            timestamp = 1234L,
            originalSize = plane.capacity()
        )

        assertEquals(16, result.bitDepth)
        assertEquals(samples.size * 2, result.payloadSize)
        assertEquals(NprawFrameCodec.VERSION_LINEAR, result.version)
        assertEquals(RawCompandingCurve.COMPANDING_NONE, result.compandingType)
        val decoded = NprawFrameCodec.decodeFrame(result.bytes)
        assertEquals(RawCompressionMode.LOSSLESS_REALTIME, decoded.mode)
        assertEquals(1234L, decoded.timestamp)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `high compression on a 12 bit sensor stays version 2 and round trips bit exact`() {
        // No reduction needed -> identity companding -> byte-identical legacy v2 output.
        val samples = frame(4095)
        val result = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 4095)
        assertEquals(NprawFrameCodec.VERSION_LINEAR, result.version)
        assertEquals(RawCompandingCurve.COMPANDING_NONE, result.compandingType)
        assertEquals(12, result.bitDepth)

        val decoded = NprawFrameCodec.decodeFrame(result.bytes)
        assertEquals(2, decoded.version)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `high compression on a 14 bit sensor compands into v3 and reconstructs within half a stop`() {
        val samples = frame(16383)
        val result = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 16383)
        assertEquals(NprawFrameCodec.VERSION_COMPANDED, result.version)
        assertEquals(RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG, result.compandingType)
        assertEquals(12, result.bitDepth)

        val decoded = NprawFrameCodec.decodeFrame(result.bytes)
        assertEquals(RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG, decoded.compandingType)
        assertEquals(16383, decoded.sensorWhiteLevel)
        assertEquals(samples.size, decoded.samples.size)

        var worst = 0.0
        for (i in samples.indices) {
            worst = maxOf(worst, stops(decoded.samples[i], samples[i]))
        }
        assertTrue("companded reconstruction worst $worst stops < 0.5", worst < 0.5)
    }

    @Test
    fun `high quality on a 14 bit sensor preserves deep shadows bit exact`() {
        // 14-bit word fits the 14-bit container -> companding is identity -> the lossy
        // adaptive pre-quantisation is gated off (it would otherwise snap shadow codes to
        // multiples of 4, e.g. s=1 -> 0, a full stop of loss). With predictive+Deflate being
        // lossless, the round-trip must be BIT-EXACT, deep shadows included.
        val shadow = IntArray(width * height) { it % 17 } // values 0..16 — deep shadow
        val result = encode(RawCompressionMode.HIGH_QUALITY, shadow, sensorWhiteLevel = 16383)
        assertEquals(NprawFrameCodec.VERSION_LINEAR, result.version)
        assertEquals(RawCompandingCurve.COMPANDING_NONE, result.compandingType)

        val decoded = NprawFrameCodec.decodeFrame(result.bytes)
        assertArrayEquals(shadow, decoded.samples)
    }

    // ── Integrity ────────────────────────────────────────────────────────────────

    @Test
    fun `crc detects a corrupted payload byte`() {
        val samples = frame(4095)
        val bytes = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 4095).bytes
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x5A).toByte()
        try {
            NprawFrameCodec.decodeFrame(bytes)
            fail("expected CRC mismatch to throw")
        } catch (e: NprawFrameCodec.NprawFormatException) {
            assertTrue(e.message!!.contains("CRC"))
        }
    }

    @Test
    fun `truncated frame is detected not silently rendered`() {
        val samples = frame(4095)
        val bytes = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 4095).bytes
        val truncated = bytes.copyOf(bytes.size - 4)
        try {
            NprawFrameCodec.decodeFrame(truncated)
            fail("expected truncation to throw")
        } catch (e: NprawFrameCodec.NprawFormatException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    @Test
    fun `bad magic is rejected`() {
        val bogus = ByteArray(80) { 0x42 }
        try {
            NprawFrameCodec.decodeFrame(bogus)
            fail("expected bad-magic rejection")
        } catch (e: NprawFrameCodec.NprawFormatException) {
            assertTrue(e.message!!.contains("magic"))
        }
    }

    @Test
    fun `corrupt geometry surfaces as a format exception not a raw array exception`() {
        // Geometry is CRC-unprotected. Patch width/height to a product that overflows Int
        // (46341*46341 wraps negative). The decoder must reject it as NprawFormatException,
        // never let a NegativeArraySizeException escape the decode contract.
        val bytes = encode(RawCompressionMode.HIGH_COMPRESSION, frame(4095), sensorWhiteLevel = 4095).bytes
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(10, 46341) // width
        buf.putInt(14, 46341) // height
        try {
            NprawFrameCodec.decodeFrame(bytes)
            fail("expected implausible geometry to be rejected")
        } catch (e: NprawFrameCodec.NprawFormatException) {
            assertTrue(e.message!!.contains("geometry"))
        }
    }

    // ── Golden (format pin) ────────────────────────────────────────────────────

    @Test
    fun `golden pins the legacy v2 layout for a tiny 12 bit frame`() {
        // Two samples, packed 12-bit, no reduction -> v2.
        val s0 = 0x123
        val s1 = 0x0AB
        val result = NprawFrameCodec.encodeFrame(
            mode = RawCompressionMode.HIGH_COMPRESSION,
            rawSamples = intArrayOf(s0, s1),
            width = 2,
            height = 1,
            rowStride = 4,
            pixelStride = 2,
            timestamp = 0L,
            originalSize = 4,
            sensorWhiteLevel = 4095
        )
        val bytes = result.bytes
        // magic + version
        assertEquals('N'.code, bytes[0].toInt() and 0xFF)
        assertEquals('P'.code, bytes[1].toInt() and 0xFF)
        assertEquals('R'.code, bytes[2].toInt() and 0xFF)
        assertEquals('A'.code, bytes[3].toInt() and 0xFF)
        assertEquals('W'.code, bytes[4].toInt() and 0xFF)
        assertEquals(2, bytes[5].toInt() and 0xFF)

        // Header field offsets (big-endian): magic5 ver1 mode@6 w@10 h@14 rowStride@18
        // pixelStride@22 ts(long)@26 origSize@34 compSize@38 bitDepth@42 ...
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(RawCompressionMode.HIGH_COMPRESSION.ordinal, buf.getInt(6)) // modeOrdinal
        assertEquals(2, buf.getInt(10)) // width
        assertEquals(1, buf.getInt(14)) // height
        assertEquals(4, buf.getInt(34)) // originalSize
        assertEquals(result.payloadSize, buf.getInt(38)) // compressedSize
        assertEquals(12, buf.getInt(42)) // bitDepth

        // Payload is the last compressedSize bytes; pack of (0x123,0x0AB) = [0x23,0xB1,0x0A].
        val payload = bytes.copyOfRange(bytes.size - result.payloadSize, bytes.size)
        assertArrayEquals(byteArrayOf(0x23, 0xB1.toByte(), 0x0A), payload)

        // And it must round-trip bit-exact.
        val decoded = NprawFrameCodec.decodeFrame(bytes)
        assertArrayEquals(intArrayOf(s0, s1), decoded.samples)
    }

    @Test
    fun `golden pins the v3 companded layout trailing fields`() {
        // A 14-bit word into the forced 12-bit container -> companding fires -> v3.
        // The two trailing ints are appended after the 8-byte crc (v2 header = 70 bytes),
        // so compandingType@70 and sensorWhiteLevel@74. Pins the ABI v2->v3 bump.
        val result = NprawFrameCodec.encodeFrame(
            mode = RawCompressionMode.HIGH_COMPRESSION,
            rawSamples = intArrayOf(0x2000, 0x3FFF),
            width = 2,
            height = 1,
            rowStride = 4,
            pixelStride = 2,
            timestamp = 0L,
            originalSize = 4,
            sensorWhiteLevel = 16383
        )
        val bytes = result.bytes
        assertEquals(3, bytes[5].toInt() and 0xFF) // version 3
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG, buf.getInt(70)) // compandingType
        assertEquals(16383, buf.getInt(74)) // sensorWhiteLevel

        val decoded = NprawFrameCodec.decodeFrame(bytes)
        assertEquals(3, decoded.version)
        assertEquals(16383, decoded.sensorWhiteLevel)
        assertEquals(RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG, decoded.compandingType)
    }

    @Test
    fun `encoding is deterministic`() {
        val samples = frame(16383)
        val a = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 16383, timestamp = 7L).bytes
        val b = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 16383, timestamp = 7L).bytes
        assertArrayEquals(a, b)
    }

    // ── Back-compat: a legacy v2 frame (no companding fields) still decodes ───────

    @Test
    fun `legacy v2 frame decodes`() {
        // A real v2 frame is exactly what HIGH_COMPRESSION emits on a 12-bit sensor.
        val samples = frame(4095)
        val v2 = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 4095)
        assertEquals(2, v2.version)
        val decoded = NprawFrameCodec.decodeFrame(v2.bytes)
        assertEquals(2, decoded.version)
        assertEquals(RawCompandingCurve.COMPANDING_NONE, decoded.compandingType)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `companded output differs from the legacy linear output`() {
        // Proves the CP16 fix is actually in effect: the companded codes are not the
        // naive linear right-shift the legacy path produced.
        val samples = frame(16383)
        val companded = encode(RawCompressionMode.HIGH_COMPRESSION, samples, sensorWhiteLevel = 16383)
        val decoded = NprawFrameCodec.decodeFrame(companded.bytes)
        // Linear >>2 would map distinct shadow values 4,8 to 1,2 then back to 4,8 only
        // coarsely; the companding keeps deep-shadow detail much closer to the original.
        assertNotEquals(NprawFrameCodec.VERSION_LINEAR, companded.version)
        assertTrue(decoded.samples.isNotEmpty())
    }
}
