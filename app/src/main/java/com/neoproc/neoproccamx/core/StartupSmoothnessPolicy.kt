package com.neoproc.neoproccamx.core

internal object StartupSmoothnessPolicy {
    private const val FEATURE_GATE_TIMEOUT_MS = 250L // Slashed from 900L
    // Submit centre AF with the first repeating request; no artificial timer latency.
    private const val AUTO_PRIME_DELAY_MS = 0L
    private const val NATIVE_PREVIEW_RELEASE_DELAY_MS = 100L // Slashed from 300L
    private const val AI_RAMP_DELAY_MS = 150L       // Slashed from 450L
    private const val AI_RAMP_DELAY_STRICT_HIGH_END_MS = 80L // Slashed from 300L
    // Heavy model initialization starts after the preview has had time to settle. Loading pose,
    // segmentation, and depth models immediately after first frame causes GC/GPU contention and visible jank.
    private const val AI_WARMUP_DELAY_MS = 1_500L
    private const val AI_WARMUP_STAGE_DELAY_MS = 250L

    fun featureGateTimeoutMs(): Long = FEATURE_GATE_TIMEOUT_MS

    fun autoPrimeDelayMs(): Long = AUTO_PRIME_DELAY_MS

    fun nativePreviewReleaseDelayMs(): Long = NATIVE_PREVIEW_RELEASE_DELAY_MS

    fun aiRampDelayMs(strictHighEnd: Boolean): Long =
        if (strictHighEnd) {
            AI_RAMP_DELAY_STRICT_HIGH_END_MS
        } else {
            AI_RAMP_DELAY_MS
        }

    fun aiWarmupDelayMs(): Long = AI_WARMUP_DELAY_MS

    fun aiWarmupStageDelayMs(): Long = AI_WARMUP_STAGE_DELAY_MS
}
