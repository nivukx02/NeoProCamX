package com.neoproc.neoproccamx.ai

import kotlin.math.sqrt

/**
 * s7-f06 / CP96 — pure track-continuity helpers for [ObjectTracker].
 *
 * The live association in [ObjectTracker] matches detections to tracks by IoU. Under fast motion
 * across strided detections two boxes of the same object can have ZERO overlap (IoU fails) even
 * though their centroids are close — spawning a new track ID (churn). This policy provides the
 * pure decision for a CENTROID BRIDGE that recovers the match when IoU fails, plus the honest
 * classification of the classical fallback. Gated behind [ObjectTrackerContinuityFlag]; never
 * consulted while the flag is OFF.
 */
internal object ObjectTrackContinuityPolicy {

    /** Max normalized centroid distance to bridge an IoU-failed match (same class). */
    const val CENTROID_BRIDGE_RADIUS = 0.12f
    const val CENTROID_BRIDGE_RADIUS_STRICT = 0.10f

    fun bridgeRadius(strictHighEnd: Boolean): Float =
        if (strictHighEnd) CENTROID_BRIDGE_RADIUS_STRICT else CENTROID_BRIDGE_RADIUS

    /** Euclidean distance between two normalized centroids. PURE. */
    fun centroidDistance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    /** Whether two same-class boxes are close enough to bridge an IoU-failed match. PURE. */
    fun isCentroidBridge(distance: Float, radius: Float): Boolean = distance <= radius

    /** Honest classification: is this a classical-fallback object (not a real model detection)? */
    fun isClassicalFallback(className: String): Boolean =
        className == "chroma_fallback" || className == "salient_fallback"
}
