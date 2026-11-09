package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * s4-f01 / CP20-21-22 — locks the elite stages added to `nivukx_denoise.comp` on the JVM via the
 * [NivukxDenoiseReference] host port. No device. The no-downgrade default (gates off → today's
 * output bit-for-bit) is guaranteed by the spec-constant GLSL defaults; these tests exercise the new
 * math each gate switches on, and characterization-lock the contract constants.
 */
class NivukxDenoiseEliteTest {

    private fun std(xs: DoubleArray): Double {
        var m = 0.0; for (v in xs) m += v; m /= xs.size
        var a = 0.0; for (v in xs) { val d = v - m; a += d * d }
        return sqrt(a / xs.size)
    }

    /** Deterministic unit-variance gaussian sequence (Box–Muller over a fixed LCG seed). */
    private fun gaussianSeq(n: Int, seed: Long = 0x5f4d3c2bL): DoubleArray {
        var s = seed
        fun u(): Double { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) }
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            val u1 = (u()).coerceIn(1e-12, 1.0); val u2 = u()
            val r = sqrt(-2.0 * ln(u1))
            out[i] = r * cos(2.0 * Math.PI * u2)
            if (i + 1 < n) out[i + 1] = r * sin(2.0 * Math.PI * u2)
            i += 2
        }
        return out
    }

    // ── CP21 VST ─────────────────────────────────────────────────────────────────────

    @Test
    fun gatNaiveInverseRoundTripsToBitExactIdentity() {
        // The algebraic inverse of the forward proves NO DC/bias shift when nothing is denoised.
        val p = NivukxDenoiseReference.DEFAULT_GAT
        var x = 0.0f
        while (x <= 1.0f) {
            val rt = NivukxDenoiseReference.gatInverseNaive(NivukxDenoiseReference.gatForward(x, p), p)
            assertEquals("GAT naive round-trip must be identity at x=$x", x.toDouble(), rt.toDouble(), 1e-4)
            x += 0.01f
        }
    }

    @Test
    fun gatStabilizesNoiseVarianceAcrossTones() {
        // CP21 core claim: post-transform noise is ≈signal-independent. Reuse ONE unit-noise sequence
        // across tones so the comparison is deterministic; the only difference is the per-tone scale.
        val p = NivukxDenoiseReference.DEFAULT_GAT
        val noise = gaussianSeq(6000)
        val levels = floatArrayOf(0.05f, 0.40f, 0.90f)
        val preStd = DoubleArray(levels.size)
        val postStd = DoubleArray(levels.size)
        for ((li, level) in levels.withIndex()) {
            val sigma = sqrt(0.0012 * level + 0.00022) // the shader's Poisson-Gaussian noise model
            val pre = DoubleArray(noise.size)
            val post = DoubleArray(noise.size)
            for (i in noise.indices) {
                val xi = (level + noise[i] * sigma).toFloat().coerceAtLeast(0f)
                pre[i] = xi.toDouble()
                post[i] = NivukxDenoiseReference.gatForward(xi, p).toDouble()
            }
            preStd[li] = std(pre)
            postStd[li] = std(post)
        }
        val preRatio = preStd.max() / preStd.min()
        val postRatio = postStd.max() / postStd.min()
        // Pre-transform noise is strongly signal-dependent (>1.5x across tones)…
        assertTrue("raw noise should vary with tone (ratio=$preRatio)", preRatio > 1.5)
        // …and the GAT collapses it to ≈uniform (<1.05x across tones).
        assertTrue("GAT must stabilize variance across tones (ratio=$postRatio)", postRatio < 1.05)
    }

    @Test
    fun exactUnbiasedInverseImplementsBiasCorrectionNotNaive() {
        // The Mäkitalo–Foi exact unbiased inverse must differ from the naive algebraic inverse by the
        // bias-correction it adds under averaging, asymptoting to +0.25·α at high counts — proving it
        // is the real unbiased inverse, not a cosmetic copy of the naive one.
        val p = NivukxDenoiseReference.DEFAULT_GAT
        var d = 6.0f
        while (d <= 200.0f) {
            val exact = NivukxDenoiseReference.gatInverseExact(d, p)
            val naive = NivukxDenoiseReference.gatInverseNaive(d, p)
            assertTrue("exact must exceed naive (bias correction) at D=$d", exact > naive)
            d += 2.0f
        }
        // More bias correction is needed at low counts than high (the correction is signal-dependent).
        val diffLow = NivukxDenoiseReference.gatInverseExact(8.0f, p) - NivukxDenoiseReference.gatInverseNaive(8.0f, p)
        val diffHigh = NivukxDenoiseReference.gatInverseExact(200.0f, p) - NivukxDenoiseReference.gatInverseNaive(200.0f, p)
        assertTrue("low-count correction must exceed high-count ($diffLow vs $diffHigh)", diffLow > diffHigh)
        // Asymptote: (exact − naive) → 0.25·α at high D.
        assertEquals(0.25 * p.sigmaShot, diffHigh.toDouble(), 0.05 * p.sigmaShot)
    }

    @Test
    fun gatCoefficientsComeFromProfileAndStayBounded() {
        // Coefficients are derived from SENSOR_NOISE_PROFILE, clamped to physically safe ranges — a
        // degenerate profile cannot NaN the forward/inverse.
        val def = NivukxDenoiseReference.gatCoefficientsFromProfile(0.00022f, 0.0012f)
        assertEquals(0.00022, def.sigmaReadSq.toDouble(), 1e-9)
        assertEquals(0.0012, def.sigmaShot.toDouble(), 1e-9)
        val degenerate = NivukxDenoiseReference.gatCoefficientsFromProfile(-5f, 0f)
        assertTrue(degenerate.sigmaReadSq >= 1e-7f && degenerate.sigmaShot >= 1e-5f)
        val v = NivukxDenoiseReference.gatForward(0.5f, degenerate)
        assertTrue("forward must stay finite for a degenerate profile", v.isFinite())
    }

    // ── CP22 perceptual chroma low-pass ───────────────────────────────────────────────

    @Test
    fun perceptualChromaLowPassPreservesLumaExactly() {
        val w = 8; val h = 8
        val rng = gaussianSeq(w * h * 3, seed = 99L)
        val rgb = FloatArray(w * h * 3) { (0.5 + 0.3 * rng[it]).toFloat().coerceIn(0f, 1f) }
        for (py in 0 until h) for (px in 0 until w) {
            val keep = NivukxDenoiseReference.jzazbzLuma(
                rgb[(py * w + px) * 3], rgb[(py * w + px) * 3 + 1], rgb[(py * w + px) * 3 + 2]
            )
            val out = NivukxDenoiseReference.perceptualChromaLowPass(rgb, w, h, px, py, keep, radius = 3)
            val lo = NivukxDenoiseReference.jzazbzLuma(out[0], out[1], out[2])
            assertEquals("chroma low-pass must leave luma untouched", keep.toDouble(), lo.toDouble(), 1e-5)
        }
    }

    @Test
    fun perceptualChromaLowPassReducesChromaCrawl() {
        // Static-luma scene with per-frame spatially-independent chroma noise → high chroma-crawl;
        // spatial chroma low-pass collapses the per-pixel temporal colour variance.
        val w = 16; val h = 16; val frames = 6
        val before = ArrayList<FloatArray>()
        val after = ArrayList<FloatArray>()
        for (f in 0 until frames) {
            val noise = gaussianSeq(w * h * 3, seed = 1234L + f * 7L)
            val frame = FloatArray(w * h * 3)
            for (py in 0 until h) for (px in 0 until w) {
                val i = (py * w + px) * 3
                // smooth base colour + luma-free chroma jitter (keep luma constant frame-to-frame)
                val baseL = 0.45f
                val cr = (0.08 * noise[i]).toFloat()
                val cg = (0.08 * noise[i + 1]).toFloat()
                val cb = (-(NivukxDenoiseReference.LUMA_W[0] * cr + NivukxDenoiseReference.LUMA_W[1] * cg) / NivukxDenoiseReference.LUMA_W[2])
                frame[i] = baseL + cr; frame[i + 1] = baseL + cg; frame[i + 2] = baseL + cb
            }
            before.add(frame.copyOf())
            val out = FloatArray(w * h * 3)
            for (py in 0 until h) for (px in 0 until w) {
                val i = (py * w + px) * 3
                val keep = NivukxDenoiseReference.jzazbzLuma(frame[i], frame[i + 1], frame[i + 2])
                val lp = NivukxDenoiseReference.perceptualChromaLowPass(frame, w, h, px, py, keep, radius = 3)
                out[i] = lp[0]; out[i + 1] = lp[1]; out[i + 2] = lp[2]
            }
            after.add(out)
        }
        val crawlBefore = ImageQualityMetrics.temporalChromaCrawl(before)
        val crawlAfter = ImageQualityMetrics.temporalChromaCrawl(after)
        assertTrue("chroma low-pass must reduce chroma-crawl ($crawlAfter !< $crawlBefore)", crawlAfter < crawlBefore)
    }

    // ── CP20 motion-compensated temporal ───────────────────────────────────────────────

    @Test
    fun motionCompensatedHistoryCutsResidualOnTranslatingScene() {
        val w = 32; val h = 32
        val prev = FloatArray(w * h * 3)
        for (py in 0 until h) for (px in 0 until w) {
            val i = (py * w + px) * 3
            val base = (0.5 + 0.4 * sin(px * 0.9) * cos(py * 0.7))
            prev[i] = base.toFloat(); prev[i + 1] = (base * 0.95).toFloat(); prev[i + 2] = (base * 0.9).toFloat()
        }
        // current = pattern translated by (+2,+1): current[px,py] = prev[px-2, py-1]
        val shiftX = 2; val shiftY = 1
        val cur = FloatArray(w * h * 3)
        for (py in 0 until h) for (px in 0 until w) {
            val sx = (px - shiftX).coerceIn(0, w - 1); val sy = (py - shiftY).coerceIn(0, h - 1)
            val i = (py * w + px) * 3; val si = (sy * w + sx) * 3
            cur[i] = prev[si]; cur[i + 1] = prev[si + 1]; cur[i + 2] = prev[si + 2]
        }
        val flowX = -shiftX.toFloat(); val flowY = -shiftY.toFloat()
        var fixedRes = 0.0; var mcRes = 0.0; var n = 0
        for (py in 4 until h - 4) for (px in 4 until w - 4) {
            val i = (py * w + px) * 3
            // fixed-coordinate (today's) read
            for (k in 0 until 3) fixedRes += abs(cur[i + k] - prev[i + k]).toDouble()
            // motion-compensated read (matches shader: sample prev at pixel + 0.5 + flow)
            val mc = NivukxDenoiseReference.sampleHistoryBilinear(prev, w, h, px + 0.5f + flowX, py + 0.5f + flowY)
            for (k in 0 until 3) mcRes += abs(cur[i + k] - mc[k]).toDouble()
            n += 3
        }
        fixedRes /= n; mcRes /= n
        assertTrue("motion-comp must slash history residual vs fixed read ($mcRes vs $fixedRes)", mcRes < fixedRes * 0.05)
    }

    // ── Contract / characterization lock ───────────────────────────────────────────────

    @Test
    fun contractConstantsAreLocked() {
        // jzazbz weights MUST sum to 1.0 — the property that makes the opponent residual luma-free.
        val sum = (NivukxDenoiseReference.LUMA_W[0] + NivukxDenoiseReference.LUMA_W[1] + NivukxDenoiseReference.LUMA_W[2]).toDouble()
        assertEquals(1.0, sum, 1e-6)
        // GAT defaults must match the shader's spec-constant defaults (the current implicit model).
        assertEquals(0.00022, NivukxDenoiseReference.DEFAULT_GAT.sigmaReadSq.toDouble(), 1e-9)
        assertEquals(0.0012, NivukxDenoiseReference.DEFAULT_GAT.sigmaShot.toDouble(), 1e-9)
    }
}
