package com.neoproc.neoproccamx.ai

/**
 * s7-f07 / CP97 — process-global gate for SceneDetector N-frame change debounce.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, SceneDetector.applyTemporalSmoothing keeps its existing
 * confidence-margin hysteresis verbatim (a single confident frame can switch) — bit-for-bit today.
 * When flipped on (device-gated), a confident new scene must persist for N consecutive frames before
 * it is committed, so the scene hint cannot flip-flop and pump exposure/tone.
 */
internal object SceneHysteresisFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
