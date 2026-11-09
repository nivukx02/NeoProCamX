package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.pipeline.DistantDetailGatePolicy
import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CP-QV5 / F05 — integration golden that ties [DistantDetailGatePolicy] to the shader-faithful
 * [DetailGuidedReference] via the objective metrics in [ImageQualityMetrics]. No device.
 *
 * We synthesize a slanted edge (for MTF50 + edge overshoot) and a flat grain patch (for SNR), then
 * run a detail re-injection that is modulated by [DetailGuidedReference.detailMask]. The gate-OFF
 * decision (today's shader) uses noiseAware=false / haloGuard=false; the gate-ON decision from the
 * policy flips them on. The golden proves the elite path:
 *  - preserves MTF50 within tolerance (still sharp),
 *  - REDUCES edge overshoot (the halo guard kills ringing at the strong edge),
 *  - holds/raises flat-region SNR (the noise-aware rolloff stops grain amplification).
 *
 * MFSR is out of scope; this exercises only the detail-preservation gates.
 */
class DistantDetailMtfHaloGoldenTest {

    // ── deterministic Gaussian grain (matches DetailGuidedEliteTest) ───────────────────────
    private fun gaussianSeq(n: Int, seed: Long): DoubleArray {
        var s = seed
        fun u(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            val u1 = u().coerceIn(1e-12, 1.0)
            val u2 = u()
            val r = sqrt(-2.0 * ln(u1))
            out[i] = r * cos(2.0 * Math.PI * u2)
            if (i + 1 < n) out[i + 1] = r * sin(2.0 * Math.PI * u2)
            i += 2
        }
        return out
    }

    private fun luma709(rgb: FloatArray, w: Int, x: Int, y: Int): Float {
        val i = (y * w + x) * 3
        return rgb[i] * 0.299f + rgb[i + 1] * 0.587f + rgb[i + 2] * 0.114f
    }

    /**
     * Detail re-injection driven by the shader detail mask: out = base + gain * mask * (base - box3).
     * `box3` is a 3x3 box blur (the low-frequency estimate), so (base - box3) is the high-frequency
     * detail the operator re-injects — exactly where the mask says "protect texture". The halo /
     * noise-aware gates enter ONLY through [DetailGuidedReference.detailMask]'s gate params, so the
     * differential between OFF and ON isolates the gate behaviour.
     */
    private fun reinjectLuma(
        rgb: FloatArray, w: Int, h: Int, gain: Float,
        noiseAware: Boolean, haloGuard: Boolean
    ): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val base = luma709(rgb, w, x, y)
            var sum = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val cx = (x + dx).coerceIn(0, w - 1)
                val cy = (y + dy).coerceIn(0, h - 1)
                sum += luma709(rgb, w, cx, cy)
                n++
            }
            val box = sum / n
            val hi = base - box
            val mask = DetailGuidedReference.detailMask(
                rgb, w, h, x, y,
                noiseAware = noiseAware, haloGuard = haloGuard
            )
            out[y * w + x] = (base + gain * mask * hi).coerceIn(0f, 1f)
        }
        return out
    }

    /** Abramowitz & Stegun 7.1.26 erf approximation (matches ImageQualityMetricsTest). */
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return sign(x) * y
    }

    private fun fillRgb(w: Int, h: Int, f: (Int, Int) -> Float): FloatArray {
        val a = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = f(x, y).coerceIn(0f, 1f)
            val i = (y * w + x) * 3
            a[i] = v; a[i + 1] = v; a[i + 2] = v
        }
        return a
    }

    /** Central row of a re-injected luma image as a 1-D edge profile for the overshoot metric. */
    private fun centralRow(luma: FloatArray, w: Int, h: Int): DoubleArray {
        val y = h / 2
        return DoubleArray(w) { x -> luma[y * w + x].toDouble() }
    }

    @Test
    fun gateOnPreservesMtfReducesOvershootAndHoldsSnr() {
        // The policy decision under an ISO / SNR / profile that trips ALL three gates.
        val decision = DistantDetailGatePolicy.resolve(
            iso = 6400,
            extremeDetail = true,
            snrDb = 20f,
            distantDetailRequested = true
        )
        assertTrue("fixture must trip noise-aware", decision.detailNoiseAware)
        assertTrue("fixture must trip halo guard", decision.detailHaloGuard)
        assertTrue("fixture must trip edge-tighten", decision.guidedEdgeTighten)

        // ── 1) Slanted edge (MTF50 + overshoot) ────────────────────────────────────────────
        val ew = 64
        val eh = 64
        val gain = 1.6f
        // Smooth Gaussian-blurred (erf) slanted step at x=32, slope 0.06 — the same well-conditioned
        // ESF target the ImageQualityMetrics MTF50 tests use, mapped into [0.2, 0.8] so the unsharp
        // over/undershoot fringes have headroom and are NOT hidden by the [0,1] output clamp. It is a
        // STRONG edge, so gate-OFF re-injection rings and the halo guard's DC-free rolloff tames it.
        val edgeSigma = 1.1
        val s = edgeSigma * sqrt(2.0)
        val edgeRgb = fillRgb(ew, eh) { x, y ->
            val edge = 32.0 + 0.06 * y
            val step = 0.5 * (1.0 + erf((x - edge) / s))
            (0.2 + 0.6 * step).toFloat()
        }
        val edgeBaseLuma = FloatArray(ew * eh) { i ->
            val bx = i % ew; val by = i / ew
            luma709(edgeRgb, ew, bx, by)
        }
        val edgeOff = reinjectLuma(edgeRgb, ew, eh, gain, noiseAware = false, haloGuard = false)
        val edgeOn = reinjectLuma(
            edgeRgb, ew, eh, gain,
            noiseAware = decision.detailNoiseAware, haloGuard = decision.detailHaloGuard
        )

        val mtfBase = ImageQualityMetrics.slantedEdgeMtf50(edgeBaseLuma, ew, eh)
        val mtfOn = ImageQualityMetrics.slantedEdgeMtf50(edgeOn, ew, eh)
        val overshootOff = ImageQualityMetrics.edgeOvershoot1D(centralRow(edgeOff, ew, eh))
        val overshootOn = ImageQualityMetrics.edgeOvershoot1D(centralRow(edgeOn, ew, eh))

        // Sharpness must be PRESERVED: the halo-guarded edge never resolves worse than the input
        // edge it started from (it tames the ring, it does not blur the subject).
        assertTrue(
            "gate-ON must preserve MTF50 within tolerance of the source edge (on=$mtfOn base=$mtfBase)",
            mtfOn >= mtfBase * 0.90
        )
        // The halo guard must cut the ringing overshoot the ungated re-injection introduces.
        assertTrue(
            "gate-ON must reduce edge overshoot / halo (on=$overshootOn off=$overshootOff)",
            overshootOn < overshootOff
        )

        // ── 2) Flat grain patch (SNR) ──────────────────────────────────────────────────────
        val fw = 48
        val fh = 48
        val grain = gaussianSeq(fw * fh, 1234567L)
        // Flat mid-grey field with sensor grain at the shader noise sigma; over-sharpening this grain
        // (gate-OFF) drives SNR down, the noise-aware rolloff (gate-ON) holds/raises it.
        val flatRgb = fillRgb(fw, fh) { x, y ->
            (0.5 + DistantDetailGatePolicy.DEFAULT_NOISE_SIGMA * grain[y * fw + x]).toFloat()
        }
        val flatOff = reinjectLuma(flatRgb, fw, fh, gain, noiseAware = false, haloGuard = false)
        val flatOn = reinjectLuma(
            flatRgb, fw, fh, gain,
            noiseAware = decision.detailNoiseAware, haloGuard = decision.detailHaloGuard
        )

        val snrOff = ImageQualityMetrics.frameLumaSnrDb(flatOff, fw, fh, tile = 16)
        val snrOn = ImageQualityMetrics.frameLumaSnrDb(flatOn, fw, fh, tile = 16)
        // Holds or raises the flat-region SNR (no grain amplification). Small epsilon guards float ties.
        assertTrue(
            "gate-ON must hold/raise flat-region SNR (on=$snrOn off=$snrOff)",
            snrOn >= snrOff - 1e-6
        )
    }

    @Test
    fun baselineDecisionReproducesGateOffPathBitForBit() {
        // The DEFAULT decision must feed the reference the exact gate-OFF params — no-downgrade lock.
        val baseline = DistantDetailGatePolicy.resolve(
            iso = 100,
            extremeDetail = false,
            snrDb = 48f,
            distantDetailRequested = false
        )
        assertTrue("default decision must be the baseline", baseline.isBaseline)

        val w = 40
        val h = 40
        val grain = gaussianSeq(w * h, 42L)
        val rgb = fillRgb(w, h) { x, y ->
            val edge = if (x < 20) 0.2f else 0.8f
            (edge + 0.01f * grain[y * w + x]).toFloat()
        }
        val gain = 1.2f
        val explicitOff = reinjectLuma(rgb, w, h, gain, noiseAware = false, haloGuard = false)
        val viaBaseline = reinjectLuma(
            rgb, w, h, gain,
            noiseAware = baseline.detailNoiseAware, haloGuard = baseline.detailHaloGuard
        )
        for (i in explicitOff.indices) {
            assertTrue(
                "baseline decision must reproduce gate-OFF bit-for-bit at $i",
                explicitOff[i] == viaBaseline[i]
            )
        }
    }
}
