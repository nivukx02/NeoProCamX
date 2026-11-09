package com.neoproc.neoproccamx.ai

/**
 * AF-SD (F11) — process-global gate for the DOCUMENT/whiteboard foreground-plane focus lock.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, FocusEngine emits NO document target and never touches its
 * stability counter, so output is bit-for-bit the legacy path. When flipped on (device-gated), a
 * SceneType.DOCUMENT that has been stable for [STABILITY_FRAMES] frames AND has no higher-priority
 * face/pose/object subject yields a centered/foreground-plane OBJECT target that biases AF/AE to the
 * page instead of hunting. It NEVER overrides a genuine subject (the branch requires no real
 * candidate won). No persistence; resets to OFF on process start.
 *
 * NOTE: the live pipeline only calls FocusEngine.selectSubjectAwareness when a tracker is enabled
 * (faceTrackingEnabled || poseTrackingEnabled || objects present); with all trackers off the lock
 * cannot engage. This is acceptable for v1 (face tracking is on by default on real devices).
 */
internal object DocumentFocusLockFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    /** Consecutive DOCUMENT frames required before the lock engages (debounce). */
    const val STABILITY_FRAMES: Int = 6

    /** Minimum segmentation foregroundCoverage to trust the segmentation centroid over the centered fallback. */
    const val MIN_SEG_COVERAGE: Float = 0.12f
}
