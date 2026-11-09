package com.neoproc.neoproccamx.ai

import android.graphics.PointF
import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

private fun strictHighEndEnabled(): Boolean =
    HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL TRACKING TYPES - cinema-oriented QUALITY (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 */

/**
 * Tracking quality levels for adaptive algorithm selection
 */
enum class TrackingQuality {
    /** Fast, low-latency tracking for preview (15ms budget) */
    FAST,
    /** Balanced quality/performance (30ms budget) */
    BALANCED,
    /** High accuracy for critical scenes (50ms budget) */
    HIGH,
    /** cinema-oriented precision for professional capture (no time limit) */
    CINEMA
}

/**
 * Tracking strategy determines the algorithm used
 */
enum class TrackingStrategy {
    /** Template matching with correlation */
    CORRELATION,
    /** Feature point tracking (ORB/FAST) */
    FEATURE_BASED,
    /** Optical flow (Lucas-Kanade) */
    OPTICAL_FLOW,
    /** Kalman filter prediction */
    KALMAN_PREDICTION,
    /** Particle filter (multi-hypothesis) */
    PARTICLE_FILTER,
    /** Extended Kalman Filter for non-linear motion */
    EXTENDED_KALMAN,
    /** Ensemble of multiple trackers */
    ENSEMBLE,
    /** Deep learning based tracking */
    DEEP_TRACKING
}

/**
 * Filter types for state estimation
 */
enum class FilterType {
    /** Linear Kalman filter for constant velocity */
    KALMAN,
    /** Extended Kalman filter for non-linear dynamics */
    EXTENDED_KALMAN,
    /** Unscented Kalman filter for high non-linearity */
    UNSCENTED_KALMAN,
    /** Particle filter for multi-modal distributions */
    PARTICLE,
    /** Complementary filter (gyro + vision fusion) */
    COMPLEMENTARY,
    /** Moving average smoothing */
    MOVING_AVERAGE,
    /** Exponential smoothing */
    EXPONENTIAL,
    /** Savitzky-Golay filter for trajectory smoothing */
    SAVITZKY_GOLAY
}

/**
 * Prediction model configuration for motion forecasting
 */
data class PredictionModel(
    /** Prediction horizon in milliseconds */
    val horizonMs: Int = if (strictHighEndEnabled()) 120 else 100,
    /** Model type (linear, polynomial, spline) */
    val modelType: PredictionModelType = PredictionModelType.LINEAR,
    /** Confidence threshold for using predictions */
    val confidenceThreshold: Float = if (strictHighEndEnabled()) 0.64f else 0.6f,
    /** Maximum extrapolation distance (normalized) */
    val maxExtrapolation: Float = if (strictHighEndEnabled()) 0.12f else 0.15f,
    /** Use acceleration for predictions */
    val useAcceleration: Boolean = true
)

enum class PredictionModelType {
    /** Constant position (no prediction) */
    CONSTANT,
    /** Constant velocity model */
    LINEAR,
    /** Constant acceleration model */
    QUADRATIC,
    /** Polynomial trajectory fit */
    POLYNOMIAL,
    /** Cubic spline interpolation */
    SPLINE,
    /** Neural network prediction */
    LEARNED
}

/**
 * Kalman filter state for 2D tracking
 * State vector: [x, y, vx, vy, ax, ay]
 */
data class KalmanState(
    /** State vector [x, y, vx, vy, ax, ay] */
    val state: FloatArray = FloatArray(6),
    /** Covariance matrix (6x6 flattened) */
    val covariance: FloatArray = FloatArray(36) { if (it % 7 == 0) 1f else 0f },
    /** Process noise covariance */
    val processNoise: Float = 0.01f,
    /** Measurement noise covariance */
    val measurementNoise: Float = 0.1f,
    /** Last update timestamp */
    val lastUpdateMs: Long = 0L,
    /** Innovation (residual) magnitude */
    val innovation: Float = 0f,
    /** Filter confidence (0-1) */
    val confidence: Float = 1f
) {
    val x: Float get() = state[0]
    val y: Float get() = state[1]
    val vx: Float get() = state[2]
    val vy: Float get() = state[3]
    val ax: Float get() = state[4]
    val ay: Float get() = state[5]
    
    fun copy(): KalmanState = KalmanState(
        state = state.copyOf(),
        covariance = covariance.copyOf(),
        processNoise = processNoise,
        measurementNoise = measurementNoise,
        lastUpdateMs = lastUpdateMs,
        innovation = innovation,
        confidence = confidence
    )
}

/**
 * Outlier rejection configuration
 */
data class OutlierRejection(
    /** Enable RANSAC-style outlier detection */
    val enabled: Boolean = true,
    /** Mahalanobis distance threshold for outliers */
    val mahalanobisThreshold: Float = 3.0f,
    /** IQR multiplier for outlier detection */
    val iqrMultiplier: Float = 1.5f,
    /** Minimum inlier ratio to accept tracking */
    val minInlierRatio: Float = if (strictHighEndEnabled()) 0.72f else 0.7f,
    /** Temporal consistency check window (frames) */
    val temporalWindowSize: Int = if (strictHighEndEnabled()) 6 else 5
)

/**
 * Adaptive smoothing parameters based on motion pattern
 */
data class AdaptiveSmoothing(
    /** Base smoothing factor (0 = no smoothing, 1 = max smoothing) */
    val baseFactor: Float = if (strictHighEndEnabled()) 0.34f else 0.3f,
    /** Smoothing for static subjects */
    val staticFactor: Float = if (strictHighEndEnabled()) 0.82f else 0.8f,
    /** Smoothing for linear motion */
    val linearFactor: Float = 0.5f,
    /** Smoothing for erratic motion */
    val erraticFactor: Float = if (strictHighEndEnabled()) 0.22f else 0.2f,
    /** Smoothing for circular motion */
    val circularFactor: Float = 0.4f,
    /** Adapt based on velocity magnitude */
    val velocityAdaptive: Boolean = true,
    /** Velocity threshold for switching smoothing */
    val velocityThreshold: Float = 0.05f
)

data class DetectedFace(
    val id: Int,
    val bounds: RectF,
    val leftEye: PointF? = null,
    val rightEye: PointF? = null,
    val leftEyeOpenProb: Float = 0f,
    val rightEyeOpenProb: Float = 0f,
    val smileProb: Float = 0f,
    val eulerY: Float = 0f,
    val eulerZ: Float = 0f
)

enum class FocusTargetType {
    EYE,
    FACE,
    BODY,
    OBJECT
}

data class FocusTarget(
    val normalizedX: Float,
    val normalizedY: Float,
    val type: FocusTargetType,
    val trackId: Int
)
