package com.neoproc.neoproccamx.rendering

/**
 * Result of [AiEnhancementRecordBoostPolicy.resolve].
 */
internal data class AiEnhancementRecordBoost(
    val boostedSharpening: Float,
    val boostedSpatialDenoise: Float,
    val sharpenDelta: Float,
    val denoiseDelta: Float,
    val active: Boolean
)

/**
 * Bounded, additive record-surface enhancement driven by the AI Enhancement toggle and
 * the measured [com.neoproc.neoproccamx.ai.EnhancementResult] (perceptual-sharpness
 * deficit + estimated noise). The measured enhancement bitmap is otherwise discarded;
 * this routes its *measurement* into the live record sharpen/denoise uniforms.
 *
 * No-downgrade contract:
 *  - enabled=false returns the base values unchanged (byte-identical, OFF == today).
 *  - Deltas are small, measurement-gated, monotonic-up (can only add sharpen/denoise),
 *    clamped to the existing sharpening ceiling, and NEVER touch chromaDenoise (slot 6)
 *    to avoid the known B&W chroma-deadband regression.
 *
 * Pure JVM, Android-free — unit-testable.
 */
internal object AiEnhancementRecordBoostPolicy {
    private const val SHARP_TARGET = 0.62f
    private const val MAX_SHARPEN_DELTA = 0.10f
    private const val MAX_DENOISE_DELTA = 0.12f

    fun resolve(
        enabled: Boolean,
        measuredPerceptualSharpness: Float,
        measuredNoiseLevel: Float,
        baseSharpening: Float,
        baseSpatialDenoise: Float,
        sharpeningCeiling: Float,
        skinDetailProtection: Float
    ): AiEnhancementRecordBoost {
        val safeSharpBase = baseSharpening.coerceIn(0f, 1f)
        val safeDenoiseBase = baseSpatialDenoise.coerceIn(0f, 1f)
        if (!enabled) {
            return AiEnhancementRecordBoost(safeSharpBase, safeDenoiseBase, 0f, 0f, false)
        }
        val noise = measuredNoiseLevel.coerceIn(0f, 1f)
        val sharpness = measuredPerceptualSharpness.coerceIn(0f, 1f)
        val skinAttenuation = 1f - skinDetailProtection.coerceIn(0f, 1f) * 0.5f
        val denoiseDelta = (noise * 0.15f).coerceIn(0f, MAX_DENOISE_DELTA)
        val sharpenDelta = ((SHARP_TARGET - sharpness).coerceAtLeast(0f) * 0.30f)
            .coerceIn(0f, MAX_SHARPEN_DELTA) * skinAttenuation
        val ceiling = if (sharpeningCeiling > 0f) sharpeningCeiling else 1f
        val boostedSharpening = (safeSharpBase + sharpenDelta).coerceIn(safeSharpBase, ceiling)
        val boostedSpatialDenoise = (safeDenoiseBase + denoiseDelta).coerceIn(safeDenoiseBase, 1f)
        return AiEnhancementRecordBoost(
            boostedSharpening = boostedSharpening,
            boostedSpatialDenoise = boostedSpatialDenoise,
            sharpenDelta = boostedSharpening - safeSharpBase,
            denoiseDelta = boostedSpatialDenoise - safeDenoiseBase,
            active = boostedSharpening > safeSharpBase || boostedSpatialDenoise > safeDenoiseBase
        )
    }
}
