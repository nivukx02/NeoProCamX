package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.NeoLogCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [RawNeoLogDevelop.finishFromAcescg] — the ACEScg→(exposure→NeoLog→quantize) develop TAIL that the
 * GPU RAW path shares with the CPU develop. It applies `NeoLogCurve.quantize(c * expScale, …)` per
 * interleaved component to within a deterministic ~1-LSB anti-banding dither (m5); because that dither is a
 * pure function of sample position, a GPU-produced ACEScg plane still finishes IDENTICALLY to the CPU
 * develop's own tail (leaving the demosaic as the only GPU-vs-CPU difference the converter's parity gate
 * measures).
 */
class RawNeoLogDevelopFinishTest {

    @Test
    fun finishFromAcescgAppliesExposureThenNeoLogQuantizePerComponent() {
        val width = 2
        val height = 2
        val variant = NeoLogCurve.Variant.CINEMA
        val stops = 1.5f
        // Interleaved ACEScg (linear, pre-exposure): mix of shadow/mid/highlight + a negative to prove clamp.
        val acescg = floatArrayOf(
            0.0f, 0.18f, 1.0f,
            0.5f, 0.25f, 0.75f,
            2.0f, 0.05f, 0.33f,
            -0.10f, 0.9f, 0.0f
        )
        val frame = RawNeoLogDevelop.finishFromAcescg(acescg, width, height, variant, stops, 10)

        assertEquals(10, frame.bitDepth)
        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        assertEquals(width * height * 3, frame.rgb.size)

        val expScale = RawExposureNormalizationPolicy.exposureScale(stops)
        for (i in acescg.indices) {
            // Within the ~1-LSB anti-banding dither (m5); rails (clipped black/white) match exactly.
            val expected = NeoLogCurve.quantize(acescg[i] * expScale, variant, 10)
            val actual = frame.rgb[i].toInt()
            assertTrue(
                "component $i: $actual not within 1 LSB of $expected",
                kotlin.math.abs(actual - expected) <= 1
            )
        }
    }

    @Test
    fun finishFromAcescgOutputCodesAreInBitDepthRange() {
        val acescg = FloatArray(4 * 3) { (it % 3) * 0.4f }
        val frame = RawNeoLogDevelop.finishFromAcescg(acescg, 4, 1, NeoLogCurve.Variant.BASE, 0f, 10)
        val maxCode = (1 shl 10) - 1
        frame.rgb.forEach { code ->
            val v = code.toInt() and 0xFFFF
            assertEquals(true, v in 0..maxCode)
        }
    }
}
