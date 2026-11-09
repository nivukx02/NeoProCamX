package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop.DevelopedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-layout contract for the 10-bit MOV packers. Locks row alignment (256-byte r210 / 128-byte
 * v210) and word order so the produced samples decode in NLEs — the structural class of the prior
 * "undecodable v210" failure.
 */
class RawTenBitPackerTest {

    private fun frame(w: Int, h: Int, rgb: ShortArray) = DevelopedFrame(w, h, 10, rgb)

    private fun leWord(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test
    fun r210RowsAlignTo256Bytes() {
        assertEquals(256, RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, 1))
        assertEquals(256, RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, 64))
        assertEquals(512, RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, 65))
    }

    @Test
    fun r210PacksBigEndianRgbWord() {
        // R=1023, G=512, B=0 → (0x3FF<<20)|(0x200<<10)|0 = 0x3FF80000, big-endian.
        val f = frame(1, 1, shortArrayOf(1023, 512, 0))
        val out = RawTenBitPacker.pack(f, RawTenBitPacker.Format.R210_444)
        assertEquals(256, out.size) // one 64-aligned row
        assertEquals(0x3F, out[0].toInt() and 0xFF)
        assertEquals(0xF8, out[1].toInt() and 0xFF)
        assertEquals(0x00, out[2].toInt() and 0xFF)
        assertEquals(0x00, out[3].toInt() and 0xFF)
    }

    @Test
    fun v210RowsAlignTo128Bytes() {
        assertEquals(128, RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, 6))
        assertEquals(128, RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, 48))
        assertEquals(256, RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, 49))
    }

    @Test
    fun v210BlackMapsToLimitedRangeFloorWithNeutralChroma() {
        val f = frame(6, 1, ShortArray(6 * 3) { 0 })
        val out = RawTenBitPacker.pack(f, RawTenBitPacker.Format.V210_422)
        assertEquals(128, out.size)
        val w0 = leWord(out, 0)
        val cb0 = w0 and 0x3FF
        val y0 = (w0 shr 10) and 0x3FF
        val cr0 = (w0 shr 20) and 0x3FF
        assertEquals("limited-range luma floor", 64, y0)
        assertEquals("neutral Cb", 512, cb0)
        assertEquals("neutral Cr", 512, cr0)
    }

    @Test
    fun v210WhiteMapsToLimitedRangeCeilingWithNeutralChroma() {
        val f = frame(6, 1, ShortArray(6 * 3) { 1023 })
        val out = RawTenBitPacker.pack(f, RawTenBitPacker.Format.V210_422)
        val w0 = leWord(out, 0)
        val y0 = (w0 shr 10) and 0x3FF
        val cb0 = w0 and 0x3FF
        val cr0 = (w0 shr 20) and 0x3FF
        assertEquals("limited-range luma ceiling", 940, y0)
        assertTrue("achromatic white keeps Cb≈512", kotlin.math.abs(cb0 - 512) <= 1)
        assertTrue("achromatic white keeps Cr≈512", kotlin.math.abs(cr0 - 512) <= 1)
    }

    @Test
    fun payloadSizeMatchesPackedLength() {
        val f = frame(50, 4, ShortArray(50 * 4 * 3) { 300 })
        assertEquals(
            RawTenBitPacker.payloadSize(RawTenBitPacker.Format.R210_444, 50, 4),
            RawTenBitPacker.pack(f, RawTenBitPacker.Format.R210_444).size
        )
        assertEquals(
            RawTenBitPacker.payloadSize(RawTenBitPacker.Format.V210_422, 50, 4),
            RawTenBitPacker.pack(f, RawTenBitPacker.Format.V210_422).size
        )
    }
}
