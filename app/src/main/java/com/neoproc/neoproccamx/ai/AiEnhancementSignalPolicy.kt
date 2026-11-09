package com.neoproc.neoproccamx.ai

/** s7-f09/CP99 — which denoise path actually produced the pixels this frame (public for metrics). */
enum class DenoisePath { NONE, CLASSICAL_BILATERAL, GUIDED_FALLBACK, NEURAL }

/**
 * s7-f09 / CP99 — pure, signal-dependent enhancement helpers for [EnhancementEngine].
 *
 *  - HONESTY: classify which denoise path actually ran (neural hook vs classical fallback vs none)
 *    so [EnhancementEngine.EnhancementMetrics] can report the truth instead of an unqualified flag.
 *  - SIGNAL-ADAPTIVE SHARPEN: scale the unsharp / micro-contrast strength DOWN as the measured
 *    noise floor rises, so noise is not amplified into fake detail (clean image => full sharpen).
 *  - OVERSHOOT CLAMP: a hard ceiling on the unsharp luma overshoot to bound halos on hard edges.
 *
 * All pure; no Android types; fully JVM-testable. Consumed only on the gated path
 * ([EnhancementSignalAdaptiveFlag]) for the pixel-changing parts; the honesty classification is
 * metadata-only (no pixel effect) and always reflects reality.
 */
internal object AiEnhancementSignalPolicy {

    /** How hard sharpen backs off per unit noise floor. */
    const val SHARPEN_NOISE_BACKOFF = 1.4f
    /** Lower bound on the sharpen scale so a very noisy image still gets a little sharpening. */
    const val MIN_SHARPEN_SCALE = 0.25f
    const val MIN_SHARPEN_SCALE_STRICT = 0.20f

    /** Hard ceiling (in 0..255 luma codes) on the unsharp overshoot, to bound edge halos. */
    const val MAX_UNSHARP_OVERSHOOT = 24f
    const val MAX_UNSHARP_OVERSHOOT_STRICT = 18f

    /**
     * Honest classification of the denoise path. Mirrors EnhancementEngine's denoise control flow:
     * a thrown neural call (no result) fell through to the classical bilateral filter.
     */
    fun denoisePath(
        denoiseApplied: Boolean,
        neuralResultPresent: Boolean,
        usedNeuralModel: Boolean,
        usedGuidedFallback: Boolean
    ): DenoisePath {
        if (!denoiseApplied) return DenoisePath.NONE
        if (!neuralResultPresent) return DenoisePath.CLASSICAL_BILATERAL
        if (usedNeuralModel) return DenoisePath.NEURAL
        if (usedGuidedFallback) return DenoisePath.GUIDED_FALLBACK
        return DenoisePath.CLASSICAL_BILATERAL
    }

    /**
     * Sharpen/micro-contrast multiplier in [floor, 1] that decreases as [normalizedNoiseFloor] rises.
     * Monotonic non-increasing: clean (noise=0) => 1.0 (full sharpen); noisy => floor.
     */
    fun signalAdaptiveSharpenScale(normalizedNoiseFloor: Float, strictHighEnd: Boolean): Float {
        val noise = normalizedNoiseFloor.coerceIn(0f, 1f)
        val floor = if (strictHighEnd) MIN_SHARPEN_SCALE_STRICT else MIN_SHARPEN_SCALE
        return (1f - noise * SHARPEN_NOISE_BACKOFF).coerceIn(floor, 1f)
    }

    /** Hard-clamp an unsharp luma overshoot to ±[ceiling] (bounds halos). */
    fun clampUnsharpOvershoot(deltaLuma: Float, ceiling: Float): Float =
        deltaLuma.coerceIn(-ceiling, ceiling)
}
