package com.neoproc.neoproccamx.recording

/**
 * s4-f03 / CP34 — bounded scene-adaptive local-contrast policy for the shadow-lift pass.
 *
 * Maps live HDR histogram statistics to the `shadow_lift.comp` push parameters
 * (shadowLift / contrastRecovery / luminanceThreshold) so a low-key scene lifts shadows more than a
 * high-key scene — replacing the frozen 0.03 / 1.10 / 0.40 constants. Every output is clamped to the
 * shader's proven-safe ranges, so the policy can NEVER crush shadows or invert contrast.
 *
 * No-downgrade: this is a dormant foundation. The live shadow-lift dispatch still uses the constants;
 * wiring this policy's output into the push is a device-gated owner step (it changes live pixels).
 * [NEUTRAL] equals the shader's current constants, so enabling the policy at a neutral scene is a
 * no-op.
 */
object SceneAdaptiveContrastPolicy {

    data class ShadowContrast(
        val shadowLift: Float,
        val contrastRecovery: Float,
        val luminanceThreshold: Float
    )

    /** The shader's current frozen constants — the no-downgrade default. */
    val NEUTRAL = ShadowContrast(shadowLift = 0.03f, contrastRecovery = 1.10f, luminanceThreshold = 0.40f)

    // The shadow_lift.comp self-clamp ranges (the contract these outputs must respect).
    private const val LIFT_MIN = 0.01f
    private const val LIFT_MAX = 0.10f
    private const val CONTRAST_MIN = 1.05f
    private const val CONTRAST_MAX = 1.15f
    private const val THRESHOLD_MIN = 0.05f
    private const val THRESHOLD_MAX = 0.40f

    /**
     * @param meanLuma scene mean luma (working linear; clamped internally)
     * @param shadowRatio fraction of the frame in shadow (0..1)
     */
    fun resolve(meanLuma: Float, shadowRatio: Float): ShadowContrast {
        val sr = shadowRatio.coerceIn(0f, 1f)
        val ml = meanLuma.coerceIn(0f, 1f) // degenerate/over-range stats fold into the safe window
        // Low-key (dark, lots of shadow) → more lift; high-key → less.
        val lift = (0.03f + 0.06f * sr).coerceIn(LIFT_MIN, LIFT_MAX)
        // Darker mean → a touch more midtone contrast recovery, never beyond the safe band.
        val contrast = (1.10f + 0.10f * (0.5f - ml)).coerceIn(CONTRAST_MIN, CONTRAST_MAX)
        // More shadow → lift a larger slice of the tonal range (higher threshold).
        val threshold = (0.10f + 0.30f * sr).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        return ShadowContrast(lift, contrast, threshold)
    }
}
