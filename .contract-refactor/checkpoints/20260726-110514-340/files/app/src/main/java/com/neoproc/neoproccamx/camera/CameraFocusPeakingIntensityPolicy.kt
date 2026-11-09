package com.neoproc.neoproccamx.camera

// ════════════════════════════════════════════════════════════════════════════════════════
// FOCUS PEAKING INTENSITY ROUTING POLICY (s5-f09 / CP-F10)
// ════════════════════════════════════════════════════════════════════════════════════════
// Pure mapping that turns the s5-f07 focus-quality primitive
// (CameraFocusControl.calculateFocusPeakingIntensity, edge-sharpness -> 0..1)
// into the BOUNDED live peaking strength parameter consumed by the preview /
// pipeline peaking mask.
//
// NO-DOWNGRADE CONTRACT: this policy is DORMANT — it has no production caller in
// this feature. resolveOrDefault(null) returns DEFAULT_PEAKING_STRENGTH (today's
// 0.55f), so wiring it in device-side reproduces today's output bit-for-bit until
// a real per-frame focus measurement is supplied.
// ════════════════════════════════════════════════════════════════════════════════════════
internal object CameraFocusPeakingIntensityPolicy {

    /**
     * No-downgrade default live peaking strength. Mirrors
     * PreferencesManager.DEFAULT_FOCUS_PEAKING_INTENSITY (0.55f) so that, until a
     * real focus measurement is wired in device-side, the live peaking overlay is
     * unchanged.
     */
    const val DEFAULT_PEAKING_STRENGTH = 0.55f

    /** Lowest live peaking strength a real measurement may map to (stays visible). */
    const val MIN_PEAKING_STRENGTH = 0.20f

    /** Highest live peaking strength (matches the live setPeakingIntensity ceiling). */
    const val MAX_PEAKING_STRENGTH = 1.0f

    /**
     * Map a measured focus-quality (from CameraFocusControl.calculateFocusPeakingIntensity)
     * into the bounded live peaking strength.
     *
     * @param laplacianVariance edge-sharpness energy of the current focus region.
     * @param focusThreshold the focus-quality threshold (defaults to the s5-f07 default).
     * @return a strength in [MIN_PEAKING_STRENGTH, MAX_PEAKING_STRENGTH].
     */
    fun resolvePeakingStrength(
        laplacianVariance: Float,
        focusThreshold: Float = 100f
    ): Float {
        val quality = CameraFocusControl.calculateFocusPeakingIntensity(
            laplacianVariance = laplacianVariance,
            threshold = focusThreshold
        )
        return quality.coerceIn(MIN_PEAKING_STRENGTH, MAX_PEAKING_STRENGTH)
    }

    /**
     * Bounded strength when a measurement is available, else the no-downgrade default.
     * A null/blank measurement reproduces today's behavior exactly.
     */
    fun resolveOrDefault(
        laplacianVariance: Float?,
        focusThreshold: Float = 100f
    ): Float {
        val variance = laplacianVariance ?: return DEFAULT_PEAKING_STRENGTH
        if (variance.isNaN() || variance.isInfinite()) return DEFAULT_PEAKING_STRENGTH
        return resolvePeakingStrength(
            laplacianVariance = variance,
            focusThreshold = focusThreshold
        )
    }
}
