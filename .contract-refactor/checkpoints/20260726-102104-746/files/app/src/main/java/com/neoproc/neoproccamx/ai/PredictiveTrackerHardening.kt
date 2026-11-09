package com.neoproc.neoproccamx.ai

import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PREDICTIVE TRACKER HARDENING (s7-f10 / CP9A) — pure scalar math, RectF-free
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Innovation gating (Mahalanobis/chi-square) + bounded coast-through-occlusion with
 * monotonic confidence decay for [PredictiveSubjectTracker]. Kept as plain Floats so
 * it is fully exercisable in JVM unit tests (the project's mockable android.jar makes
 * RectF.centerX()/width() return 0 — see FocusEngineSubjectHardeningGoldenTest).
 *
 * NO-DOWNGRADE: this object is only consulted when
 * [PredictiveSubjectTracker.hardenedTrackingEnabled] is true. The default reproduces
 * the legacy fixed-threshold behaviour bit-for-bit.
 */
internal object PredictiveTrackerHardening {

    /** Default chi-square-style gate radius (matches TrackingMath.isInlier base threshold). */
    const val DEFAULT_MAHALANOBIS_THRESHOLD = 3.0f
    const val DEFAULT_MAHALANOBIS_THRESHOLD_STRICT = 3.2f

    /** Per-frame confidence multiplier while coasting (no measurement). */
    const val COAST_CONFIDENCE_DECAY = 0.82f
    const val COAST_CONFIDENCE_DECAY_STRICT = 0.86f

    /** Max frames the filter may coast before the track is dropped. */
    const val MAX_COAST_FRAMES = 8
    const val MAX_COAST_FRAMES_STRICT = 10

    /**
     * Mahalanobis distance of a measurement from the predicted position, using the
     * diagonal position covariance. Covariance is floored at 0.001 (matching
     * [TrackingMath.isInlier]) so a degenerate covariance can never produce NaN/Inf.
     */
    fun mahalanobisDistance(
        innovationX: Float,
        innovationY: Float,
        covXX: Float,
        covYY: Float
    ): Float {
        val cxx = covXX.coerceAtLeast(0.001f)
        val cyy = covYY.coerceAtLeast(0.001f)
        return sqrt(innovationX * innovationX / cxx + innovationY * innovationY / cyy)
    }

    /**
     * Inlier test for the innovation gate. [threshold] is the Mahalanobis gate radius.
     */
    fun isInnovationInlier(
        innovationX: Float,
        innovationY: Float,
        covXX: Float,
        covYY: Float,
        threshold: Float
    ): Boolean = mahalanobisDistance(innovationX, innovationY, covXX, covYY) < threshold

    /**
     * One coast step: decays [confidence] by [decay] and returns the new confidence.
     * Monotonic non-increasing; clamped to [0, 1].
     */
    fun decayConfidence(confidence: Float, decay: Float): Float =
        (confidence * decay).coerceIn(0f, 1f)

    /**
     * Whether a coasting track has exhausted its coast budget and must be dropped.
     */
    fun coastExhausted(coastFrames: Int, maxCoastFrames: Int): Boolean =
        coastFrames >= maxCoastFrames
}
