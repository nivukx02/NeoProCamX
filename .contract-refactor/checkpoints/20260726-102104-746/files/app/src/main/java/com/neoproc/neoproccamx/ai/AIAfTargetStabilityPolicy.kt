package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL AF TARGET STABILITY - cinema-oriented ALGORITHMS (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Advanced stabilization features:
 * - Predictive stabilization using motion forecasting
 * - Adaptive deadband based on scene motion
 * - Multi-frame temporal filtering
 * - Scene-aware stability thresholds
 */

internal data class AIAfTargetDecision(
    val acceptTarget: Boolean,
    val holdExistingLock: Boolean,
    val subjectChanged: Boolean,
    val hardReacquire: Boolean,
    val shouldUpdateMetering: Boolean,
    val shouldTriggerAutofocus: Boolean,
    val stabilityScore: Float = 0f,
    val predictedTarget: Pair<Float, Float>? = null
)

/**
 * Temporal filter state for stability tracking
 */
internal data class TemporalFilterState(
    val positions: FloatArray = FloatArray(0),
    val timestamps: LongArray = LongArray(0),
    val currentIndex: Int = 0,
    val filled: Boolean = false
) {
    fun copy(): TemporalFilterState = TemporalFilterState(
        positions = positions.copyOf(),
        timestamps = timestamps.copyOf(),
        currentIndex = currentIndex,
        filled = filled
    )
}

/**
 * Internal stability metrics for AF target analysis
 */
internal data class AfStabilityMetrics(
    val positionVariance: Float,
    val velocityVariance: Float,
    val jitterMagnitude: Float,
    val temporalCoherence: Float,
    val overallStability: Float
)

internal object AIAfTargetStabilityPolicy {
    private const val NEW_SUBJECT_MIN_CONFIDENCE = 0.32f
    private const val TRACKED_SUBJECT_MIN_CONFIDENCE = 0.20f
    private const val LOCK_HOLD_MS = 180L
    
    // Advanced stability parameters
    private const val TEMPORAL_FILTER_SIZE = 12
    private const val MIN_STABILITY_SCORE = 0.45f
    private const val JITTER_THRESHOLD = 0.008f
    private const val PREDICTION_LEAD_MS = 250L

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ════════════════════════════════════════════════════════════════════════════
    // MAIN DECISION FUNCTION (ENHANCED)
    // ════════════════════════════════════════════════════════════════════════════

    fun resolveDecision(
        target: SubjectMeteringTarget,
        currentTarget: AIFloatRect?,
        moved: Float,
        nowMs: Long,
        lastUpdateMs: Long,
        lastAcceptedTargetMs: Long,
        lastTapAfMs: Long,
        lastTrackId: Int,
        lastSubjectType: SubjectType?,
        regionsChanged: Boolean,
        moveThreshold: Float,
        reacquireThreshold: Float,
        updateIntervalMs: Long,
        retriggerIntervalMs: Long
    ): AIAfTargetDecision {
        val subjectChanged =
            lastSubjectType != target.subjectType || (lastTrackId != -1 && lastTrackId != target.trackingId)
        val sameTrackedSubject = !subjectChanged && lastTrackId == target.trackingId
        val minConfidence = requiredMinimumConfidence(
            target = target,
            sameTrackedSubject = sameTrackedSubject,
            subjectChanged = subjectChanged,
            moved = moved
        )

        if (target.confidence <= minConfidence) {
            val holdExistingLock =
                sameTrackedSubject && (nowMs - lastAcceptedTargetMs) <= LOCK_HOLD_MS
            return AIAfTargetDecision(
                acceptTarget = false,
                holdExistingLock = holdExistingLock,
                subjectChanged = subjectChanged,
                hardReacquire = false,
                shouldUpdateMetering = false,
                shouldTriggerAutofocus = false,
                stabilityScore = 0f
            )
        }

        // Calculate stability score
        val stabilityScore = calculateStabilityScore(
            moved = moved,
            confidence = target.confidence,
            sameSubject = sameTrackedSubject,
            timeSinceLastUpdate = nowMs - lastUpdateMs
        )

        val reacquireMotionScale = when (target.subjectType) {
            SubjectType.EYE -> if (target.confidence >= 0.72f) 1.0f else 1.08f
            SubjectType.FACE -> if (target.confidence >= 0.68f) 1.08f else 1.14f
            SubjectType.HEAD -> if (target.confidence >= 0.68f) 1.09f else 1.15f
            SubjectType.OBJECT -> if (target.confidence >= 0.68f) 1.10f else 1.18f
            SubjectType.UPPER_BODY,
            SubjectType.BODY,
            SubjectType.FULL_BODY -> 1.22f
            SubjectType.GROUP -> 1.26f
        }
        val sameSubjectReacquireScale = when (target.subjectType) {
            SubjectType.EYE -> 1.85f
            SubjectType.FACE -> 1.70f
            SubjectType.HEAD -> 1.68f
            SubjectType.OBJECT -> 1.80f
            SubjectType.UPPER_BODY,
            SubjectType.BODY,
            SubjectType.FULL_BODY -> 1.55f
            SubjectType.GROUP -> 1.45f
        }
        val hardReacquireThreshold = if (sameTrackedSubject) {
            reacquireThreshold * sameSubjectReacquireScale
        } else {
            reacquireThreshold * reacquireMotionScale
        }
        val hardReacquire =
            currentTarget == null ||
                subjectChanged ||
                moved >= hardReacquireThreshold
        
        val shouldUpdateMetering =
            regionsChanged && (hardReacquire || moved >= moveThreshold || nowMs - lastUpdateMs >= updateIntervalMs)
        
        val shouldTriggerAutofocus =
            hardReacquire && (subjectChanged || nowMs - lastTapAfMs >= retriggerIntervalMs)
        val requiredStability = requiredStabilityThreshold(
            target = target,
            sameTrackedSubject = sameTrackedSubject,
            subjectChanged = subjectChanged,
            moved = moved
        )

        return AIAfTargetDecision(
            acceptTarget = stabilityScore >= requiredStability,
            holdExistingLock = false,
            subjectChanged = subjectChanged,
            hardReacquire = hardReacquire,
            shouldUpdateMetering = shouldUpdateMetering,
            shouldTriggerAutofocus = shouldTriggerAutofocus,
            stabilityScore = stabilityScore
        )
    }

    private fun requiredStabilityThreshold(
        target: SubjectMeteringTarget,
        sameTrackedSubject: Boolean,
        subjectChanged: Boolean,
        moved: Float
    ): Float {
        val confidence = target.confidence.coerceIn(0f, 1f)
        val confidenceEase = ((confidence - 0.65f).coerceAtLeast(0f) / 0.35f) * 0.12f
        val subjectEase = when (target.subjectType) {
            SubjectType.EYE -> 0.10f
            SubjectType.FACE -> 0.07f
            SubjectType.HEAD -> 0.06f
            SubjectType.OBJECT -> 0.06f
            SubjectType.BODY,
            SubjectType.UPPER_BODY,
            SubjectType.FULL_BODY -> 0.04f
            SubjectType.GROUP -> 0.02f
        }
        val continuityEase = if (sameTrackedSubject) 0.03f else 0f
        val motionEase = when {
            moved > 0.16f &&
                confidence >= 0.78f &&
                (target.subjectType == SubjectType.EYE || target.subjectType == SubjectType.FACE || target.subjectType == SubjectType.OBJECT) -> 0.06f
            moved > 0.08f && confidence >= 0.65f -> 0.03f
            else -> 0f
        }
        val subjectSwitchPenalty = if (subjectChanged && confidence < 0.75f) 0.04f else if (subjectChanged) 0.02f else 0f
        return (
            MIN_STABILITY_SCORE - confidenceEase - subjectEase - continuityEase - motionEase + subjectSwitchPenalty
            ).coerceIn(0.38f, MIN_STABILITY_SCORE + 0.04f)
    }

    private fun requiredMinimumConfidence(
        target: SubjectMeteringTarget,
        sameTrackedSubject: Boolean,
        subjectChanged: Boolean,
        moved: Float
    ): Float {
        val base = if (sameTrackedSubject) {
            TRACKED_SUBJECT_MIN_CONFIDENCE
        } else {
            NEW_SUBJECT_MIN_CONFIDENCE
        }
        val subjectBias = if (sameTrackedSubject) {
            when (target.subjectType) {
                SubjectType.EYE -> -0.02f
                SubjectType.OBJECT -> -0.01f
                SubjectType.FACE,
                SubjectType.HEAD,
                SubjectType.BODY,
                SubjectType.UPPER_BODY,
                SubjectType.FULL_BODY -> 0f
                SubjectType.GROUP -> 0.02f
            }
        } else {
            when (target.subjectType) {
                SubjectType.EYE -> -0.08f
                SubjectType.FACE -> -0.05f
                SubjectType.HEAD -> -0.05f
                SubjectType.OBJECT -> -0.04f
                SubjectType.BODY,
                SubjectType.UPPER_BODY,
                SubjectType.FULL_BODY -> -0.02f
                SubjectType.GROUP -> 0f
            }
        }
        val motionEase = when {
            moved > 0.20f && target.confidence >= 0.80f -> 0.06f
            moved > 0.12f &&
                target.confidence >= 0.70f &&
                (target.subjectType == SubjectType.EYE || target.subjectType == SubjectType.FACE || target.subjectType == SubjectType.OBJECT) -> 0.04f
            else -> 0f
        }
        val subjectSwitchPenalty = if (!subjectChanged) {
            0f
        } else {
            when (target.subjectType) {
                SubjectType.EYE,
                SubjectType.FACE,
                SubjectType.HEAD,
                SubjectType.OBJECT -> 0.01f
                SubjectType.BODY,
                SubjectType.UPPER_BODY,
                SubjectType.FULL_BODY,
                SubjectType.GROUP -> 0.02f
            }
        }
        // The published minimum-confidence constants are hard floors: subjectBias and
        // motionEase can only RAISE the threshold (tighten), never lower it below the
        // documented base. This prevents weak targets from being wrongly accepted just
        // because they happen to be "easy" subject types like EYE/FACE.
        val effectiveFloor = if (sameTrackedSubject) {
            TRACKED_SUBJECT_MIN_CONFIDENCE
        } else {
            NEW_SUBJECT_MIN_CONFIDENCE
        }
        return (base + subjectSwitchPenalty + subjectBias - motionEase)
            .coerceIn(effectiveFloor, 0.52f)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // ADVANCED STABILIZATION
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Calculate comprehensive stability score
     */
    fun calculateStabilityScore(
        moved: Float,
        confidence: Float,
        sameSubject: Boolean,
        timeSinceLastUpdate: Long
    ): Float {
        // Position stability (lower movement = higher score)
        val positionStability = exp(-moved * 10f).coerceIn(0f, 1f)
        
        // Confidence stability
        val confidenceStability = confidence
        
        // Temporal stability (prefer consistent updates)
        val updateDeltaMs = timeSinceLastUpdate
        val temporalStability = if (updateDeltaMs < 50L) {
            1f
        } else if (updateDeltaMs < 100L) {
            0.9f
        } else {
            0.7f
        }
        
        // Subject continuity bonus
        val continuityBonus = if (sameSubject) 0.15f else 0f
        
        val baseScore = positionStability * 0.4f +
                       confidenceStability * 0.35f +
                       temporalStability * 0.25f
        
        return (baseScore + continuityBonus).coerceIn(0f, 1f)
    }

    /**
     * Predictive stabilization using motion forecasting
     */
    fun predictStableTarget(
        currentX: Float,
        currentY: Float,
        velocityX: Float,
        velocityY: Float,
        accelerationX: Float,
        accelerationY: Float,
        motionPattern: MotionPattern,
        leadTimeMs: Long = PREDICTION_LEAD_MS
    ): Pair<Float, Float> {
        val effectiveLeadTimeMs = if (strictHighEndEnabled()) {
            leadTimeMs.coerceIn(60L, PREDICTION_LEAD_MS)
        } else {
            leadTimeMs
        }
        val dt = effectiveLeadTimeMs / 1000f
        
        return when (motionPattern) {
            MotionPattern.STATIC -> {
                currentX to currentY
            }
            MotionPattern.LINEAR -> {
                val predX = currentX + velocityX * dt
                val predY = currentY + velocityY * dt
                predX.coerceIn(0f, 1f) to predY.coerceIn(0f, 1f)
            }
            MotionPattern.ACCELERATING, MotionPattern.DECELERATING -> {
                val dt2 = dt * dt * 0.5f
                val predX = currentX + velocityX * dt + accelerationX * dt2
                val predY = currentY + velocityY * dt + accelerationY * dt2
                predX.coerceIn(0f, 1f) to predY.coerceIn(0f, 1f)
            }
            MotionPattern.CIRCULAR -> {
                // For circular motion, predict using angular velocity
                val speed = sqrt(velocityX * velocityX + velocityY * velocityY)
                if (speed < 0.001f) return currentX to currentY
                
                val radius = max(0.1f, speed / max(0.001f,
                    sqrt(accelerationX * accelerationX + accelerationY * accelerationY)))
                val angularVel = speed / radius
                val angle = angularVel * dt
                
                val cosA = cos(angle)
                val sinA = sin(angle)
                val dx = currentX - 0.5f
                val dy = currentY - 0.5f
                val rotX = dx * cosA - dy * sinA + 0.5f
                val rotY = dx * sinA + dy * cosA + 0.5f
                
                rotX.coerceIn(0f, 1f) to rotY.coerceIn(0f, 1f)
            }
            MotionPattern.ERRATIC -> {
                // For erratic motion, use damped prediction
                val dampedDt = dt * 0.3f
                val predX = currentX + velocityX * dampedDt
                val predY = currentY + velocityY * dampedDt
                predX.coerceIn(0f, 1f) to predY.coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Adaptive deadband based on scene motion
     * Returns adjusted movement threshold
     */
    fun adaptiveDeadband(
        baseDeadband: Float,
        sceneMotion: Float,
        motionPattern: MotionPattern,
        sceneType: SceneType
    ): Float {
        // Adjust deadband based on scene motion level
        val motionMultiplier = when {
            sceneMotion < 0.01f -> 1.5f  // Increase deadband for static scenes
            sceneMotion < 0.05f -> 1.2f
            sceneMotion < 0.15f -> 1.0f
            sceneMotion < 0.30f -> 0.8f
            else -> 0.6f                 // Decrease deadband for high motion
        }
        
        // Adjust for motion pattern
        val patternMultiplier = when (motionPattern) {
            MotionPattern.STATIC -> 1.8f
            MotionPattern.LINEAR -> 1.2f
            MotionPattern.ACCELERATING -> 0.9f
            MotionPattern.DECELERATING -> 1.0f
            MotionPattern.CIRCULAR -> 1.1f
            MotionPattern.ERRATIC -> 0.7f
        }
        
        // Adjust for scene type
        val sceneMultiplier = when (sceneType) {
            SceneType.ACTION -> 0.6f
            SceneType.PORTRAIT, SceneType.PORTRAIT_GROUP -> 1.4f
            SceneType.LANDSCAPE, SceneType.ARCHITECTURE -> 1.5f
            SceneType.MACRO -> 1.3f
            else -> 1.0f
        }
        
        val strictRefinement = if (strictHighEndEnabled()) {
            when (sceneType) {
                SceneType.PORTRAIT,
                SceneType.PORTRAIT_GROUP,
                SceneType.MACRO -> 0.92f
                SceneType.ACTION -> 0.88f
                else -> 0.96f
            }
        } else {
            1f
        }
        return (baseDeadband * motionMultiplier * patternMultiplier * sceneMultiplier * strictRefinement)
            .coerceIn(baseDeadband * 0.45f, baseDeadband * 2.2f)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TEMPORAL FILTERING
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Update temporal filter with new position
     */
    fun updateTemporalFilter(
        state: TemporalFilterState,
        x: Float,
        y: Float,
        timestampMs: Long
    ): TemporalFilterState {
        val size = TEMPORAL_FILTER_SIZE
        
        // Initialize if needed
        if (state.positions.isEmpty()) {
            return TemporalFilterState(
                positions = FloatArray(size * 2) { if (it % 2 == 0) x else y },
                timestamps = LongArray(size) { timestampMs },
                currentIndex = 1,
                filled = false
            )
        }
        
        // Update circular buffer
        val newPositions = state.positions.copyOf()
        val newTimestamps = state.timestamps.copyOf()
        val idx = state.currentIndex % size
        
        newPositions[idx * 2] = x
        newPositions[idx * 2 + 1] = y
        newTimestamps[idx] = timestampMs
        
        val newIndex = (state.currentIndex + 1) % size
        val filled = state.filled || newIndex == 0
        
        return TemporalFilterState(
            positions = newPositions,
            timestamps = newTimestamps,
            currentIndex = newIndex,
            filled = filled
        )
    }

    /**
     * Get temporally filtered position (median filter + exponential smoothing)
     */
    fun getFilteredPosition(state: TemporalFilterState): Pair<Float, Float> {
        if (state.positions.isEmpty()) return 0.5f to 0.5f
        
        val count = if (state.filled) TEMPORAL_FILTER_SIZE else state.currentIndex
        if (count <= 0) return 0.5f to 0.5f
        
        // Extract X and Y positions
        val xPositions = FloatArray(count) { state.positions[it * 2] }
        val yPositions = FloatArray(count) { state.positions[it * 2 + 1] }
        
        // Median filter for robustness
        xPositions.sort()
        yPositions.sort()
        
        val medianX = if (count % 2 == 0) {
            (xPositions[count / 2 - 1] + xPositions[count / 2]) / 2f
        } else {
            xPositions[count / 2]
        }
        
        val medianY = if (count % 2 == 0) {
            (yPositions[count / 2 - 1] + yPositions[count / 2]) / 2f
        } else {
            yPositions[count / 2]
        }
        
        return medianX to medianY
    }

    /**
     * Calculate stability metrics from temporal filter
     */
    fun calculateStabilityMetrics(state: TemporalFilterState): AfStabilityMetrics {
        if (state.positions.isEmpty() || state.currentIndex < 3) {
            return AfStabilityMetrics(
                positionVariance = 0f,
                velocityVariance = 0f,
                jitterMagnitude = 0f,
                temporalCoherence = 1f,
                overallStability = 0.5f
            )
        }
        
        val count = if (state.filled) TEMPORAL_FILTER_SIZE else state.currentIndex
        
        // Calculate position variance
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until count) {
            sumX += state.positions[i * 2]
            sumY += state.positions[i * 2 + 1]
        }
        val meanX = sumX / count
        val meanY = sumY / count
        
        var varianceX = 0f
        var varianceY = 0f
        for (i in 0 until count) {
            val dx = state.positions[i * 2] - meanX
            val dy = state.positions[i * 2 + 1] - meanY
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val positionVariance = sqrt((varianceX + varianceY) / count)
        
        // Calculate velocity variance (frame-to-frame changes)
        var velocitySum = 0f
        for (i in 1 until count) {
            val dt = (state.timestamps[i] - state.timestamps[i - 1]) / 1000f
            if (dt > 0f && dt < 0.2f) {
                val dx = state.positions[i * 2] - state.positions[(i - 1) * 2]
                val dy = state.positions[i * 2 + 1] - state.positions[(i - 1) * 2 + 1]
                velocitySum += sqrt(dx * dx + dy * dy) / dt
            }
        }
        val avgVelocity = velocitySum / max(1, count - 1)
        
        // Jitter detection (high-frequency position changes)
        var jitter = 0f
        for (i in 2 until count) {
            val dx1 = state.positions[i * 2] - state.positions[(i - 1) * 2]
            val dy1 = state.positions[i * 2 + 1] - state.positions[(i - 1) * 2 + 1]
            val dx2 = state.positions[(i - 1) * 2] - state.positions[(i - 2) * 2]
            val dy2 = state.positions[(i - 1) * 2 + 1] - state.positions[(i - 2) * 2 + 1]
            
            // Direction reversal indicates jitter
            if (dx1 * dx2 < 0 || dy1 * dy2 < 0) {
                jitter += 1f
            }
        }
        val jitterMagnitude = jitter / max(1, count - 2)
        
        // Temporal coherence (how smoothly positions evolve)
        val coherence = (1f - positionVariance * 5f).coerceIn(0f, 1f)
        
        // Overall stability score
        val stability = (
            (1f - positionVariance * 10f) * 0.4f +
            (1f - min(avgVelocity, 1f)) * 0.3f +
            (1f - jitterMagnitude) * 0.2f +
            coherence * 0.1f
        ).coerceIn(0f, 1f)
        
        return AfStabilityMetrics(
            positionVariance = positionVariance,
            velocityVariance = avgVelocity,
            jitterMagnitude = jitterMagnitude,
            temporalCoherence = coherence,
            overallStability = stability
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // SCENE-AWARE THRESHOLDS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Adjust stability thresholds based on scene characteristics
     */
    fun getSceneAwareThreshold(
        baseThreshold: Float,
        sceneType: SceneType,
        subjectType: SubjectType,
        lowLight: Boolean
    ): Float {
        // Scene type adjustment
        val sceneMultiplier = when (sceneType) {
            SceneType.ACTION, SceneType.FIREWORKS -> 0.7f  // More lenient
            SceneType.PORTRAIT, SceneType.PORTRAIT_GROUP -> 1.2f  // More strict
            SceneType.MACRO -> 1.4f  // Very strict
            SceneType.LANDSCAPE, SceneType.ARCHITECTURE -> 1.3f
            SceneType.NIGHT_EXTREME, SceneType.ASTRO -> 0.8f  // Lenient (noisy)
            else -> 1.0f
        }
        
        // Subject type adjustment
        val subjectMultiplier = when (subjectType) {
            SubjectType.EYE -> 1.3f  // Strict for eyes
            SubjectType.FACE -> 1.1f
            SubjectType.HEAD -> 1.05f
            SubjectType.BODY, SubjectType.UPPER_BODY, SubjectType.FULL_BODY -> 0.9f
            SubjectType.OBJECT -> 0.8f
            SubjectType.GROUP -> 0.7f
        }
        
        // Low light adjustment (noisier = more lenient)
        val lightMultiplier = if (lowLight) 0.85f else 1.0f
        
        return baseThreshold * sceneMultiplier * subjectMultiplier * lightMultiplier
    }
}
