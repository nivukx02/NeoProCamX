package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CP-QV16 — JVM property golden for the gated spatial denoiser. A full host port of `denoise.comp`
 * (bilateral + NLM + guided + Anscombe-Oklab-Bayes + Kalman-Bucy + Schrödinger) is infeasible in a unit
 * test, so this LOCKS the detail-safe PROPERTY on the one leg already ported host-side —
 * [WaveletDetailSafeReference.waveletDenoise], the flat-region wavelet soft-threshold that the live
 * nivukx path lacks. On a synthetic flat-region + hard-edge image with added Gaussian noise it asserts:
 *   (1) the flat region's variance drops by > 40 % (real denoising), and
 *   (2) the hard edge's CIEDE2000 stays < 1.0 vs the clean edge (detail-safe: the edge is not smeared).
 *
 * What is locked: the wavelet soft-threshold leg's denoise-vs-preserve behavior. What is NOT covered
 * here (device-gated, unverifiable in the JVM): the GPU dispatch/barrier/copy-back and the remaining
 * spatial stages of denoise.comp.
 */
class DenoiseSpatialGoldenTest {

    /** Deterministic Box–Muller Gaussian stream (same generator style as [WaveletDetailSafeTest]). */
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

    private fun variance(values: List<Float>): Double {
        val mean = values.sumOf { it.toDouble() } / values.size
        return values.sumOf { val d = it - mean; d * d } / values.size
    }

    @Test
    fun waveletLegDropsFlatRegionVarianceWhilePreservingTheEdge() {
        val w = 48
        val h = 48
        val edge = w / 2                 // vertical hard edge down the middle
        val darkLevel = 0.30f
        val brightLevel = 0.70f

        // Clean image: flat-left / flat-right split by a hard edge.
        val clean = FloatArray(w * h) { i -> if ((i % w) < edge) darkLevel else brightLevel }

        // Add per-pixel Gaussian noise (sigma 0.05) to build the noisy input.
        val noise = gaussianSeq(w * h, 20260701L)
        val noisy = FloatArray(w * h) { i -> (clean[i] + 0.05 * noise[i]).toFloat().coerceIn(0f, 1f) }

        // Run the ported wavelet soft-threshold denoise (the detail-safe leg of denoise.comp).
        val denoised = FloatArray(w * h) { i ->
            WaveletDetailSafeReference.waveletDenoise(noisy, w, h, i % w, i / w, threshold = 0.03f, noiseLevel = 0.4f)
        }

        // (1) Flat-region variance must drop > 40 %. Sample an interior patch of the dark-left region,
        // away from the border/edge so we measure genuine flat-field noise reduction.
        fun flatPatch(img: FloatArray): List<Float> {
            val out = ArrayList<Float>()
            for (y in 6 until h - 6) for (x in 6 until edge - 6) out.add(img[y * w + x])
            return out
        }
        val noisyVar = variance(flatPatch(noisy))
        val denoisedVar = variance(flatPatch(denoised))
        assertTrue("flat-region noise must be present in the input ($noisyVar)", noisyVar > 1e-4)
        val drop = 1.0 - denoisedVar / noisyVar
        assertTrue("flat-region variance must drop > 40% (was $noisyVar -> $denoisedVar, drop=${"%.3f".format(drop)})", drop > 0.40)

        // (2) The hard edge must stay intact: the denoised dark/bright plateau colors must remain within
        // CIEDE2000 < 1.0 of the CLEAN plateau colors (a smeared edge would pull the plateaus together and
        // blow past 1.0). Measure the mean of a column just inside each side of the edge.
        fun columnMean(img: FloatArray, x: Int): Float {
            var acc = 0.0
            for (y in 6 until h - 6) acc += img[y * w + x]
            return (acc / (h - 12)).toFloat()
        }
        val cleanDark = columnMean(clean, edge - 3)
        val cleanBright = columnMean(clean, edge + 3)
        val denoisedDark = columnMean(denoised, edge - 3)
        val denoisedBright = columnMean(denoised, edge + 3)

        fun gray(v: Float) = ImageQualityMetrics.srgbToLab(v, v, v)
        val deDark = ImageQualityMetrics.deltaE2000(gray(cleanDark), gray(denoisedDark))
        val deBright = ImageQualityMetrics.deltaE2000(gray(cleanBright), gray(denoisedBright))
        assertTrue("dark plateau must stay detail-safe (deltaE2000=$deDark)", deDark < 1.0)
        assertTrue("bright plateau must stay detail-safe (deltaE2000=$deBright)", deBright < 1.0)
    }
}
