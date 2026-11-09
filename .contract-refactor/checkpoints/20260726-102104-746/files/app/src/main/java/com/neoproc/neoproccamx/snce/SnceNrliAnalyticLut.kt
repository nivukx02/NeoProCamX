package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement.SemanticClass
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * CP-QV21 — pure-Kotlin builder for a **near-identity** 33^3 RGB 3D-LUT that carries the SNCE Stage-11
 * NRLI perceptual look, derived analytically from [SnceNeuralEnhancement.PROFILES].
 *
 * The live NRLI shader (`nrli_lut_apply.comp`) is a straight RGB→RGB 3D-LUT sampler with a `strength`
 * blend; it has no semantic mask at sample time. So this builder collapses the per-class CAM16
 * hue/chroma/lightness [SnceNeuralEnhancement.EnhancementProfile] rules into a single, global, **bounded**
 * RGB grade:
 *
 *  - A gentle luma-preserving **chroma** scale (saturation about the per-voxel luma) using the mean
 *    chroma-scale of the enhancement profiles — the profiles overwhelmingly *lift* chroma slightly.
 *  - A subtle **warm** bias (a touch more red, a touch less blue) reflecting the skin/foliage/shadow
 *    hue intent (positive skin/shadow hue shifts, warm memory-colour anchoring), applied on a curve that
 *    is zero at the neutral axis and near-zero at the extremes so greys stay grey.
 *
 * BOUND (documented + test-locked in the CP-QV21 golden): every output channel of every voxel stays
 * within [MAX_DEVIATION] (= 0.03, i.e. ≤ 3% of full scale) of the pure identity value, and the grey axis
 * (r==g==b) is preserved within [GREY_AXIS_TOLERANCE]. Combined with the shader's `mix(color, graded,
 * strength)` this makes strength 0 an exact passthrough and any strength a *subtle*, non-destructive look
 * — never a heavy grade. This is a foundation look; a learned Image-Adaptive-3DLUT model can replace it.
 *
 * Layout matches [SnceAdaptiveLut] / the native `createNrliIdentityLut` voxel order exactly: length =
 * size^3 * 3, R-fastest (`idx = ((b*size + g)*size + r)*3 + channel`), each channel in [0,1].
 */
object SnceNrliAnalyticLut {

    /** Max absolute per-channel deviation from identity, over the whole cube. Keeps the look subtle. */
    const val MAX_DEVIATION: Float = 0.03f

    /** Grey axis (r==g==b) must stay neutral within this tolerance after the grade. */
    const val GREY_AXIS_TOLERANCE: Float = 1e-3f

    /** Rec.709 luma weights — the axis chroma is measured/preserved about. */
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /**
     * Build the near-identity 33^3 look LUT (default size 33 = the native NRLI LUT size). The returned
     * [SnceAdaptiveLut] can be sampled by [SnceNeuralEnhancement.trilinearSampleLut] (CPU reference) and
     * its [SnceAdaptiveLut.rgb] handed straight to
     * [com.neoproc.neoproccamx.rendering.VulkanBridge.updateNrliLut].
     */
    fun buildNearIdentityLut(size: Int = 33): SnceAdaptiveLut {
        require(size >= 2) { "LUT size must be >= 2" }
        val maxIdx = size - 1
        val chromaScale = meanProfileChromaScale()
        // Warm bias magnitude, kept tiny so the bound holds with margin.
        val warmBias = WARM_BIAS
        val rgb = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val rf = r.toFloat() / maxIdx
            val gf = g.toFloat() / maxIdx
            val bf = b.toFloat() / maxIdx
            val out = grade(rf, gf, bf, chromaScale, warmBias)
            rgb[idx++] = out[0]
            rgb[idx++] = out[1]
            rgb[idx++] = out[2]
        }
        return SnceAdaptiveLut(size, rgb)
    }

    /**
     * The analytic grade applied to one RGB voxel. Luma-preserving chroma scale + a curved warm bias that
     * vanishes on the grey axis and at the gamut extremes. Every step is bounded so the whole-cube result
     * stays within [MAX_DEVIATION] of identity (see [buildNearIdentityLut]'s BOUND note).
     */
    fun grade(r: Float, g: Float, b: Float, chromaScale: Float, warmBias: Float): FloatArray {
        val luma = LR * r + LG * g + LB * b
        // Luma-preserving chroma scale: push each channel away from (or toward) luma. Clamp the scale so a
        // single voxel can never deviate more than the bound even at maximum chroma.
        val s = clampChromaScale(chromaScale)
        var rr = luma + (r - luma) * s
        var gg = luma + (g - luma) * s
        var bb = luma + (b - luma) * s

        // Curved warm bias: strongest at mid-luma, zero at luma 0 and 1 (extremes stay put), and it scales
        // with distance from the grey axis so true greys are untouched. sat is in [0,1)-ish.
        val sat = maxOf(abs(r - luma), abs(g - luma), abs(b - luma))
        val lumaWindow = 4f * luma * (1f - luma) // parabola, peak 1.0 at luma 0.5, 0 at the ends
        val warm = warmBias * lumaWindow * sat
        rr += warm
        bb -= warm

        return floatArrayOf(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
    }

    /**
     * Mean of the profile chroma-scales (excluding OTHER's identity 1.0 and the cap-only SNOW/METAL rules
     * that don't set a scale). The SNCE profiles lean to a *slight* chroma lift, so this is ~1.0..1.1; we
     * then clamp it hard in [clampChromaScale] so the near-identity bound always holds.
     */
    private fun meanProfileChromaScale(): Float {
        val scales = SnceNeuralEnhancement.PROFILES
            .filterKeys { it != SemanticClass.OTHER }
            .values
            .map { it.chromaScale }
        if (scales.isEmpty()) return 1f
        return scales.sum() / scales.size
    }

    /**
     * Clamp the chroma scale to the tightest window that still keeps every voxel within [MAX_DEVIATION].
     * Worst-case chroma magnitude |channel - luma| ≤ ~0.79 (pure primary), so |scale - 1| must stay below
     * MAX_DEVIATION / 0.79 ≈ 0.038; we cap at a comfortable margin below that.
     */
    private fun clampChromaScale(scale: Float): Float {
        val maxDelta = 0.03f // < MAX_DEVIATION / worstCaseChroma, with the warm bias budget reserved
        return scale.coerceIn(1f - maxDelta, 1f + maxDelta)
    }

    /** Warm-bias magnitude. Peak contribution warmBias * 1.0 (lumaWindow) * ~0.79 (sat) ≈ 0.008 ≪ bound. */
    private const val WARM_BIAS: Float = 0.01f

    // ---------------------------------------------------------------------------------------------
    // ELITE Rank-1 — hand-graded FILM-PRINT show-LUT (a real colorist look, still bounded + safe).
    //
    // Unlike [buildNearIdentityLut] (a ≤3% nudge), this carries a visible cinema print emulation:
    //   1. a gentle monotone filmic S-curve (contrast about a slightly-low pivot, endpoints fixed);
    //   2. split-tone — warm shadows / cool highlights (the classic teal-and-orange print signature);
    //   3. a luma-preserving midtone chroma lift that PROTECTS deep shadows + bright highlights;
    //   4. a SKIN-HUE LOCK — pixels inside the skin hue band have their hue pulled back to the input,
    //      so the look never sunburns or wax-ifies skin (the exact regression fought before).
    //
    // Every voxel is hard-clamped to within [FILM_MAX_DEVIATION] of identity, so the result can never
    // clip, band, or black out, and the shader's mix(color, graded, strength) keeps strength 0 an exact
    // passthrough. The look is intentionally stronger than the near-identity builder but still a *tasteful*
    // print (ColorChecker ΔE stays modest); heavier looks are a device-tuning dial on the same wire.
    // ---------------------------------------------------------------------------------------------

    /** Max absolute per-channel deviation from identity for the FILM-PRINT look (hard-clamped). */
    const val FILM_MAX_DEVIATION: Float = 0.14f
    private const val CONTRAST_PIVOT: Float = 0.435f
    private const val CONTRAST_STRENGTH: Float = 0.16f
    private const val CONTRAST_STEEPNESS: Float = 3.0f
    private const val SHADOW_WARM: Float = 0.016f
    private const val HIGHLIGHT_COOL: Float = 0.013f
    private const val MID_SAT: Float = 1.10f

    /** Skin hue anchor (deg) + band half-width; DEVICE-TUNABLE for the exact working RGB the shader samples. */
    const val SKIN_HUE_DEG: Float = 51f
    private const val SKIN_HUE_HALFWIDTH: Float = 22f

    /**
     * Build the FILM-PRINT show-LUT (33^3, same ABI/layout as [buildNearIdentityLut]). Hand it straight to
     * [com.neoproc.neoproccamx.rendering.VulkanBridge.updateNrliLut]. Fail-safe by construction: a rejected
     * upload leaves the native identity LUT + strength 0.
     */
    fun buildFilmPrintLut(size: Int = 33): SnceAdaptiveLut {
        require(size >= 2) { "LUT size must be >= 2" }
        val maxIdx = size - 1
        val rgb = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val out = gradeFilmPrint(r.toFloat() / maxIdx, g.toFloat() / maxIdx, b.toFloat() / maxIdx)
            rgb[idx++] = out[0]; rgb[idx++] = out[1]; rgb[idx++] = out[2]
        }
        return SnceAdaptiveLut(size, rgb)
    }

    /** The full film-print grade on one voxel. Bounded to identity ± [FILM_MAX_DEVIATION], clamped to [0,1]. */
    fun gradeFilmPrint(r: Float, g: Float, b: Float): FloatArray {
        // 1. filmic S-curve (per channel, monotone, endpoints fixed, mid-grey ~preserved by the pivot).
        var rr = filmicContrast(r); var gg = filmicContrast(g); var bb = filmicContrast(b)

        // 2. split-tone: warm shadows, cool highlights (teal & orange), luma-weighted so mids stay clean.
        val luma1 = LR * rr + LG * gg + LB * bb
        val shadowW = smoothstep(0.55f, 0.0f, luma1)
        val highW = smoothstep(0.55f, 1.0f, luma1)
        val tone = SHADOW_WARM * shadowW - HIGHLIGHT_COOL * highW
        rr += tone; bb -= tone

        // 3. luma-preserving midtone chroma lift (parabolic window protects deep shadows + highlights).
        val luma2 = LR * rr + LG * gg + LB * bb
        val s = 1f + (MID_SAT - 1f) * (4f * luma2 * (1f - luma2)).coerceIn(0f, 1f)
        rr = luma2 + (rr - luma2) * s
        gg = luma2 + (gg - luma2) * s
        bb = luma2 + (bb - luma2) * s

        // 4. SKIN-HUE LOCK: inside the skin band, restore the input hue (keep graded S/V) so skin never shifts.
        val inChroma = max(r, max(g, b)) - min(r, min(g, b))
        val hueDist = angularDistanceDeg(hueDeg(r, g, b), SKIN_HUE_DEG)
        val bandW = (1f - hueDist / SKIN_HUE_HALFWIDTH).coerceIn(0f, 1f)
        val skinW = bandW * smoothstep(0.05f, 0.22f, inChroma) // no lock on near-neutral (noise-safe)
        if (skinW > 0f) {
            val restoredHue = lerpHueDeg(hueDeg(rr, gg, bb), hueDeg(r, g, b), skinW)
            val fixed = setHueKeepSV(rr, gg, bb, restoredHue)
            rr = fixed[0]; gg = fixed[1]; bb = fixed[2]
        }

        // Hard bound: never more than FILM_MAX_DEVIATION from identity, then clamp to displayable [0,1].
        return floatArrayOf(
            rr.coerceIn(r - FILM_MAX_DEVIATION, r + FILM_MAX_DEVIATION).coerceIn(0f, 1f),
            gg.coerceIn(g - FILM_MAX_DEVIATION, g + FILM_MAX_DEVIATION).coerceIn(0f, 1f),
            bb.coerceIn(b - FILM_MAX_DEVIATION, b + FILM_MAX_DEVIATION).coerceIn(0f, 1f)
        )
    }

    /** Monotone filmic contrast: logistic S normalized to fix (0,0)/(1,1), gently blended with identity. */
    private fun filmicContrast(x: Float): Float {
        val p = CONTRAST_PIVOT
        val a = CONTRAST_STEEPNESS
        val g0 = 1f / (1f + exp(-a * (0f - p)))
        val g1 = 1f / (1f + exp(-a * (1f - p)))
        val gx = 1f / (1f + exp(-a * (x - p)))
        val norm = (gx - g0) / (g1 - g0) // monotone, endpoints 0->0 / 1->1
        return x + (norm - x) * CONTRAST_STRENGTH
    }

    /** GLSL-style smoothstep(edge0, edge1, x); supports edge0 > edge1 (falling ramp). */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** HSV hue in degrees [0,360). Returns 0 for neutrals. */
    private fun hueDeg(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < 1e-6f) return 0f
        val h = when {
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return (h % 360f + 360f) % 360f
    }

    /** Shortest signed hue interpolation a->b by t, wrapping at 360. */
    private fun lerpHueDeg(a: Float, b: Float, t: Float): Float {
        val d = ((b - a + 540f) % 360f) - 180f
        return (a + d * t % 360f + 360f) % 360f
    }

    /** Shortest angular distance between two hues, in [0,180]. */
    private fun angularDistanceDeg(a: Float, b: Float): Float = abs(((b - a + 540f) % 360f) - 180f)

    /** Rebuild an RGB with a new hue but the same HSV saturation + value as (r,g,b). */
    private fun setHueKeepSV(r: Float, g: Float, b: Float, hueDegrees: Float): FloatArray {
        val v = max(r, max(g, b)); val mn = min(r, min(g, b))
        val s = if (v < 1e-6f) 0f else (v - mn) / v
        val c = v * s
        val hp = ((hueDegrees % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return floatArrayOf(r1 + m, g1 + m, b1 + m)
    }
}
