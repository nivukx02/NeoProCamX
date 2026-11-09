package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * s4-f06 / CP23 — proves the dormant `denoise.comp` wavelet soft-threshold is a genuine flat-region
 * detail-safe pass (denoises flats, preserves edges) that the live nivukx denoise lacks — the
 * evidence behind the WIRE-eligible (not RETIRE) verdict.
 */
class WaveletDetailSafeTest {

    private fun gaussianSeq(n: Int, seed: Long): DoubleArray {
        var s = seed
        fun u(): Double { s = s * 6364136223846793005L + 1442695040888963407L; return (s ushr 11).toDouble() / (1L shl 53).toDouble() }
        val out = DoubleArray(n); var i = 0
        while (i < n) {
            val u1 = u().coerceIn(1e-12, 1.0); val u2 = u(); val r = sqrt(-2.0 * ln(u1))
            out[i] = r * cos(2.0 * Math.PI * u2); if (i + 1 < n) out[i + 1] = r * sin(2.0 * Math.PI * u2); i += 2
        }
        return out
    }

    @Test
    fun waveletDenoiseImprovesFlatFieldSnr() {
        val w = 32; val h = 32
        val noise = gaussianSeq(w * h, 5L)
        val img = FloatArray(w * h) { (0.5 + 0.03 * noise[it]).toFloat().coerceIn(0f, 1f) }
        val out = FloatArray(w * h) { i -> WaveletDetailSafeReference.waveletDenoise(img, w, h, i % w, i / w, threshold = 0.03f, noiseLevel = 0.4f) }
        val before = ImageQualityMetrics.flatFieldSnrDb(img)
        val after = ImageQualityMetrics.flatFieldSnrDb(out)
        assertTrue("wavelet soft-threshold must raise flat-field SNR ($after vs $before)", after > before)
    }

    @Test
    fun waveletDenoisePreservesEdgesBetterThanAPlainBlur() {
        val w = 32; val h = 32; val edge = w / 2
        val img = FloatArray(w * h) { i -> if ((i % w) < edge) 0.2f else 0.8f }
        // Plain 4-neighbour low-pass (no detail return) — the bleed-prone baseline.
        fun plainBlur(x: Int, y: Int): Float {
            fun a(xx: Int, yy: Int) = img[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
            return (a(x, y - 1) + a(x, y + 1) + a(x + 1, y) + a(x - 1, y)) * 0.25f
        }
        fun contrast(sample: (Int, Int) -> Float): Double {
            var left = 0.0; var right = 0.0; var nl = 0; var nr = 0
            for (y in 4 until h - 4) for (x in 4 until w - 4) {
                if (x < edge) { left += sample(x, y); nl++ } else { right += sample(x, y); nr++ }
            }
            return right / nr - left / nl
        }
        val waveletContrast = contrast { x, y -> WaveletDetailSafeReference.waveletDenoise(img, w, h, x, y, 0.03f, 0.4f) }
        val blurContrast = contrast { x, y -> plainBlur(x, y) }
        assertTrue("wavelet must preserve the edge better than a plain blur ($waveletContrast vs $blurContrast)",
            waveletContrast > blurContrast + 0.01)
        assertTrue("and keep the edge largely intact ($waveletContrast)", waveletContrast > 0.55)
    }
}
