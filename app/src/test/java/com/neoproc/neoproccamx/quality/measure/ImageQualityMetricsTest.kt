package com.neoproc.neoproccamx.quality.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * CP01 acceptance: each measurement primitive must detect a known-good vs known-bad synthetic
 * input and recover analytically-known values within tolerance.
 */
class ImageQualityMetricsTest {

    // ---- SNR ----

    @Test
    fun flatFieldSnrMatchesKnownMeanOverSigma() {
        // Half the samples at mean+sigma, half at mean-sigma => exact mean and exact stddev=sigma.
        val mean = 0.5f
        val sigma = 0.01f
        val n = 1000
        val luma = FloatArray(n) { if (it % 2 == 0) mean + sigma else mean - sigma }
        val expected = 20.0 * kotlin.math.log10((mean / sigma).toDouble()) // ~33.98 dB
        assertEquals(expected, ImageQualityMetrics.flatFieldSnrDb(luma), 0.3)
    }

    @Test
    fun flatFieldSnrCapsForNoiselessPatch() {
        val luma = FloatArray(256) { 0.42f }
        assertEquals(ImageQualityMetrics.SNR_CAP_DB, ImageQualityMetrics.flatFieldSnrDb(luma), 1e-9)
    }

    @Test
    fun flatFieldSnrDropsAsNoiseRises() {
        val clean = noisyFlatField(mean = 0.5f, sigma = 0.005f, n = 4096, seed = 11)
        val noisy = noisyFlatField(mean = 0.5f, sigma = 0.05f, n = 4096, seed = 11)
        assertTrue(
            "more noise must lower SNR",
            ImageQualityMetrics.flatFieldSnrDb(clean) > ImageQualityMetrics.flatFieldSnrDb(noisy) + 10.0
        )
    }

    @Test
    fun frameLumaSnrRecoversNoiseFloorOnFlatFrame() {
        // A uniformly-noisy flat frame: the flattest-tile noise floor ≈ the global sigma, so the
        // measured SNR ≈ 20*log10(mean/sigma).
        val w = 128
        val h = 128
        val mean = 0.5f
        val sigma = 0.01f
        val rng = java.util.Random(7)
        val img = FloatArray(w * h) { (mean + sigma * rng.nextGaussian().toFloat()).coerceIn(0f, 1f) }
        val expected = 20.0 * kotlin.math.log10((mean / sigma).toDouble()) // ~33.98 dB
        assertEquals(expected, ImageQualityMetrics.frameLumaSnrDb(img, w, h), 2.0)
    }

    @Test
    fun frameLumaSnrIgnoresSceneStructureAndTracksNoiseFloor() {
        // A high-contrast checkerboard (lots of structure) with only light noise must still report
        // a HIGH SNR, because the flattest tiles capture the low noise floor, not the edges.
        val w = 128
        val h = 128
        val sigma = 0.004f
        val rng = java.util.Random(3)
        val img = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val base = if (((x / 16) + (y / 16)) % 2 == 0) 0.2f else 0.8f
            (base + sigma * rng.nextGaussian().toFloat()).coerceIn(0f, 1f)
        }
        val snr = ImageQualityMetrics.frameLumaSnrDb(img, w, h, tile = 16)
        assertTrue("structured-but-clean frame must read as high SNR, got $snr", snr > 40.0)
    }

    @Test
    fun frameLumaSnrByteOverloadMatchesFloatVersion() {
        // The 8-bit Y-plane overload (with strides) must agree with the float version on the same
        // pixels within quantization tolerance.
        val w = 96
        val h = 96
        val mean = 0.5f
        val sigma = 0.02f
        val rng = java.util.Random(21)
        val floatImg = FloatArray(w * h) { (mean + sigma * rng.nextGaussian().toFloat()).coerceIn(0f, 1f) }
        val y = ByteArray(w * h) { (floatImg[it] * 255f).toInt().coerceIn(0, 255).toByte() }
        val floatSnr = ImageQualityMetrics.frameLumaSnrDb(floatImg, w, h)
        val byteSnr = ImageQualityMetrics.frameLumaSnrDbFromY(y, w, h, rowStride = w, pixelStride = 1)
        assertEquals(floatSnr, byteSnr, 2.0)
    }

    // ---- MTF50 ----

    @Test
    fun mtf50RecoversGaussianAnalyticValue() {
        val sigma = 1.0
        val (img, w, h) = slantedEdge(width = 64, height = 64, edge0 = 32.0, slope = 0.06, sigma = sigma)
        val measured = ImageQualityMetrics.slantedEdgeMtf50(img, w, h)
        // Gaussian LSF MTF = exp(-2*(pi*sigma*f)^2) => MTF50 freq = sqrt(ln2/2)/(pi*sigma).
        val analytic = sqrt(ln(2.0) / 2.0) / (PI * sigma) // ~0.1874 cy/px
        assertEquals(analytic, measured, analytic * 0.18)
    }

    @Test
    fun mtf50IsHigherForSharperEdge() {
        val sharp = slantedEdge(64, 64, 32.0, 0.06, sigma = 0.7)
        val blur = slantedEdge(64, 64, 32.0, 0.06, sigma = 2.0)
        val mSharp = ImageQualityMetrics.slantedEdgeMtf50(sharp.first, sharp.second, sharp.third)
        val mBlur = ImageQualityMetrics.slantedEdgeMtf50(blur.first, blur.second, blur.third)
        assertTrue("sharper edge must have higher MTF50 ($mSharp vs $mBlur)", mSharp > mBlur)
    }

    // ---- dE2000 / color ----

    @Test
    fun deltaE2000IsZeroForIdenticalLab() {
        val lab = floatArrayOf(53.4f, 12.0f, -8.0f)
        assertEquals(0.0, ImageQualityMetrics.deltaE2000(lab, lab), 1e-6)
    }

    @Test
    fun srgbToLabMapsWhiteAndGray() {
        val white = ImageQualityMetrics.srgbToLab(1f, 1f, 1f)
        assertEquals(100.0, white[0].toDouble(), 0.2)
        assertEquals(0.0, white[1].toDouble(), 0.3)
        assertEquals(0.0, white[2].toDouble(), 0.3)
        val gray = ImageQualityMetrics.srgbToLab(0.5f, 0.5f, 0.5f)
        assertEquals(53.39, gray[0].toDouble(), 0.5) // L* of sRGB 0.5 mid-gray
        assertTrue(abs(gray[1]) < 0.3 && abs(gray[2]) < 0.3)
    }

    @Test
    fun colorCheckerDeltaFlagsAColorShift() {
        val refs = listOf(
            ImageQualityMetrics.srgbToLab(0.76f, 0.60f, 0.51f), // skin-ish
            ImageQualityMetrics.srgbToLab(0.30f, 0.48f, 0.68f), // sky-ish
            ImageQualityMetrics.srgbToLab(0.36f, 0.47f, 0.27f)  // foliage-ish
        )
        val clean = ImageQualityMetrics.colorCheckerDeltaE2000(refs, refs)
        assertEquals(0.0, clean.first, 1e-6)
        assertEquals(0.0, clean.second, 1e-6)
        // Warm/green-shifted measurement must register a clear dE.
        val shifted = listOf(
            ImageQualityMetrics.srgbToLab(0.82f, 0.58f, 0.46f),
            ImageQualityMetrics.srgbToLab(0.30f, 0.50f, 0.62f),
            ImageQualityMetrics.srgbToLab(0.38f, 0.50f, 0.25f)
        )
        val (mean, max) = ImageQualityMetrics.colorCheckerDeltaE2000(shifted, refs)
        assertTrue("shift must produce measurable dE (mean=$mean)", mean > 1.5)
        assertTrue(max >= mean)
    }

    // ---- banding ----

    @Test
    fun bandingScoreRisesWithPosterization() {
        val n = 512
        val smooth = FloatArray(n) { it.toFloat() / (n - 1) }
        val posterized = FloatArray(n) {
            val levels = 8
            val q = Math.round(smooth[it] * (levels - 1)).toFloat() / (levels - 1)
            q
        }
        val smoothRes = ImageQualityMetrics.rampBanding(smooth)
        val postRes = ImageQualityMetrics.rampBanding(posterized)
        assertTrue("smooth ramp should have many levels", smoothRes.distinctLevels > 200)
        assertTrue("posterized ramp collapses to ~8 levels", postRes.distinctLevels <= 8)
        assertTrue(
            "posterized banding score must exceed smooth (${postRes.bandingScore} vs ${smoothRes.bandingScore})",
            postRes.bandingScore > smoothRes.bandingScore * 5
        )
    }

    // ---- temporal flicker ----

    @Test
    fun temporalFlickerZeroForStaticFrames() {
        val frame = FloatArray(256) { (it % 7) / 7f }
        val frames = listOf(frame.copyOf(), frame.copyOf(), frame.copyOf())
        assertEquals(0.0, ImageQualityMetrics.temporalFlickerStd(frames), 1e-9)
    }

    @Test
    fun temporalFlickerMatchesInjectedJitter() {
        // Each pixel toggles +/- jitter across two frames => per-pixel std == jitter.
        val jitter = 0.02f
        val len = 4096
        val base = FloatArray(len) { 0.5f }
        val f1 = FloatArray(len) { base[it] + jitter }
        val f2 = FloatArray(len) { base[it] - jitter }
        val measured = ImageQualityMetrics.temporalFlickerStd(listOf(f1, f2))
        assertEquals(jitter.toDouble(), measured, 1e-4)
    }

    @Test
    fun temporalChromaCrawlIgnoresLumaOnlyFlicker() {
        // Two frames that differ only in overall brightness (luma) but keep identical color ratios
        // must read ~0 chroma-crawl, even though luma flicker is large.
        val pixels = 256
        val f1 = FloatArray(pixels * 3) { 0.3f }
        val f2 = FloatArray(pixels * 3) { 0.6f } // pure luma scale, neutral gray both frames
        assertTrue(
            "neutral luma-only change must not register as chroma-crawl",
            ImageQualityMetrics.temporalChromaCrawl(listOf(f1, f2)) < 1e-6
        )
    }

    @Test
    fun temporalChromaCrawlDetectsColorInstability() {
        // A scene whose hue swings between frames (R-heavy vs B-heavy) must register chroma-crawl.
        val pixels = 256
        val warm = FloatArray(pixels * 3) { i -> if (i % 3 == 0) 0.7f else 0.3f } // red push
        val cool = FloatArray(pixels * 3) { i -> if (i % 3 == 2) 0.7f else 0.3f } // blue push
        val crawl = ImageQualityMetrics.temporalChromaCrawl(listOf(warm, cool))
        assertTrue("hue swing must register chroma-crawl, got $crawl", crawl > 0.1)
    }

    // ---- helpers ----

    private fun noisyFlatField(mean: Float, sigma: Float, n: Int, seed: Long): FloatArray {
        val rng = java.util.Random(seed)
        return FloatArray(n) { (mean + sigma * rng.nextGaussian().toFloat()).coerceIn(0f, 1f) }
    }

    /** Generates a slanted step edge blurred by a Gaussian of stddev [sigma] (erf profile). */
    private fun slantedEdge(
        width: Int,
        height: Int,
        edge0: Double,
        slope: Double,
        sigma: Double
    ): Triple<FloatArray, Int, Int> {
        val img = FloatArray(width * height)
        val s = sigma * sqrt(2.0)
        for (y in 0 until height) {
            val edge = edge0 + slope * y
            for (x in 0 until width) {
                // 0.5*(1+erf((x-edge)/(sigma*sqrt2))) — integral of the Gaussian PSF.
                img[y * width + x] = (0.5 * (1.0 + erf((x - edge) / s))).toFloat()
            }
        }
        return Triple(img, width, height)
    }

    /** Abramowitz & Stegun 7.1.26 approximation of erf. */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return sign(x) * y
    }
}
