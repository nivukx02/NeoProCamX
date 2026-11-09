package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P4 Phase A — verifies the NPV10 export preview-grade LUT stage
 * ([Npv10ExportEnhancementPolicy.applyPreviewGradeLut]) applies the live ColorScience grade correctly.
 * The identity round-trip proves the P010 YCbCr<->RGB decode/encode are exact inverses (any range/matrix
 * error would drift the samples), so the WYSIWYG grade is byte-honest.
 */
class Npv10ExportGradeParityTest {

    private val width = 4
    private val height = 4

    private fun writeSample(b: ByteArray, offset: Int, sample: Int) {
        val value = sample.coerceIn(0, 1023) shl 6
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun readSample(b: ByteArray, offset: Int): Int {
        val value = (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
        return value ushr 6
    }

    // P010 semi-planar: Y plane (w*h) then interleaved Cb/Cr at 4:2:0 ((w/2)*(h/2) pairs).
    private fun buildP010(yValues: IntArray, u: Int, v: Int): ByteArray {
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val bytes = ByteArray((width * height + chromaW * chromaH * 2) * 2)
        var off = 0
        for (y in yValues) {
            writeSample(bytes, off, y)
            off += 2
        }
        repeat(chromaW * chromaH) {
            writeSample(bytes, off, u)
            writeSample(bytes, off + 2, v)
            off += 4
        }
        return bytes
    }

    private fun identityLut(n: Int): FloatArray {
        val arr = FloatArray(n * n * n * 3)
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val idx = (ri + gi * n + bi * n * n) * 3
            arr[idx] = ri / (n - 1f)
            arr[idx + 1] = gi / (n - 1f)
            arr[idx + 2] = bi / (n - 1f)
        }
        return arr
    }

    @Test
    fun `identity LUT round-trips within one code`() {
        val n = 33
        // In-gamut mid values + near-neutral chroma so no channel clamps (which would break exactness).
        val yValues = intArrayOf(
            320, 360, 400, 440,
            480, 512, 544, 576,
            300, 620, 500, 460,
            380, 600, 420, 560
        )
        val payload = buildP010(yValues, u = 522, v = 502)
        val original = payload.copyOf()
        Npv10ExportEnhancementPolicy.applyPreviewGradeLut(payload, width, height, identityLut(n), n)
        var offset = 0
        while (offset < payload.size) {
            val a = readSample(original, offset)
            val b = readSample(payload, offset)
            assertTrue("sample @${offset} drifted $a -> $b under identity LUT", abs(a - b) <= 1)
            offset += 2
        }
    }

    @Test
    fun `red-boost LUT raises exported Cr chroma`() {
        val n = 33
        val redBoost = identityLut(n).also { arr ->
            for (i in 0 until n * n * n) arr[i * 3] = (arr[i * 3] + 0.15f).coerceIn(0f, 1f)
        }
        val payload = buildP010(IntArray(width * height) { 500 }, u = 512, v = 512) // neutral gray
        Npv10ExportEnhancementPolicy.applyPreviewGradeLut(payload, width, height, redBoost, n)
        val chromaBase = width * height * 2
        val newV = readSample(payload, chromaBase + 2) // first Cr sample
        assertTrue("red boost should raise Cr(v) above neutral 512, got $newV", newV > 512)
    }

    @Test
    fun `degenerate LUT size is a no-op`() {
        val payload = buildP010(IntArray(width * height) { 500 }, u = 540, v = 470)
        val original = payload.copyOf()
        Npv10ExportEnhancementPolicy.applyPreviewGradeLut(payload, width, height, FloatArray(3), 1)
        assertTrue("lutSize<2 must not touch pixels", original.contentEquals(payload))
    }
}
