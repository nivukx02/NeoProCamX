package com.neoproc.neoproccamx.pipeline

/**
 * Maps the user-selected [VideoDetailProfile] to a distinct, bounded record-surface
 * sharpening value so FOCUSED / NATURAL / EXTREME actually differ on recorded pixels.
 *
 * No-downgrade contract:
 *  - FOCUSED (and legacy null) is the IDENTITY anchor: returns [base] unchanged, so the
 *    shipped default recorded pixels are byte-identical to today.
 *  - NATURAL is pulled softer but never below the strict [floor] and never sharper than
 *    the default [base].
 *  - EXTREME is pushed crisper but never softer than [base] and never above the
 *    halo/quality-gate [ceiling] (falling back to an absolute halo-safe cap when the
 *    plan reports no ceiling).
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
        return when (profile) {
            VideoDetailProfile.NATURAL_DETAIL ->
                (safeBase * 0.75f).coerceIn(effectiveFloor, safeBase)
            VideoDetailProfile.EXTREME_DETAIL ->
                if (effectiveCeiling > safeBase) {
                    (safeBase * 1.15f + 0.05f).coerceIn(safeBase, effectiveCeiling)
                } else {
                    safeBase
                }
            VideoDetailProfile.FOCUSED_DETAIL, null -> safeBase
        }
    }
}
