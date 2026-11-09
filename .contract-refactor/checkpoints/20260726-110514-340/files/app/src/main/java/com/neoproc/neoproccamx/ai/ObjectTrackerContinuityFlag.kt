package com.neoproc.neoproccamx.ai

/**
 * s7-f06 / CP96 — process-global gate for ObjectTracker centroid-bridge continuity.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, ObjectTracker.associateDetections matches purely by IoU
 * exactly as today (bit-for-bit). When flipped on (device-gated), an IoU-failed match falls back to
 * a same-class centroid bridge so a fast-moving object keeps its track ID across strided detections.
 */
internal object ObjectTrackerContinuityFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
