package com.neoproc.neoproccamx.ai

/**
 * AF-SD (F12) — consumer gate: the AF coordinator consumes the per-frame sceneContrast only when
 * this is ON; otherwise it uses the legacy DEFAULT_SCENE_CONTRAST / 0.5f constants (bit-for-bit).
 * DEFAULT ON (user-directed activation 2026-07-03; OFF path == legacy, golden-locked).
 */
internal object AIFocusSceneContrastFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * AF-SD (F12) — producer gate: when ON, CameraViewModel fills SubjectMeteringTarget.sceneContrast
 * from the already-computed EnhancementResult sharpness/noise; when OFF it emits NEUTRAL_CONTRAST so
 * the target is byte-identical to the legacy one. DEFAULT ON (user-directed activation 2026-07-03;
 * OFF emits NEUTRAL_CONTRAST == legacy). Paired with [AIFocusSceneContrastFlag].
 */
internal object SceneContrastProducerFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * AF-SD (F12) — pure, deterministic mapper turning the already-computed EnhancementResult
 * sharpness/noise into a bounded [0,1] scene contrast (1.0 == neutral, matching the coordinator's
 * DEFAULT_SCENE_CONTRAST) and a matching scene complexity (0.5 == the legacy neutral). No Android
 * deps, no ML — fully unit-testable and reused only behind the two gates above.
 *
 * Neutrality is deliberate: a normal/mid frame (sharpness >= 0.5) maps to contrast 1.0 -> complexity
 * 0.5, so enabling the consumer gate does NOT perturb the CP-F1 anti-hunting path on ordinary frames;
 * only genuinely soft/flat frames push contrast down (and complexity up).
 */
internal object AIFocusSceneContrastPolicy {
    /** == AIAutoFocusCoordinator.DEFAULT_SCENE_CONTRAST. */
    const val NEUTRAL_CONTRAST = 1.0f

    /** == the coordinator's legacy hardcoded sceneComplexity. */
    const val NEUTRAL_COMPLEXITY = 0.5f

    /** Contrast of a fully-flat frame (sits below AIFocusSceneCutRiskPolicy.MIN_CONTRAST_FOR_RETRIGGER). */
    private const val CONTRAST_FLOOR = 0.10f

    /** Heavy grain masks edges, so it lowers effective contrast (bounded). */
    private const val NOISE_WEIGHT = 0.15f

    fun sceneContrastFromEnhancement(sharpnessScore: Float, estimatedNoiseLevel: Float): Float {
        val s = sharpnessScore.coerceIn(0f, 1f)
        // Sharp (>=0.5, the enhancement neutral/bypass) => neutral 1.0; softer => linearly toward the floor.
        val base = if (s >= 0.5f) NEUTRAL_CONTRAST else CONTRAST_FLOOR + (s / 0.5f) * (NEUTRAL_CONTRAST - CONTRAST_FLOOR)
        val noisePenalty = estimatedNoiseLevel.coerceIn(0f, 1f) * NOISE_WEIGHT
        return (base - noisePenalty).coerceIn(0f, 1f)
    }

    fun sceneComplexityFromContrast(sceneContrast: Float): Float =
        (NEUTRAL_COMPLEXITY + (1f - sceneContrast.coerceIn(0f, 1f)) * (1f - NEUTRAL_COMPLEXITY)).coerceIn(0f, 1f)
}
