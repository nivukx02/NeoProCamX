package com.neoproc.neoproccamx.pipeline

/**
 * Maps the user-selected [VideoDetailProfile] to a distinct, bounded record-surface
 * sharpening value so FOCUSED / NATURAL / EXTREME actually differ on recorded pixels.
 *
 * No-downgrade contract: every selected profile retains at least the scene-adaptive [base]. FOCUSED
 * and EXTREME add progressively stronger bounded texture lift, never exceeding the active halo gate.
 * A legacy null remains the identity anchor for callers that have not selected a profile.
 *
 * Pure JVM, Android-free — unit-testable. Feeds the single record sharpening uniform
 * (GpuImageEnhancementUniforms.sharpening -> uSharpness on the offscreen-EGL record
 * shader and slot 8 on the P010 copy path).
 */
internal object VideoDetailRecordClarityPolicy {
    private const val ABSOLUTE_HALO_SAFE_CEILING = 0.94f

    fun resolveRecordSharpening(
        base: Float,
        profile: VideoDetailProfile?,
        floor: Float,
        ceiling: Float
    ): Float {
        val safeBase = base.coerceIn(0f, 1f)
        val effectiveCeiling =
            (if (ceiling > 0f) ceiling else ABSOLUTE_HALO_SAFE_CEILING).coerceIn(0f, 1f)
        val effectiveFloor = floor.coerceIn(0f, effectiveCeiling)
        if (profile == null) return safeBase
        val boundedBase = safeBase.coerceIn(effectiveFloor, effectiveCeiling)
        val tuning = EliteDetailProfilePolicy.resolve(profile)
        return (boundedBase * tuning.recordMultiplier + tuning.recordAdditiveLift)
            .coerceIn(boundedBase, effectiveCeiling)
    }
}
