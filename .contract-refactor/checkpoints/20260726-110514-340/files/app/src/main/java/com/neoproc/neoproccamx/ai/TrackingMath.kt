package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import android.graphics.RectF
import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL TRACKING MATH - cinema-oriented ALGORITHMS (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Implements advanced tracking algorithms for professional camera applications:
 * - Kalman filtering for smooth state estimation
 * - Motion prediction with acceleration
 * - RANSAC-style outlier rejection
 * - Adaptive smoothing based on motion patterns
 */
internal object TrackingMath {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ════════════════════════════════════════════════════════════════════════════
    // BASIC GEOMETRY
    // ════════════════════════════════════════════════════════════════════════════

    fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        val intersection = width * height
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // KALMAN FILTER IMPLEMENTATION
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Initialize Kalman filter state from first observation
     */
    fun initializeKalman(
        x: Float, 
        y: Float,
        initialPositionVariance: Float = 0.1f,
        initialVelocityVariance: Float = 1.0f
    ): KalmanState {
        val state = FloatArray(6) // [x, y, vx, vy, ax, ay]
        state[0] = x
        state[1] = y
        
        val covariance = FloatArray(36) // 6x6 matrix
        covariance[0] = initialPositionVariance  // P[0,0]
        covariance[7] = initialPositionVariance  // P[1,1]
        covariance[14] = initialVelocityVariance // P[2,2]
        covariance[21] = initialVelocityVariance // P[3,3]
        covariance[28] = initialVelocityVariance * 2f // P[4,4]
        covariance[35] = initialVelocityVariance * 2f // P[5,5]
        
        return KalmanState(
            state = state,
            covariance = covariance,
            processNoise = if (strictHighEndEnabled()) 0.008f else 0.01f,
            measurementNoise = if (strictHighEndEnabled()) 0.09f else 0.1f
        )
    }

    /**
     * Kalman filter prediction step
     * Predicts next state based on motion model
     */
    fun kalmanPredict(
        kalman: KalmanState,
        dt: Float
    ): KalmanState {
        // State transition matrix F (constant acceleration model)
        // x' = x + vx*dt + 0.5*ax*dt^2
        // y' = y + vy*dt + 0.5*ay*dt^2
        // vx' = vx + ax*dt
        // vy' = vy + ay*dt
        // ax' = ax (assumed constant)
        // ay' = ay (assumed constant)
        
        val dt2 = dt * dt
        val halfDt2 = 0.5f * dt2
        
        val newState = FloatArray(6)
        newState[0] = kalman.state[0] + kalman.state[2] * dt + kalman.state[4] * halfDt2
        newState[1] = kalman.state[1] + kalman.state[3] * dt + kalman.state[5] * halfDt2
        newState[2] = kalman.state[2] + kalman.state[4] * dt
        newState[3] = kalman.state[3] + kalman.state[5] * dt
        newState[4] = kalman.state[4] // Constant acceleration assumption
        newState[5] = kalman.state[5]
        
        // Update covariance: P' = F*P*F^T + Q
        val newCov = predictCovariance(kalman.covariance, dt, kalman.processNoise)
        
        return kalman.copy().apply {
            state.indices.forEach { i -> state[i] = newState[i] }
            covariance.indices.forEach { i -> covariance[i] = newCov[i] }
        }
    }

    /**
     * Kalman filter update step
     * Corrects prediction with measurement
     */
    fun kalmanUpdate(
        kalman: KalmanState,
        measurementX: Float,
        measurementY: Float,
        nowMs: Long
    ): KalmanState {
        // Measurement: we only observe position [x, y]
        // Innovation: y = z - H*x
        val innovationX = measurementX - kalman.state[0]
        val innovationY = measurementY - kalman.state[1]
        
        // Innovation covariance: S = H*P*H^T + R
        val S00 = kalman.covariance[0] + kalman.measurementNoise
        val S11 = kalman.covariance[7] + kalman.measurementNoise
        
        // Kalman gain: K = P*H^T * S^-1
        val K = FloatArray(12) // 6x2 matrix
        if (S00 > 0f) {
            K[0] = kalman.covariance[0] / S00   // K[0,0]
            K[2] = kalman.covariance[2] / S00   // K[2,0]
            K[4] = kalman.covariance[4] / S00   // K[4,0]
        }
        if (S11 > 0f) {
            K[7] = kalman.covariance[7] / S11   // K[1,1]
            K[9] = kalman.covariance[9] / S11   // K[3,1]
            K[11] = kalman.covariance[11] / S11 // K[5,1]
        }
        
        // Update state: x = x + K*innovation
        val newState = kalman.state.copyOf()
        newState[0] += K[0] * innovationX + K[1] * innovationY
        newState[1] += K[6] * innovationX + K[7] * innovationY
        newState[2] += K[2] * innovationX + K[3] * innovationY
        newState[3] += K[8] * innovationX + K[9] * innovationY
        newState[4] += K[4] * innovationX + K[5] * innovationY
        newState[5] += K[10] * innovationX + K[11] * innovationY
        
        // Update covariance: P = (I - K*H)*P
        val newCov = updateCovariance(kalman.covariance, K)
        
        val innovationMag = sqrt(innovationX * innovationX + innovationY * innovationY)
        val newConfidence = (1f / (1f + innovationMag)).coerceIn(0f, 1f)
        
        return KalmanState(
            state = newState,
            covariance = newCov,
            processNoise = kalman.processNoise,
            measurementNoise = kalman.measurementNoise,
            lastUpdateMs = nowMs,
            innovation = innovationMag,
            confidence = kalman.confidence * 0.9f + newConfidence * 0.1f
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // MOTION PREDICTION
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Predict center position with acceleration-based model
     */
    fun predictCenter(state: MotionState, leadTimeMs: Long): Pair<Float, Float> {
        val leadSeconds = leadTimeMs / 1000f
        val dt = leadSeconds
        val dt2 = dt * dt * 0.5f
        
        // Use Kalman state if available, otherwise fall back to simple velocity model
        val predX = if (state.kalmanState.isNotEmpty()) {
            state.kalmanState[0] + state.kalmanState[2] * dt + state.kalmanState[4] * dt2
        } else {
            state.centerX + state.velocityX * dt + state.accelerationX * dt2
        }
        
        val predY = if (state.kalmanState.isNotEmpty()) {
            state.kalmanState[1] + state.kalmanState[3] * dt + state.kalmanState[5] * dt2
        } else {
            state.centerY + state.velocityY * dt + state.accelerationY * dt2
        }
        
        return predX.coerceIn(0f, 1f) to predY.coerceIn(0f, 1f)
    }

    /**
     * Advanced prediction with motion pattern awareness
     */
    fun predictCenterAdvanced(
        state: MotionState,
        leadTimeMs: Long,
        motionPattern: MotionPattern
    ): Pair<Float, Float> {
        val dt = leadTimeMs / 1000f
        
        return when (motionPattern) {
            MotionPattern.STATIC -> state.centerX to state.centerY
            MotionPattern.LINEAR -> {
                val x = state.centerX + state.velocityX * dt
                val y = state.centerY + state.velocityY * dt
                x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
            }
            MotionPattern.ACCELERATING, MotionPattern.DECELERATING -> {
                val dt2 = dt * dt * 0.5f
                val x = state.centerX + state.velocityX * dt + state.accelerationX * dt2
                val y = state.centerY + state.velocityY * dt + state.accelerationY * dt2
                x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
            }
            MotionPattern.CIRCULAR -> {
                // Circular motion: predict using angular velocity
                val speed = sqrt(state.velocityX * state.velocityX + state.velocityY * state.velocityY)
                if (speed < 0.001f) return state.centerX to state.centerY
                
                val radius = speed / max(0.001f, 
                    sqrt(state.accelerationX * state.accelerationX + state.accelerationY * state.accelerationY))
                val angularVel = speed / radius
                val angle = angularVel * dt
                
                val cosA = cos(angle)
                val sinA = sin(angle)
                val dx = state.centerX - 0.5f
                val dy = state.centerY - 0.5f
                val rotX = dx * cosA - dy * sinA + 0.5f
                val rotY = dx * sinA + dy * cosA + 0.5f
                
                rotX.coerceIn(0f, 1f) to rotY.coerceIn(0f, 1f)
            }
            MotionPattern.ERRATIC -> {
                // For erratic motion, use short-term prediction only
                val dampedDt = dt * 0.5f
                val x = state.centerX + state.velocityX * dampedDt
                val y = state.centerY + state.velocityY * dampedDt
                x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // MOTION STATE UPDATE WITH KALMAN
    // ════════════════════════════════════════════════════════════════════════════

    fun updateMotionState(
        current: MotionState?,
        centerX: Float,
        centerY: Float,
        confidence: Float,
        nowMs: Long,
        smoothing: Float = 0.34f,
        width: Float = 0f,
        height: Float = 0f
    ): MotionState {
        if (current == null) {
            val kalman = initializeKalman(centerX, centerY)
            return MotionState(
                centerX = centerX,
                centerY = centerY,
                velocityX = 0f,
                velocityY = 0f,
                confidence = confidence,
                updatedAtMs = nowMs,
                width = width.coerceAtLeast(0f),
                height = height.coerceAtLeast(0f),
                hitStreak = 1,
                missCount = 0,
                lastMatchedAtMs = nowMs,
                kalmanState = kalman.state,
                kalmanCovarianceP = kalman.covariance
            )
        }
        
        val dt = ((nowMs - current.updatedAtMs).coerceAtLeast(1L)) / 1000f
        
        // Kalman filter update
        val kalman = if (current.kalmanState.size == 6) {
            val prevKalman = KalmanState(
                state = current.kalmanState,
                covariance = current.kalmanCovarianceP,
                lastUpdateMs = current.updatedAtMs
            )
            val predicted = kalmanPredict(prevKalman, dt)
            kalmanUpdate(predicted, centerX, centerY, nowMs)
        } else {
            initializeKalman(centerX, centerY)
        }
        
        // Calculate acceleration from velocity change
        val instantVx = (centerX - current.centerX) / dt
        val instantVy = (centerY - current.centerY) / dt
        val newVx = current.velocityX * (1f - smoothing) + instantVx * smoothing
        val newVy = current.velocityY * (1f - smoothing) + instantVy * smoothing
        
        val accelX = (newVx - current.velocityX) / dt
        val accelY = (newVy - current.velocityY) / dt
        
        // Detect motion pattern
        val motionPattern = detectMotionPattern(
            current.velocityX, current.velocityY,
            newVx, newVy,
            accelX, accelY
        )
        
        current.centerX = centerX
        current.centerY = centerY
        current.prevVelocityX = current.velocityX
        current.prevVelocityY = current.velocityY
        current.velocityX = newVx
        current.velocityY = newVy
        current.accelerationX = accelX
        current.accelerationY = accelY
        current.confidence = confidence
        current.updatedAtMs = nowMs
        current.motionPattern = motionPattern
        current.kalmanState = kalman.state
        current.kalmanCovarianceP = kalman.covariance
        
        if (width > 0f) {
            current.width = if (current.width <= 0f) {
                width
            } else {
                current.width * if (strictHighEndEnabled()) 0.72f else 0.68f +
                    width * if (strictHighEndEnabled()) 0.28f else 0.32f
            }
        }
        if (height > 0f) {
            current.height = if (current.height <= 0f) {
                height
            } else {
                current.height * if (strictHighEndEnabled()) 0.72f else 0.68f +
                    height * if (strictHighEndEnabled()) 0.28f else 0.32f
            }
        }
        current.hitStreak = (current.hitStreak + 1).coerceAtMost(12)
        current.missCount = 0
        current.lastMatchedAtMs = nowMs
        return current
    }

    // ════════════════════════════════════════════════════════════════════════════
    // OUTLIER REJECTION
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Detect outliers using Mahalanobis distance
     * Returns true if the measurement is an inlier
     */
    fun isInlier(
        measurementX: Float,
        measurementY: Float,
        state: MotionState,
        threshold: Float = 3.0f
    ): Boolean {
        if (state.kalmanState.size != 6) return true // No filter yet, accept all
        
        val dx = measurementX - state.kalmanState[0]
        val dy = measurementY - state.kalmanState[1]
        
        // Simple Mahalanobis distance using position covariance
        val covXX = state.kalmanCovarianceP[0].coerceAtLeast(0.001f)
        val covYY = state.kalmanCovarianceP[7].coerceAtLeast(0.001f)
        
        val mahalanobis = sqrt(dx * dx / covXX + dy * dy / covYY)
        val effectiveThreshold = if (strictHighEndEnabled()) threshold + 0.35f else threshold
        return mahalanobis < effectiveThreshold
    }

    /**
     * Adaptive outlier rejection based on tracking history
     */
    fun isInlierAdaptive(
        measurementX: Float,
        measurementY: Float,
        state: MotionState
    ): Boolean {
        // Adjust threshold based on hit streak (more lenient for new tracks)
        val baseThreshold = if (strictHighEndEnabled()) 3.2f else 3.0f
        val adaptiveThreshold = if (state.hitStreak < 5) {
            baseThreshold * 1.5f
        } else {
            baseThreshold * (1f - state.confidence * if (strictHighEndEnabled()) 0.24f else 0.3f)
        }
        
        return isInlier(measurementX, measurementY, state, adaptiveThreshold)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // ADAPTIVE SMOOTHING
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Calculate adaptive smoothing factor based on motion pattern
     */
    fun adaptiveSmoothing(
        motionPattern: MotionPattern,
        velocity: Float,
        baseSmoothing: Float = 0.34f
    ): Float {
        val patternFactor = when (motionPattern) {
            MotionPattern.STATIC -> 0.8f
            MotionPattern.LINEAR -> 0.5f
            MotionPattern.ACCELERATING -> 0.4f
            MotionPattern.DECELERATING -> 0.45f
            MotionPattern.ERRATIC -> 0.2f
            MotionPattern.CIRCULAR -> 0.4f
        }
        
        // Reduce smoothing for fast motion
        val velocityFactor = if (velocity > 0.05f) {
            (1f - (velocity - 0.05f).coerceAtMost(0.3f))
        } else {
            1f
        }
        
        val minSmoothing = if (strictHighEndEnabled()) 0.12f else 0.1f
        val maxSmoothing = if (strictHighEndEnabled()) 0.92f else 0.9f
        return (baseSmoothing * patternFactor * velocityFactor).coerceIn(minSmoothing, maxSmoothing)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // TRACKING MISS HANDLING
    // ════════════════════════════════════════════════════════════════════════════

    fun noteTrackingMiss(
        current: MotionState,
        nowMs: Long,
        predictionLeadMs: Long,
        damping: Float = 0.78f
    ): MotionState {
        val predictedCenter = predictCenterAdvanced(current, predictionLeadMs, current.motionPattern)
        current.centerX = predictedCenter.first
        current.centerY = predictedCenter.second
        val strictDamping = if (strictHighEndEnabled()) {
            (damping + 0.04f).coerceAtMost(0.9f)
        } else {
            damping
        }
        current.velocityX *= strictDamping
        current.velocityY *= strictDamping
        current.accelerationX *= strictDamping * strictDamping
        current.accelerationY *= strictDamping * strictDamping
        current.updatedAtMs = nowMs
        current.missCount = (current.missCount + 1).coerceAtMost(if (strictHighEndEnabled()) 10 else 8)
        current.hitStreak = (current.hitStreak - 1).coerceAtLeast(0)
        
        // Update Kalman with prediction only (no measurement)
        if (current.kalmanState.size == 6) {
            val dt = predictionLeadMs / 1000f
            val kalman = KalmanState(
                state = current.kalmanState,
                covariance = current.kalmanCovarianceP
            )
            val predicted = kalmanPredict(kalman, dt)
            current.kalmanState = predicted.state
            current.kalmanCovarianceP = predicted.covariance
        }
        
        return current
    }

    // ════════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Detect motion pattern from velocity and acceleration
     */
    private fun detectMotionPattern(
        vxOld: Float, vyOld: Float,
        vxNew: Float, vyNew: Float,
        ax: Float, ay: Float
    ): MotionPattern {
        val strictHighEnd = strictHighEndEnabled()
        val speedOld = sqrt(vxOld * vxOld + vyOld * vyOld)
        val speedNew = sqrt(vxNew * vxNew + vyNew * vyNew)
        val accel = sqrt(ax * ax + ay * ay)
        
        // Static
        if (speedNew < (if (strictHighEnd) 0.0045f else 0.005f) &&
            accel < (if (strictHighEnd) 0.009f else 0.01f)) {
            return MotionPattern.STATIC
        }
        
        // Erratic (high acceleration, changing direction)
        if (accel > (if (strictHighEnd) 0.46f else 0.5f)) {
            val dotProduct = vxOld * vxNew + vyOld * vyNew
            val velocityAlignment = if (speedOld > 0.001f && speedNew > 0.001f) {
                dotProduct / (speedOld * speedNew)
            } else {
                1f
            }
            if (velocityAlignment < (if (strictHighEnd) 0.55f else 0.5f)) {
                return MotionPattern.ERRATIC
            }
        }
        
        // Circular (perpendicular acceleration)
        if (speedNew > (if (strictHighEnd) 0.009f else 0.01f) &&
            accel > (if (strictHighEnd) 0.045f else 0.05f)) {
            val perpAccel = abs(vxNew * ay - vyNew * ax) / speedNew
            if (perpAccel > accel * (if (strictHighEnd) 0.66f else 0.7f)) {
                return MotionPattern.CIRCULAR
            }
        }
        
        // Accelerating/Decelerating
        if (accel > (if (strictHighEnd) 0.018f else 0.02f)) {
            return if (speedNew > speedOld * (if (strictHighEnd) 1.04f else 1.05f)) {
                MotionPattern.ACCELERATING
            } else if (speedNew < speedOld * (if (strictHighEnd) 0.96f else 0.95f)) {
                MotionPattern.DECELERATING
            } else {
                MotionPattern.LINEAR
            }
        }
        
        return MotionPattern.LINEAR
    }

    /**
     * Predict covariance matrix for Kalman filter
     */
    private fun predictCovariance(P: FloatArray, dt: Float, Q: Float): FloatArray {
        // Simplified covariance prediction (diagonal dominant assumption)
        val newP = P.copyOf()
        val dt2 = dt * dt
        val dt4 = dt2 * dt2
        
        // Add process noise
        newP[0] += Q * dt4 * 0.25f  // Position variance grows with dt^4
        newP[7] += Q * dt4 * 0.25f
        newP[14] += Q * dt2        // Velocity variance grows with dt^2
        newP[21] += Q * dt2
        newP[28] += Q              // Acceleration variance is constant
        newP[35] += Q
        
        return newP
    }

    /**
     * Update covariance matrix after Kalman update
     */
    private fun updateCovariance(P: FloatArray, K: FloatArray): FloatArray {
        // Simplified: P' = (I - K*H) * P
        // H = [1 0 0 0 0 0]
        //     [0 1 0 0 0 0]
        val newP = P.copyOf()
        
        // Update only affected elements (measurement affects position only)
        newP[0] = P[0] * (1f - K[0])
        newP[7] = P[7] * (1f - K[7])
        
        return newP
    }
}
