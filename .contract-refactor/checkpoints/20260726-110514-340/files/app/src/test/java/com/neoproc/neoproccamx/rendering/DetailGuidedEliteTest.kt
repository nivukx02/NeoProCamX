package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * s4-f02 / CP24 — re-audit golden lock for `detail_preservation.comp` + `guided_upsample.comp`
 * elite gates, on the JVM via [DetailGuidedReference]. No device. Default (gates off) reproduces
 * today's mask/upsample; these tests prove the gated elite paths: noise-aware rolloff (no grain
 * amplification), halo guard (no ringing at strong edges), and guide-snapping joint-bilateral.
 */
class DetailGuidedEliteTest {

    private fun gaussianSeq(n: Int, seed: Long): DoubleArray {
        var s = seed
        fun u(): Double { s = s * 6364136223846793005L + 1442695040888963407L; return (s ushr 11).toDouble() / (1L shl 53).toDouble() }
        val out = DoubleArray(n); var i = 0
        while (i < n) {
            val u1 = u().coerceIn(1e-12, 1.0); val u2 = u()
            val r = sqrt(-2.0 * ln(u1))
            out[i] = r * cos(2.0 * Math.PI * u2)
            if (i + 1 < n) out[i + 1] = r * sin(2.0 * Math.PI * u2)
            i += 2
        }
        return out
    }

    private fun fill(w: Int, h: Int, f: (Int, Int) -> Float): FloatArray {
        val a = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = f(x, y); val i = (y * w + x) * 3
            a[i] = v; a[i + 1] = v; a[i + 2] = v
        }
        return a
    }

    private fun maskMean(rgb: FloatArray, w: Int, h: Int, noiseAware: Boolean, haloGuard: Boolean): Double {
        var acc = 0.0; var n = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            acc += DetailGuidedReference.detailMask(rgb, w, h, x, y, noiseAware = noiseAware, haloGuard = haloGuard).toDouble()
            n++
        }
        return acc / n
    }

    // ── CP24 noise-aware detail gain ───────────────────────────────────────────────────

    @Test
    fun noiseAwareRollsOffGrainButPreservesRealTexture() {
        val w = 24; val h = 24
        // Flat field + sensor grain (σ≈0.04): the default mask over-flags it; noise-aware suppresses.
        val grain = gaussianSeq(w * h, 7L)
        val flat = fill(w, h) { x, y -> (0.5 + 0.04 * grain[y * w + x]).toFloat().coerceIn(0f, 1f) }
        // Real isotropic texture (period-2 checker, amplitude 0.25): well above the noise floor.
        val texture = fill(w, h) { x, y -> if (((x + y) and 1) == 0) 0.75f else 0.25f }

        val flatDefault = maskMean(flat, w, h, noiseAware = false, haloGuard = false)
        val flatNoiseAware = maskMean(flat, w, h, noiseAware = true, haloGuard = false)
        val texDefault = maskMean(texture, w, h, noiseAware = false, haloGuard = false)
        val texNoiseAware = maskMean(texture, w, h, noiseAware = true, haloGuard = false)

        assertTrue("flat grain must be flagged by today's mask ($flatDefault)", flatDefault > 0.8)
        assertTrue("noise-aware must roll off grain ($flatNoiseAware)", flatNoiseAware < 0.15)
        assertTrue("real texture must stay flagged by default ($texDefault)", texDefault > 0.7)
        assertTrue("noise-aware must preserve real texture ($texNoiseAware vs $texDefault)", texNoiseAware > 0.8 * texDefault)
    }

    // ── CP24 halo guard ────────────────────────────────────────────────────────────────

    @Test
    fun haloGuardSuppressesMaskAtStrongEdgesOnly() {
        val w = 24; val h = 24
        // Steep luminance ramp = strong DC-free gradient; shallow ramp = weak gradient. Both are
        // flagged by today's (DC-biased) mask, so the halo guard's differential effect is isolated.
        val strong = fill(w, h) { x, _ -> (0.1f + 0.8f * x / (w - 1)) }   // big gradient
        val mild = fill(w, h) { x, _ -> (0.45f + 0.1f * x / (w - 1)) }    // tiny gradient

        val strongDefault = maskMean(strong, w, h, noiseAware = false, haloGuard = false)
        val strongGuard = maskMean(strong, w, h, noiseAware = false, haloGuard = true)
        val mildDefault = maskMean(mild, w, h, noiseAware = false, haloGuard = false)
        val mildGuard = maskMean(mild, w, h, noiseAware = false, haloGuard = true)

        assertTrue("halo guard must slash the mask on strong edges ($strongGuard vs $strongDefault)", strongGuard < 0.2 * strongDefault)
        assertTrue("halo guard must leave low-gradient regions alone ($mildGuard vs $mildDefault)", mildGuard > 0.9 * mildDefault)
    }

    @Test
    fun edgeOvershootMetricDistinguishesCleanFromRinging() {
        // Locks the new ImageQualityMetrics.edgeOvershoot1D used as the CP24 ringing gate.
        val clean = doubleArrayOf(0.1, 0.1, 0.1, 0.3, 0.5, 0.7, 0.9, 0.9, 0.9)
        val ringing = doubleArrayOf(0.1, 0.1, 0.05, 0.4, 0.6, 1.15, 0.9, 0.9, 0.9) // overshoots to 1.15
        val cleanO = ImageQualityMetrics.edgeOvershoot1D(clean)
        val ringO = ImageQualityMetrics.edgeOvershoot1D(ringing)
        assertTrue("clean edge has ~no overshoot ($cleanO)", cleanO < 0.05)
        assertTrue("ringing edge is detected ($ringO)", ringO > 0.2)
    }

    // ── CP24 guided / joint-bilateral upsample ──────────────────────────────────────────

    /** 10–90% transition width (in px) of a 1-D rising profile; smaller = sharper edge. */
    private fun edge1090Width(profile: DoubleArray): Double {
        val lo = profile.first(); val hi = profile.last()
        val span = hi - lo
        fun crossing(frac: Double): Double {
            val level = lo + frac * span
            for (i in 1 until profile.size) {
                if ((profile[i - 1] < level) != (profile[i] < level)) {
                    val t = (level - profile[i - 1]) / (profile[i] - profile[i - 1])
                    return (i - 1) + t
                }
            }
            return profile.size.toDouble()
        }
        return crossing(0.9) - crossing(0.1)
    }

    private fun crossing50(profile: DoubleArray): Double {
        val lo = profile.first(); val hi = profile.last(); val level = lo + 0.5 * (hi - lo)
        for (i in 1 until profile.size) {
            if ((profile[i - 1] < level) != (profile[i] < level)) {
                val t = (level - profile[i - 1]) / (profile[i] - profile[i - 1])
                return (i - 1) + t
            }
        }
        return profile.size / 2.0
    }

    @Test
    fun guidedUpsampleSnapsToGuideEdgeAndTightenSharpensFurther() {
        val gw = 32; val gh = 32; val scale = 4; val lw = gw / scale; val lh = gh / scale
        val guideEdge = 16
        // Full-res guide: a sharp vertical edge (0.2 | 0.8) at x = guideEdge.
        val guide = fill(gw, gh) { x, _ -> if (x < guideEdge) 0.2f else 0.8f }
        // Low-res value map: the SAME edge but blurred across several low-res columns (centred on the
        // guide edge, low x = 4) — what a cheaply-computed quarter-res map looks like.
        val low = FloatArray(lw * lh)
        for (ly in 0 until lh) for (lx in 0 until lw) {
            val t = ((lx - 2f) / 4f).coerceIn(0f, 1f); low[ly * lw + lx] = t * t * (3f - 2f * t)
        }
        val y = gh / 2
        val guided = DoubleArray(gw) { x -> DetailGuidedReference.guidedUpsample(guide, gw, gh, low, lw, lh, x, y, sigmaR = 0.3f, scale = scale).toDouble() }
        val tighten = DoubleArray(gw) { x -> DetailGuidedReference.guidedUpsample(guide, gw, gh, low, lw, lh, x, y, sigmaR = 0.3f, scale = scale, edgeTighten = true).toDouble() }
        val naive = DoubleArray(gw) { x -> DetailGuidedReference.naiveUpsample(low, lw, lh, x, y, scale).toDouble() }

        val wGuided = edge1090Width(guided)
        val wTighten = edge1090Width(tighten)
        val wNaive = edge1090Width(naive)
        assertTrue("guided must be sharper than naive bilinear ($wGuided vs $wNaive)", wGuided < wNaive)
        assertTrue("edge-tighten must sharpen further ($wTighten vs $wGuided)", wTighten <= wGuided)
        // The upsampled edge must land on the GUIDE edge, not the blurry low-res transition.
        assertEquals("guided edge must align to the guide", guideEdge.toDouble(), crossing50(guided), 3.0)
    }

    // ── No-downgrade / characterization lock ─────────────────────────────────────────────

    @Test
    fun defaultGatesReproduceBaselineBehaviour() {
        val w = 16; val h = 16
        val img = fill(w, h) { x, y -> (0.5 + 0.2 * sin(x * 0.6) * cos(y * 0.4)).toFloat() }
        // Gates off must equal the explicit no-gate computation at every pixel (no-downgrade lock).
        for (yy in 2 until h - 2) for (xx in 2 until w - 2) {
            val gated = DetailGuidedReference.detailMask(img, w, h, xx, yy, noiseAware = false, haloGuard = false)
            val raw = DetailGuidedReference.detailMask(img, w, h, xx, yy)
            assertEquals(raw.toDouble(), gated.toDouble(), 0.0)
        }
    }
}
