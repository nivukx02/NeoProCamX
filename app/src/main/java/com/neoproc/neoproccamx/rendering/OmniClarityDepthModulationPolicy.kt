package com.neoproc.neoproccamx.rendering

import kotlin.math.abs

/**
 * OmniClarity Engine — §3 Parallax / Oracle / Helix depth- & segmentation-driven modulation.
 *
 * Per the design, depth + semantic masks modulate later processing rather than running a separate
 * pipeline: the foreground subject gets full nano-detail/sharpening while the background gets reduced
 * processing, the temporal flicker-guard blends per depth layer, and an optional depth-dependent
 * defocus reuses the existing filtering machinery. Depth comes from the existing geometric/AI depth
 * source (kept as-is per the no-new-vendor decision); this policy only turns availability + confidence
 * into modulation strengths that feed the live shader's subject-gated detail machinery. No pixels here.
 *
 * Confidence-aware: as depth/segmentation confidence falls, the foreground/background gains converge so
 * an unreliable mask can never carve a visible halo into the image (it degrades to uniform processing).
 */
internal data class DepthModulationParams(
    val foregroundDetailGain: Float,
    val backgroundDetailGain: Float,
    val flickerGuardStrength: Float,
    val defocusStrength: Float,
    val depthMaskValid: Boolean,
    val segmentationMaskValid: Boolean
) {
    /** Push-constant / uniform layout for the subject-gated modulation in the live shader. */
    fun toPushConstants(): FloatArray = floatArrayOf(
        foregroundDetailGain,
        backgroundDetailGain,
        flickerGuardStrength,
        defocusStrength,
        if (depthMaskValid) 1f else 0f,
        if (segmentationMaskValid) 1f else 0f
    )

    /** Difference in detail between foreground and background, after confidence convergence. */
    val foregroundBackgroundDelta: Float get() = abs(foregroundDetailGain - backgroundDetailGain)
}

internal data class DepthModulationPlan(
    val active: Boolean,
    val params: DepthModulationParams,
    val reason: String
)

internal object OmniClarityDepthModulationPolicy {
    private const val FG_GAIN_NORMAL = 0.85f
    private const val FG_GAIN_STRICT = 1.0f
    private const val BACKGROUND_RATIO = 0.5f      // background gets ~half the foreground detail
    private const val FLICKER_GUARD = 0.5f
    private const val DEFOCUS_NORMAL = 0.6f
    private const val DEFOCUS_STRICT = 0.8f

    fun resolve(
        depthAvailable: Boolean,
        segmentationAvailable: Boolean,
        depthConfidence: Float,
        highEndStrict: Boolean,
        defocusRequested: Boolean
    ): DepthModulationPlan {
        if (!depthAvailable && !segmentationAvailable) {
            val uniformGain = if (highEndStrict) FG_GAIN_STRICT else FG_GAIN_NORMAL
            return DepthModulationPlan(
                active = false,
                params = DepthModulationParams(
                    foregroundDetailGain = uniformGain,
                    backgroundDetailGain = uniformGain, // no mask -> uniform processing
                    flickerGuardStrength = 0f,
                    defocusStrength = 0f,
                    depthMaskValid = false,
                    segmentationMaskValid = false
                ),
                reason = "NO_DEPTH_OR_SEGMENTATION"
            )
        }

        val confidence = depthConfidence.coerceIn(0f, 1f)
        val foreground = if (highEndStrict) FG_GAIN_STRICT else FG_GAIN_NORMAL
        val fullBackground = foreground * BACKGROUND_RATIO
        // Converge background toward foreground as confidence drops (no halos from a bad mask).
        val background = fullBackground + (foreground - fullBackground) * (1f - confidence)
        // Defocus needs real geometric depth (segmentation alone can't drive a depth-of-field blur).
        val defocus = if (defocusRequested && depthAvailable) {
            (if (highEndStrict) DEFOCUS_STRICT else DEFOCUS_NORMAL) * confidence
        } else {
            0f
        }

        return DepthModulationPlan(
            active = true,
            params = DepthModulationParams(
                foregroundDetailGain = foreground,
                backgroundDetailGain = background,
                flickerGuardStrength = FLICKER_GUARD * confidence,
                defocusStrength = defocus,
                depthMaskValid = depthAvailable,
                segmentationMaskValid = segmentationAvailable
            ),
            reason = if (depthAvailable) "DEPTH_MODULATED" else "SEGMENTATION_MODULATED"
        )
    }
}
