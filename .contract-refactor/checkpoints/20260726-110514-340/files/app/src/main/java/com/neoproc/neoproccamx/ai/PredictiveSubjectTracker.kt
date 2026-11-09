package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PREDICTIVE SUBJECT TRACKER - KALMAN-FILTERED TRACKING WITH MOTION COMPENSATION
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks subject motion using Kalman filtering with velocity and acceleration prediction.
 * Provides smooth, anticipatory focus points that lead moving subjects.
 * 
 * Key features:
 * - Kalman filter for state estimation (position, velocity, acceleration)
 * - Predictive lead computation for moving subjects
 * - Weighted face/body selection with continuity bias
 * - Outlier rejection using motion consistency checks
 * - Adaptive prediction gain based on tracking confidence
 *
 * s7-f10/CP9A SHARED SEAM: updateTracking(faces, pose, objects, timestampMs) is THE single
 * subject smoother; downstream features (s7-f02 face, s7-f05 pose, s7-f06 object) consume the
 * returned PredictedSubjectLocation contract (subjectId prefix face:/pose:/object:,
 * predictedCenterX/Y, confidence, velocity, isMoving) rather than re-deriving Kalman state.
 */
class PredictiveSubjectTracker {
    companion object {
        private const val TAG = "PredictiveSubjectTracker"
        private const val PREDICTION_LEAD_MS_STRICT = 55L      // Strict high-end prediction
        private const val PREDICTION_LEAD_MS_STANDARD = 35L    // Standard prediction
        private const val VELOCITY_DAMPING_STRICT = 0.92f     // Higher smoothing
        private const val VELOCITY_DAMPING_STANDARD = 0.88f
        private const val CONFIDENCE_THRESHOLD = 0.42f
        private const val MAX_VELOCITY_RATIO = 0.15f           // Max velocity as % of frame
        // s7-f10/CP9A: default ON (user-directed 2026-07-03). When false the legacy fixed-threshold
        // reject + 500ms hard-drop path runs UNCHANGED (golden-locked); when true (the default) a
        // Mahalanobis innovation gate + bounded coast/decay path is used. NOTE: the coast/decay
        // thresholds are device-calibration PLACEHOLDERS to tune on the vivo I2405 (AF-coast).
        internal const val HARDENED_TRACKING_DEFAULT = true

        internal const val OBJECT_RACK_PULL_LAMBDA_CINEMA = 3.5f
        internal const val OBJECT_RACK_PULL_LAMBDA_ACTION = 8.5f

        fun cinematicRackPullStep(
            previous: Float,
            target: Float,
            deltaTimeSeconds: Float,
            cinemaMode: Boolean
        ): Float {
            val lambda = if (cinemaMode) OBJECT_RACK_PULL_LAMBDA_CINEMA else OBJECT_RACK_PULL_LAMBDA_ACTION
            val eased = 1.0f - exp(-lambda * deltaTimeSeconds.coerceAtLeast(0f))
            return previous + (target - previous) * eased
        }
    }

    private val trackedSubjects = LinkedHashMap<String, SubjectTrackingState>()
    private var lastSelectedSubjectId: String? = null
    private var consecutiveFramesOnSubject: Int = 0

    // s7-f10/CP9A: default ON. When false the legacy path runs unchanged; when true (the default)
    // the Mahalanobis innovation gate + bounded coast-through-occlusion path is used.
    @Volatile var hardenedTrackingEnabled: Boolean = HARDENED_TRACKING_DEFAULT
    private val coastFrames = LinkedHashMap<String, Int>()

    data class SubjectTrackingState(
        val subjectId: String,
        val kalmanState: KalmanState,
        val lastObservedBounds: RectF,
        val lastObservedConfidence: Float,
        val velocityX: Float = 0f,
        val velocityY: Float = 0f,
        val accelerationX: Float = 0f,
        val accelerationY: Float = 0f,
        val lastUpdateMs: Long = 0L,
        val trackingDurationMs: Long = 0L
    )

    data class PredictedSubjectLocation(
        val subjectId: String,
        val predictedBounds: RectF,
        val predictedCenterX: Float,
        val predictedCenterY: Float,
        val confidence: Float,
        val velocity: Pair<Float, Float>,
        val isMoving: Boolean
    )

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun predictionLeadMs(): Long =
        if (strictHighEndEnabled()) PREDICTION_LEAD_MS_STRICT else PREDICTION_LEAD_MS_STANDARD

    private fun velocityDamping(): Float =
        if (strictHighEndEnabled()) VELOCITY_DAMPING_STRICT else VELOCITY_DAMPING_STANDARD

    /**
     * Update tracking state with new observations from face/body detections.
     * Returns predicted subject locations for all tracked subjects.
     */
    fun updateTracking(
        faces: List<TrackedFace>,
        pose: TrackedPose?,
        objects: List<TrackedObject>,
        timestampMs: Long
    ): List<PredictedSubjectLocation> {
        val predictions = mutableListOf<PredictedSubjectLocation>()
        val dt = 0.016f // Assume 60fps frame interval

        // Create observation map
        val observationMap = mutableMapOf<String, Pair<RectF, Float>>()
        faces.forEach { face ->
            observationMap["face:${face.trackingId}"] = Pair(face.boundingBox, face.confidence)
        }
        pose?.let {
            observationMap["pose:${it.trackingId}"] = Pair(it.boundingBox, it.confidence)
        }
        objects.forEach { obj ->
            observationMap["object:${obj.trackingId}"] = Pair(obj.boundingBox, obj.confidence)
        }

        // Update or initialize Kalman states
        observationMap.forEach { (subjectId, obsData) ->
            val (bounds, confidence) = obsData
            val state = trackedSubjects[subjectId]

            if (state == null) {
                // Initialize new subject
                val kalman = TrackingMath.initializeKalman(
                    x = bounds.centerX(),
                    y = bounds.centerY(),
                    initialPositionVariance = 0.1f,
                    initialVelocityVariance = 0.5f
                )
                trackedSubjects[subjectId] = SubjectTrackingState(
                    subjectId = subjectId,
                    kalmanState = kalman,
                    lastObservedBounds = bounds,
                    lastObservedConfidence = confidence,
                    lastUpdateMs = timestampMs,
                    trackingDurationMs = 0L
                )
            } else {
                // Update existing subject - Kalman predict step
                val predictedKalman = TrackingMath.kalmanPredict(state.kalmanState, dt)

                // Update with measurement (Kalman update step)
                val innovationX = bounds.centerX() - predictedKalman.state[0]
                val innovationY = bounds.centerY() - predictedKalman.state[1]

                // Check for outliers. Default path keeps the exact legacy fixed-threshold
                // reject so accepted/rejected decisions are bit-identical when the flag is off.
                val innovationMagnitude = sqrt(innovationX * innovationX + innovationY * innovationY)
                val accept = if (hardenedTrackingEnabled) {
                    val gateThreshold = if (strictHighEndEnabled()) {
                        PredictiveTrackerHardening.DEFAULT_MAHALANOBIS_THRESHOLD_STRICT
                    } else {
                        PredictiveTrackerHardening.DEFAULT_MAHALANOBIS_THRESHOLD
                    }
                    PredictiveTrackerHardening.isInnovationInlier(
                        innovationX, innovationY,
                        predictedKalman.covariance[0], predictedKalman.covariance[7],
                        gateThreshold
                    )
                } else {
                    innovationMagnitude < MAX_VELOCITY_RATIO
                }
                if (accept) {
                    if (hardenedTrackingEnabled) coastFrames[subjectId] = 0
                    val updatedKalman = TrackingMath.kalmanUpdate(
                        kalman = predictedKalman,
                        measurementX = bounds.centerX(),
                        measurementY = bounds.centerY(),
                        nowMs = timestampMs
                    )

                    // Extract velocity with damping for smoothness
                    val newVelX = updatedKalman.state[2] * velocityDamping()
                    val newVelY = updatedKalman.state[3] * velocityDamping()

                    val newAccelX = updatedKalman.state[4]
                    val newAccelY = updatedKalman.state[5]

                    trackedSubjects[subjectId] = state.copy(
                        kalmanState = updatedKalman,
                        lastObservedBounds = bounds,
                        lastObservedConfidence = confidence,
                        velocityX = newVelX,
                        velocityY = newVelY,
                        accelerationX = newAccelX,
                        accelerationY = newAccelY,
                        lastUpdateMs = timestampMs,
                        trackingDurationMs = state.trackingDurationMs + (timestampMs - state.lastUpdateMs)
                    )
                }
            }
        }

        // s7-f10/CP9A: coast-through-occlusion (hardened path only). For tracks with no
        // observation this frame, advance the Kalman on the model and decay confidence
        // monotonically, dropping the track once the coast budget (or 500ms) is exceeded.
        // Runs BEFORE prediction emission so the coasted track surfaces decayed confidence
        // in the same frame. Default path leaves trackedSubjects untouched here and uses the
        // legacy cleanup below, so behaviour is bit-for-bit identical when the flag is off.
        if (hardenedTrackingEnabled) {
            val decay = if (strictHighEndEnabled()) {
                PredictiveTrackerHardening.COAST_CONFIDENCE_DECAY_STRICT
            } else {
                PredictiveTrackerHardening.COAST_CONFIDENCE_DECAY
            }
            val maxCoast = if (strictHighEndEnabled()) {
                PredictiveTrackerHardening.MAX_COAST_FRAMES_STRICT
            } else {
                PredictiveTrackerHardening.MAX_COAST_FRAMES
            }
            val unobserved = trackedSubjects.keys.filter { it !in observationMap.keys }
            for (subjectId in unobserved) {
                val state = trackedSubjects[subjectId] ?: continue
                val nextCoast = (coastFrames[subjectId] ?: 0) + 1
                if (PredictiveTrackerHardening.coastExhausted(nextCoast, maxCoast) ||
                    (timestampMs - state.lastUpdateMs) > 500L
                ) {
                    trackedSubjects.remove(subjectId)
                    coastFrames.remove(subjectId)
                } else {
                    coastFrames[subjectId] = nextCoast
                    val coasted = TrackingMath.kalmanPredict(state.kalmanState, dt)
                    trackedSubjects[subjectId] = state.copy(
                        kalmanState = coasted,
                        lastObservedConfidence =
                            PredictiveTrackerHardening.decayConfidence(state.lastObservedConfidence, decay)
                    )
                }
            }
        }

        // Generate predictions for all tracked subjects
        trackedSubjects.forEach { (_, state) ->
            val leadTimeS = predictionLeadMs() / 1000f
            val predictedX = state.kalmanState.state[0] + state.velocityX * leadTimeS
            val predictedY = state.kalmanState.state[1] + state.velocityY * leadTimeS

            val predictedBounds = RectF().apply {
                left = predictedX - state.lastObservedBounds.width() / 2
                top = predictedY - state.lastObservedBounds.height() / 2
                right = predictedX + state.lastObservedBounds.width() / 2
                bottom = predictedY + state.lastObservedBounds.height() / 2
            }

            val isMoving = abs(state.velocityX) > 0.005f || abs(state.velocityY) > 0.005f

            predictions.add(
                PredictedSubjectLocation(
                    subjectId = state.subjectId,
                    predictedBounds = predictedBounds,
                    predictedCenterX = predictedX,
                    predictedCenterY = predictedY,
                    confidence = state.lastObservedConfidence,
                    velocity = Pair(state.velocityX, state.velocityY),
                    isMoving = isMoving
                )
            )
        }

        // Clean up stale tracks (older than 500ms). The hardened path already pruned coasted
        // tracks above (coast budget / 500ms); the legacy path keeps the original hard-drop.
        if (!hardenedTrackingEnabled) {
            trackedSubjects.entries.removeAll { (_, state) ->
                (timestampMs - state.lastUpdateMs) > 500L
            }
        }

        return predictions
    }

    /**
     * Select best subject for focus based on predictions, with continuity bias.
     */
    fun selectPrimarySubject(
        predictions: List<PredictedSubjectLocation>,
        isFacePreferred: Boolean = true
    ): PredictedSubjectLocation? {
        if (predictions.isEmpty()) return null

        val sorted = predictions.sortedByDescending { pred ->
            var score = pred.confidence

            // Prefer faces
            if (isFacePreferred && pred.subjectId.startsWith("face:")) {
                score += 0.2f
            }
            if (pred.subjectId.startsWith("object:")) {
                val cx = (pred.predictedBounds.left + pred.predictedBounds.right) * 0.5f
                val cy = (pred.predictedBounds.top + pred.predictedBounds.bottom) * 0.5f
                val width = (pred.predictedBounds.right - pred.predictedBounds.left).coerceAtLeast(0f)
                val height = (pred.predictedBounds.bottom - pred.predictedBounds.top).coerceAtLeast(0f)
                val centrality = (1.0f - sqrt((cx - 0.5f) * (cx - 0.5f) + (cy - 0.5f) * (cy - 0.5f)) * 1.4142135f)
                    .coerceIn(0f, 1f)
                score += centrality * 0.18f + (width * height).coerceIn(0f, 1f) * 0.08f
            }

            // Continuity bias - stay on current subject if still strong
            if (pred.subjectId == lastSelectedSubjectId && consecutiveFramesOnSubject > 0) {
                score += (consecutiveFramesOnSubject * 0.05f).coerceAtMost(0.3f)
            }

            // Penalty for moving too much (favor stationary subjects)
            if (!pred.isMoving) {
                score += 0.1f
            }

            score
        }

        val selected = sorted.firstOrNull()
        if (selected != null) {
            if (selected.subjectId == lastSelectedSubjectId) {
                consecutiveFramesOnSubject++
            } else {
                lastSelectedSubjectId = selected.subjectId
                consecutiveFramesOnSubject = 1
            }
        }

        return selected
    }

    /**
     * Reset tracking state (e.g., when camera switches or user taps)
     */
    fun reset() {
        trackedSubjects.clear()
        coastFrames.clear()
        lastSelectedSubjectId = null
        consecutiveFramesOnSubject = 0
    }

    /**
     * Get current tracking confidence for a subject
     */
    fun getTrackingConfidence(subjectId: String): Float {
        val state = trackedSubjects[subjectId] ?: return 0f
        val confidenceBase = state.lastObservedConfidence
        val durationBonus = (state.trackingDurationMs / 500f).coerceAtMost(0.2f)
        return (confidenceBase + durationBonus).coerceAtMost(1f)
    }

    /**
     * Get tracking stability indicator (how long we've tracked current subject)
     */
    fun getTrackingStability(): Float {
        if (lastSelectedSubjectId == null) return 0f
        return (consecutiveFramesOnSubject / 30f).coerceAtMost(1f)  // Stable after 30 frames
    }
}
