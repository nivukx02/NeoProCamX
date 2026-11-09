package com.neoproc.neoproccamx.ai

/**
 * AF-SD (F08) — process-global gate for animal/pet head-eye autofocus.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, FocusEngine focuses PET/ANIMAL TrackedObjects at their
 * predicted bbox centre exactly as the legacy path (bit-for-bit). When flipped on (device-side by
 * the owner, after validating on real animals), the PET/ANIMAL object candidate's focus point is
 * rerouted through [AnimalEyeFocusTargetPolicy] to a head/eye-region prior so pet portraits lock on
 * the eye/head instead of the torso. It reuses only the existing tracked bbox + motion — no new
 * model. Selection ranking is deliberately untouched (the candidate keeps its OBJECT-tier score),
 * so an animal can never steal primary from a human face/eye. No persistence; resets to OFF on
 * process start. Mirrors [PoseHandoffFlag] / [ObjectTrackerContinuityFlag].
 */
internal object AnimalEyeAfFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
