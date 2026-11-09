package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.rendering.BokehFusionParams
import kotlin.math.abs

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * CP-QV4 (s9-f04) — CINEMATIC DEPTH-BOKEH FOCUS POLICY
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Purely-functional, Android-free FUSION of the two matte producers the bokeh pass already has —
 * the MiDaS depth field ([DepthEstimator]) and the person matte ([SegmentationEngine]) — into a
 * subject-aware focus plane and a subject-protecting circle-of-confusion (CoC) map.
 *
 * The bokeh shader (bokeh_vogel_spiral.comp), its push-constant ABI ([BokehFusionParams]) and the
 * per-pixel CoC math ([DepthEstimator.circleOfConfusionRadiusPixels]) all EXIST and are dispatched
 * today, but only around the FIXED default focus plane (normalized-inverse-depth 0.5, the value
 * [DepthEstimator.decodeOutputWithEnhancements] hard-codes for its CoC map). The gap this policy
 * fills: when a subject matte is available, put the focus plane ON THE SUBJECT and drive the CoC to
 * ~0 wherever the matte says "subject" (so the subject stays razor sharp) while letting the CoC grow
 * with depth distance everywhere else.
 *
 * NO-DOWNGRADE CONTRACT: [enabled] defaults OFF.
 *  - With it OFF, [resolveBokehParams] returns [BokehFusionParams.passthroughDefault] (blendStrength
 *    0 == exact passthrough, today's pixels bit-for-bit).
 *  - [buildFocusMatteCoc] is the honest superset of today's CoC: fed a focus plane of 0.5 and a matte
 *    that never crosses [subjectThreshold] (e.g. an all-zero matte), every pixel is the raw
 *    [DepthEstimator.circleOfConfusionRadiusPixels] value around 0.5 — i.e. identical to the live
 *    map. The subject bias only changes pixels the matte actually claims as subject.
 *  - [segmentationBiasedFocusDepth] falls back to the same fixed 0.5 default whenever the matte
 *    carries no localized subject signal (empty / uniform), so a matte-less frame focuses exactly
 *    as today.
 *
 * The subject-aware look is only VISIBLE once the owner raises blendStrength device-side
 * (DEVICE-GATED, the s1-f06 rule). This object computes no pixels; it hands the focus plane and CoC
 * map to the existing native dispatch.
 */
internal object CinematicDepthFocusPolicy {

    /** Master gate: OFF == today's fixed-plane bokeh (bit-for-bit passthrough). */
    const val ENABLED_DEFAULT = false

    /**
     * The fixed focus plane (normalized inverse depth) the live [DepthEstimator] path builds its CoC
     * map around today (see the `normalizedInverseDepthToMeters(0.5f)` calls in
     * decodeOutputWithEnhancements / fallbackDepth). The matte-less / uniform-matte fallback of
     * [segmentationBiasedFocusDepth] returns exactly this, so a frame with no subject focuses as today.
     */
    const val DEFAULT_FOCUS_DEPTH = 0.5f

    /**
     * Matte value above which a pixel is treated as SUBJECT (kept sharp). Segmentation confidence /
     * matte values are in [0, 1]; the mid-point splits confident subject from confident background.
     * ENGINEERING PLACEHOLDER — only consulted through [buildFocusMatteCoc]'s subject suppression.
     */
    const val SUBJECT_MATTE_THRESHOLD = 0.5f

    /**
     * Weight floor below which the accumulated matte weight is treated as "no subject signal"
     * (empty / all-zero matte) and [segmentationBiasedFocusDepth] falls back to [DEFAULT_FOCUS_DEPTH].
     */
    private const val MATTE_WEIGHT_EPSILON = 1.0e-4f

    /**
     * Matte-variance floor below which the matte is treated as UNIFORM (no localized subject to bias
     * toward) and [segmentationBiasedFocusDepth] falls back to [DEFAULT_FOCUS_DEPTH].
     */
    private const val MATTE_VARIANCE_EPSILON = 1.0e-6f

    /**
     * Device-side master flag. Owner flips true after validating that the subject stays sharp and the
     * background CoC matches the live depth-only bokeh outside the matte.
     */
    @JvmField
    var enabled = ENABLED_DEFAULT

    /**
     * (a) SUBJECT-BIASED FOCUS PLANE. PURE.
     *
     * Matte-weighted mean of the normalized(-inverse) depth = the depth plane of the subject region,
     * i.e. where to focus. When the matte carries no localized subject signal — empty buffer, total
     * weight ≤ [MATTE_WEIGHT_EPSILON], or a UNIFORM matte (zero variance, including all-ones) — there
     * is no subject to bias toward, so this returns [DEFAULT_FOCUS_DEPTH] (today's fixed plane) within
     * 1e-4.
     *
     * @param depth  normalized-inverse-depth field, row-major [width]×[height], values in [0, 1].
     * @param matte  subject matte / confidence, same layout, values in [0, 1].
     */
    fun segmentationBiasedFocusDepth(
        depth: FloatArray,
        matte: FloatArray,
        width: Int,
        height: Int
    ): Float {
        val count = minOf(depth.size, matte.size, if (width > 0 && height > 0) width * height else Int.MAX_VALUE)
        if (count <= 0) return DEFAULT_FOCUS_DEPTH

        var weightSum = 0f
        var weightedDepth = 0f
        var matteSum = 0f
        var matteSqSum = 0f
        for (index in 0 until count) {
            val d = depth[index]
            val m = matte[index]
            if (!d.isFinite() || !m.isFinite()) continue
            val w = m.coerceIn(0f, 1f)
            val dv = d.coerceIn(0f, 1f)
            weightSum += w
            weightedDepth += w * dv
            matteSum += w
            matteSqSum += w * w
        }

        // No subject signal at all (empty / all-zero matte): keep today's fixed plane.
        if (weightSum <= MATTE_WEIGHT_EPSILON) return DEFAULT_FOCUS_DEPTH

        // Uniform matte (zero variance — every pixel equally weighted, including all-ones): there is
        // no localized subject to bias toward, so fall back to the fixed plane.
        val n = count.toFloat()
        val matteMean = matteSum / n
        val matteVariance = (matteSqSum / n - matteMean * matteMean).coerceAtLeast(0f)
        if (matteVariance <= MATTE_VARIANCE_EPSILON) return DEFAULT_FOCUS_DEPTH

        return (weightedDepth / weightSum).coerceIn(0f, 1f)
    }

    /**
     * (b) SUBJECT-PROTECTING CIRCLE-OF-CONFUSION MAP. PURE.
     *
     * Per-pixel CoC (in pixels) that REUSES [DepthEstimator.circleOfConfusionRadiusPixels] verbatim so
     * the physical defocus model is unchanged, then drives the CoC toward ~0 wherever the matte marks
     * the SUBJECT (matte > [subjectThreshold]) — keeping the subject sharp — while leaving the raw,
     * depth-distance-driven CoC everywhere else.
     *
     * The subject suppression scales linearly from full CoC at the threshold to 0 CoC at matte==1, so
     * the transition is smooth (no hard matte seam). Feed a matte that never crosses [subjectThreshold]
     * (e.g. all-zero) and a focus plane of [DEFAULT_FOCUS_DEPTH] to reproduce the live depth-only CoC
     * map bit-for-bit.
     *
     * @param depth          normalized-inverse-depth field, row-major, values in [0, 1].
     * @param matte          subject matte, same layout, values in [0, 1].
     * @param focusDepth     focus plane (normalized inverse depth), e.g. from [segmentationBiasedFocusDepth].
     * @param viewportWidth  output width in pixels (drives the CoC's pixel scale).
     * @param subjectThreshold matte value above which a pixel is protected (kept sharp).
     */
    fun buildFocusMatteCoc(
        depth: FloatArray,
        matte: FloatArray,
        focusDepth: Float,
        viewportWidth: Int,
        subjectThreshold: Float = SUBJECT_MATTE_THRESHOLD
    ): FloatArray {
        if (depth.isEmpty()) return FloatArray(0)
        val safeWidth = viewportWidth.coerceAtLeast(1)
        val safeFocus = focusDepth.coerceIn(0f, 1f)
        val focusMeters = DepthEstimator.normalizedInverseDepthToMeters(safeFocus)
        val threshold = subjectThreshold.coerceIn(0f, 1f)
        // Above the threshold the protection ramps to full at matte==1; guard the divisor.
        val protectionRange = (1f - threshold).coerceAtLeast(MATTE_WEIGHT_EPSILON)

        val coc = FloatArray(depth.size)
        for (index in depth.indices) {
            val d = depth[index]
            val depthValue = if (d.isFinite()) d.coerceIn(0f, 1f) else safeFocus
            val rawCoc = DepthEstimator.circleOfConfusionRadiusPixels(
                normalizedInverseDepth = depthValue,
                focusDistanceMeters = focusMeters,
                viewportWidthPixels = safeWidth
            )
            val m = matte.getOrElse(index) { 0f }
            val matteValue = if (m.isFinite()) m.coerceIn(0f, 1f) else 0f
            // Subject suppression: 0 below threshold (raw CoC kept), ramping to 1 (CoC -> 0) at matte==1.
            val suppression = ((matteValue - threshold) / protectionRange).coerceIn(0f, 1f)
            coc[index] = (rawCoc * (1f - suppression)).coerceAtLeast(0f)
        }
        return coc
    }

    /**
     * (c) PASSTHROUGH-WHEN-OFF bokeh parameters. PURE.
     *
     * While [enabled] is OFF this returns [BokehFusionParams.passthroughDefault] (blendStrength 0 ==
     * exact passthrough), so the native dispatch reproduces today's pixels bit-for-bit. When the owner
     * flips the gate on device-side, the same passthrough is still returned here (blendStrength stays a
     * DEVICE-GATED lever raised elsewhere) but the focus plane carried in the params is the
     * subject-biased one — never a fabricated visible-bokeh claim.
     *
     * @param width       output width.
     * @param height      output height.
     * @param focusDepth  focus plane to carry, e.g. from [segmentationBiasedFocusDepth].
     */
    fun resolveBokehParams(
        width: Int,
        height: Int,
        focusDepth: Float = DEFAULT_FOCUS_DEPTH
    ): BokehFusionParams {
        val plane = if (enabled) focusDepth.coerceIn(0f, 1f) else DEFAULT_FOCUS_DEPTH
        return BokehFusionParams.passthroughDefault(width = width, height = height, focusDepth = plane)
    }
}
