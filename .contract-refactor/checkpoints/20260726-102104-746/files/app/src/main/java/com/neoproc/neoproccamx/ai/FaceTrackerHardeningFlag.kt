package com.neoproc.neoproccamx.ai

/**
 * s7-f02 / CP93 — process-global gate for FaceTracker identity-lock hysteresis.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, FaceTracker.assignTrackingIds passes a zero identity bias to
 * the existing [AIDetectionTrackingPolicy.matchTrackingId] association, so track-ID assignment is
 * bit-for-bit today's behavior. When flipped on (device-gated), the most-established face track gets
 * a small stickiness bonus so a brief occlusion / competing detection does not churn its track ID.
 *
 * This EXTENDS the existing IoU/centroid/age association via its candidateScoreAdjuster hook; it does
 * NOT stand up a competing association, and it does NOT touch FaceEyeFocusTargetPolicy (s5-f03 owns
 * the eye/face focus scorer).
 */
internal object FaceTrackerHardeningFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
