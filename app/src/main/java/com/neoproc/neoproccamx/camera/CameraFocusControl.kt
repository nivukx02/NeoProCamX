package com.neoproc.neoproccamx.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.pow

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END FOCUS CONTROL SYSTEM
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade autofocus control with cinema-level precision.
// Supports advanced features: rack focus, focus zones, predictive tracking.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Focus tracking mode for professional cinematography.
 */
internal enum class FocusTrackingMode {
    /** Standard single-point autofocus */
    SINGLE_POINT,
    /** Continuous tracking of moving subjects */
    CONTINUOUS_TRACKING,
    /** Face/Eye priority autofocus */
    FACE_PRIORITY,
    /** Subject recognition with AI tracking */
    SUBJECT_TRACKING,
    /** Zone-based focus with weighted regions */
    ZONE_FOCUS,
    /** Wide area covering multiple focus points */
    WIDE_AREA
}

/**
 * Focus speed profile for different shooting scenarios.
 */
internal enum class FocusSpeedProfile {
    /** Instant focus - fastest possible acquisition */
    INSTANT,
    /** Standard speed - balanced for most scenarios */
    STANDARD,
    /** Cinematic - smooth rack focus transitions */
    CINEMATIC,
    /** Slow rack - dramatic focus pulls */
    SLOW_RACK,
    /** Custom speed defined by user */
    CUSTOM
}

/**
 * Focus metering region with professional weighting.
 */
internal data class FocusMeteringZone(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val weight: Float = 1f,
    val trackingId: Int = -1
) {
    val area: Float get() = radiusX * radiusY * 3.14159f
    
    fun contains(x: Float, y: Float): Boolean {
        val dx = (x - centerX) / radiusX
        val dy = (y - centerY) / radiusY
        return (dx * dx + dy * dy) <= 1f
    }
    
    fun distanceFrom(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Result of focus operation with detailed status.
 */
internal data class FocusResult(
    val achieved: Boolean,
    val diopters: Float,
    val distanceMeters: Float,
    val confidenceScore: Float,
    val trackingState: Int,
    val focusMode: Int
)

internal object CameraFocusControl {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    
    // ═══════════════════════════════════════════════════════════════════════════
    // cinema-oriented CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Default focus window ratio for tap-to-focus */
    private const val DEFAULT_FOCUS_WINDOW_RATIO = 0.12f
    
    /** Spot focus window ratio (smaller, more precise) */
    private const val SPOT_FOCUS_WINDOW_RATIO = 0.06f
    
    /** Wide focus window ratio (larger coverage) */
    private const val WIDE_FOCUS_WINDOW_RATIO = 0.25f
    
    /** Face detection focus window ratio */
    private const val FACE_FOCUS_WINDOW_RATIO = 0.18f
    
    /** Eye tracking focus window ratio (tight around eye) */
    private const val EYE_FOCUS_WINDOW_RATIO = 0.04f
    
    /** Minimum valid focus distance in diopters */
    private const val MIN_FOCUS_DIOPTERS = 0.001f
    
    /** Focus hunting threshold - minimum change to trigger refocus */
    private const val FOCUS_HUNTING_THRESHOLD = 0.02f
    
    /** Smooth focus transition speed (diopters per second) */
    private const val SMOOTH_FOCUS_SPEED = 4.0f
    
    /** Cinematic rack focus speed (diopters per second) */
    private const val CINEMATIC_RACK_SPEED = 1.5f

    /** Strict high-end ratio multiplier for tighter metering windows */
    private const val STRICT_FOCUS_WINDOW_MULTIPLIER = 0.85f
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AUTOFOCUS MODE SELECTION
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // s5-f01 / CP-F2: the hardware afMode preference order is an EXPLICIT, documented, pure
    // function of (device-available modes, isRecording). These constants ARE the golden
    // preference table — they reproduce today's behavior bit-for-bit and are locked by
    // CameraFocusControlTest. Do not reorder without updating the golden lock: the order is the
    // contract (deterministic mode selection, no mid-take thrash).

    /**
     * Preferred continuous-AF modes while recording / live-preview, most-preferred first.
     * Video servo first (smooth, damped for motion footage), then picture servo, then plain AUTO.
     */
    private val AF_MODE_PREFERENCE_RECORDING = intArrayOf(
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        CaptureRequest.CONTROL_AF_MODE_AUTO
    )

    /**
     * Preferred continuous-AF modes for still / non-recording, most-preferred first.
     * Picture servo first (snappier acquisition for stills), then video servo, then plain AUTO.
     */
    private val AF_MODE_PREFERENCE_STILL = intArrayOf(
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
        CaptureRequest.CONTROL_AF_MODE_AUTO
    )

    /**
     * Preferred single-shot (tap / trigger) AF modes, most-preferred first. AUTO gives a clean
     * one-shot lock that honors CONTROL_AF_TRIGGER_START; MACRO is the close-focus single-shot
     * fallback. When neither is available the caller falls back to the continuous selector.
     */
    private val SINGLE_SHOT_AF_MODE_PREFERENCE = intArrayOf(
        CaptureRequest.CONTROL_AF_MODE_AUTO,
        CaptureRequest.CONTROL_AF_MODE_MACRO
    )

    fun continuousAutoFocusMode(isRecording: Boolean): Int =
        if (isRecording) {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }

    fun selectAvailableContinuousAutoFocusMode(
        availableModes: IntArray,
        isRecording: Boolean
    ): Int {
        val preferred = if (isRecording) AF_MODE_PREFERENCE_RECORDING else AF_MODE_PREFERENCE_STILL
        return preferred.firstOrNull { it in availableModes }
            ?: CaptureRequest.CONTROL_AF_MODE_OFF
    }

    /**
     * Single-shot AF mode for tap / trigger captures. Reproduces the previously-inline
     * CameraController branch exactly: prefer AUTO, then MACRO, otherwise fall back to the
     * continuous selector for the current recording state. Pure function of device capabilities;
     * zero per-frame cost (s5-f01 / CP-F2).
     */
    fun selectSingleShotAutoFocusMode(
        availableModes: IntArray,
        isRecording: Boolean
    ): Int =
        SINGLE_SHOT_AF_MODE_PREFERENCE.firstOrNull { it in availableModes }
            ?: selectAvailableContinuousAutoFocusMode(availableModes, isRecording)

    /**
     * Select optimal AF mode based on shooting scenario.
     */
    fun selectOptimalAfMode(
        isRecording: Boolean,
        trackingMode: FocusTrackingMode,
        supportsFullFocus: Boolean
    ): Int {
        return when (trackingMode) {
            FocusTrackingMode.SINGLE_POINT -> CaptureRequest.CONTROL_AF_MODE_AUTO
            
            FocusTrackingMode.CONTINUOUS_TRACKING,
            FocusTrackingMode.FACE_PRIORITY,
            FocusTrackingMode.SUBJECT_TRACKING -> {
                if (isRecording) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                }
            }
            
            FocusTrackingMode.ZONE_FOCUS,
            FocusTrackingMode.WIDE_AREA -> {
                if (supportsFullFocus && !isRecording) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METERING REGION CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    fun buildTapMeteringRegion(
        sensorRect: Rect,
        mappedSensorX: Float,
        mappedSensorY: Float,
        focusWindowRatio: Float
    ): Rect = buildTapMeteringRegion(
        sensorRect = CameraRect(sensorRect.left, sensorRect.top, sensorRect.right, sensorRect.bottom),
        mappedSensorX = mappedSensorX,
        mappedSensorY = mappedSensorY,
        focusWindowRatio = focusWindowRatio
    ).let { Rect(it.left, it.top, it.right, it.bottom) }

    fun buildTapMeteringRegion(
        sensorRect: CameraRect,
        mappedSensorX: Float,
        mappedSensorY: Float,
        focusWindowRatio: Float
    ): CameraRect {
        val clampedX = mappedSensorX.coerceIn(0f, 1f)
        val clampedY = mappedSensorY.coerceIn(0f, 1f)
        val sensorWidth = sensorRect.width.coerceAtLeast(1)
        val sensorHeight = sensorRect.height.coerceAtLeast(1)
        val centerX = (sensorRect.left + clampedX * sensorWidth)
            .toInt()
            .coerceIn(sensorRect.left, sensorRect.right - 1)
        val centerY = (sensorRect.top + clampedY * sensorHeight)
            .toInt()
            .coerceIn(sensorRect.top, sensorRect.bottom - 1)
        val halfWidth = (sensorWidth * focusWindowRatio * 0.5f).toInt().coerceAtLeast(1)
        val halfHeight = (sensorHeight * focusWindowRatio * 0.5f).toInt().coerceAtLeast(1)
        val left = (centerX - halfWidth).coerceIn(sensorRect.left, sensorRect.right - 1)
        val top = (centerY - halfHeight).coerceIn(sensorRect.top, sensorRect.bottom - 1)
        val right = (centerX + halfWidth).coerceIn(left + 1, sensorRect.right)
        val bottom = (centerY + halfHeight).coerceIn(top + 1, sensorRect.bottom)
        return CameraRect(left, top, right, bottom)
    }

    /**
     * Build focus region for face/eye tracking with appropriate size.
     */
    fun buildFaceFocusRegion(
        sensorRect: CameraRect,
        faceLeft: Float,
        faceTop: Float,
        faceRight: Float,
        faceBottom: Float,
        eyePriority: Boolean = true
    ): CameraRect {
        val faceCenterX = (faceLeft + faceRight) * 0.5f
        val faceCenterY = (faceTop + faceBottom) * 0.5f
        val faceWidth = faceRight - faceLeft
        val faceHeight = faceBottom - faceTop
        
        // If eye priority, focus on upper third of face (eye region)
        val effectiveCenterY = if (eyePriority) {
            faceTop + faceHeight * 0.35f
        } else {
            faceCenterY
        }
        
        val baseWindowRatio = if (eyePriority) EYE_FOCUS_WINDOW_RATIO else FACE_FOCUS_WINDOW_RATIO
        val windowRatio = if (strictHighEndEnabled()) {
            baseWindowRatio * STRICT_FOCUS_WINDOW_MULTIPLIER
        } else {
            baseWindowRatio
        }
        
        return buildTapMeteringRegion(
            sensorRect = sensorRect,
            mappedSensorX = faceCenterX,
            mappedSensorY = effectiveCenterY,
            focusWindowRatio = windowRatio
        )
    }

    /**
     * Build zone focus region covering multiple points.
     */
    fun buildZoneFocusRegion(
        sensorRect: CameraRect,
        zones: List<FocusMeteringZone>
    ): CameraRect {
        if (zones.isEmpty()) {
            // Default to center third
            val thirdWidth = sensorRect.width / 3
            val thirdHeight = sensorRect.height / 3
            return CameraRect(
                left = thirdWidth,
                top = thirdHeight,
                right = thirdWidth * 2,
                bottom = thirdHeight * 2
            )
        }
        
        // Compute weighted centroid
        var totalWeight = 0f
        var weightedX = 0f
        var weightedY = 0f
        
        for (zone in zones) {
            totalWeight += zone.weight
            weightedX += zone.centerX * zone.weight
            weightedY += zone.centerY * zone.weight
        }
        
        val centerX = if (totalWeight > 0f) weightedX / totalWeight else 0.5f
        val centerY = if (totalWeight > 0f) weightedY / totalWeight else 0.5f
        
        // Use wide focus window for zones
        val zoneWindowRatio = if (strictHighEndEnabled()) {
            WIDE_FOCUS_WINDOW_RATIO * STRICT_FOCUS_WINDOW_MULTIPLIER
        } else {
            WIDE_FOCUS_WINDOW_RATIO
        }
        return buildTapMeteringRegion(
            sensorRect = sensorRect,
            mappedSensorX = centerX,
            mappedSensorY = centerY,
            focusWindowRatio = zoneWindowRatio
        )
    }

    /**
     * Build spot metering region for precise focus.
     */
    fun buildSpotFocusRegion(
        sensorRect: CameraRect,
        centerX: Float,
        centerY: Float
    ): CameraRect {
        val spotWindowRatio = if (strictHighEndEnabled()) {
            SPOT_FOCUS_WINDOW_RATIO * STRICT_FOCUS_WINDOW_MULTIPLIER
        } else {
            SPOT_FOCUS_WINDOW_RATIO
        }
        return buildTapMeteringRegion(
            sensorRect = sensorRect,
            mappedSensorX = centerX,
            mappedSensorY = centerY,
            focusWindowRatio = spotWindowRatio
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RACK FOCUS & SMOOTH TRANSITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate smooth focus transition step for rack focus effect.
     * Returns next focus distance in diopters.
     */
    fun calculateRackFocusStep(
        currentDiopters: Float,
        targetDiopters: Float,
        speedProfile: FocusSpeedProfile,
        deltaTimeSeconds: Float
    ): Float {
        val strict = strictHighEndEnabled()
        val speed = when (speedProfile) {
            FocusSpeedProfile.INSTANT -> Float.MAX_VALUE
            FocusSpeedProfile.STANDARD -> if (strict) SMOOTH_FOCUS_SPEED * 0.9f else SMOOTH_FOCUS_SPEED
            FocusSpeedProfile.CINEMATIC -> if (strict) CINEMATIC_RACK_SPEED * 0.85f else CINEMATIC_RACK_SPEED
            FocusSpeedProfile.SLOW_RACK -> if (strict) CINEMATIC_RACK_SPEED * 0.4f else CINEMATIC_RACK_SPEED * 0.5f
            FocusSpeedProfile.CUSTOM -> if (strict) SMOOTH_FOCUS_SPEED * 0.9f else SMOOTH_FOCUS_SPEED
        }
        
        val delta = targetDiopters - currentDiopters
        val maxStep = speed * deltaTimeSeconds
        
        return if (abs(delta) <= maxStep) {
            targetDiopters
        } else {
            currentDiopters + if (delta > 0) maxStep else -maxStep
        }
    }

    /**
     * Calculate focus transition with easing for cinematic effect.
     * Uses smooth ease-in-out curve.
     */
    fun calculateEasedFocusTransition(
        startDiopters: Float,
        endDiopters: Float,
        progress: Float // 0.0 to 1.0
    ): Float {
        // Smooth step (ease-in-out) interpolation
        val easedProgress = if (progress < 0.5f) {
            2f * progress * progress
        } else {
            1f - (-2f * progress + 2f).pow(2) / 2f
        }
        
        return startDiopters + (endDiopters - startDiopters) * easedProgress.coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PREDICTIVE FOCUS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Predict next focus position based on subject movement.
     * Uses linear prediction with velocity estimation.
     */
    fun predictNextFocusPosition(
        currentDiopters: Float,
        previousDiopters: Float,
        deltaTimeSeconds: Float,
        predictionTimeSeconds: Float = 0.033f // ~1 frame at 30fps
    ): Float {
        if (deltaTimeSeconds <= 0f) return currentDiopters
        
        val velocity = (currentDiopters - previousDiopters) / deltaTimeSeconds
        val predictionHorizon = if (strictHighEndEnabled()) {
            minOf(predictionTimeSeconds, 0.0167f)
        } else {
            predictionTimeSeconds
        }
        val predictedDiopters = currentDiopters + velocity * predictionHorizon
        
        // Clamp to reasonable range
        return predictedDiopters.coerceIn(MIN_FOCUS_DIOPTERS, 15f)
    }

    /**
     * Determine if focus hunting is occurring and should be suppressed.
     */
    fun isFocusHunting(
        currentDiopters: Float,
        previousDiopters: Float,
        olderDiopters: Float
    ): Boolean {
        val huntingThreshold = if (strictHighEndEnabled()) {
            FOCUS_HUNTING_THRESHOLD * 0.75f
        } else {
            FOCUS_HUNTING_THRESHOLD
        }
        val change1 = currentDiopters - previousDiopters
        val change2 = previousDiopters - olderDiopters
        
        // Hunting = oscillation with small magnitude
        val isOscillating = (change1 * change2) < 0 // Direction change
        val isSmallMagnitude = abs(change1) < huntingThreshold && 
                               abs(change2) < huntingThreshold
        
        return isOscillating && isSmallMagnitude
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEPTH OF FIELD CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate depth of field for given focus parameters.
     * Returns (near limit, far limit) in meters.
     */
    fun calculateDepthOfField(
        focusDistanceMeters: Float,
        focalLengthMm: Float,
        aperture: Float,
        circleOfConfusionMm: Float = 0.005f
    ): Pair<Float, Float> {
        if (focusDistanceMeters <= 0f || focusDistanceMeters.isInfinite()) {
            return Float.MAX_VALUE to Float.MAX_VALUE
        }
        
        val hyperfocal = (focalLengthMm * focalLengthMm) / (aperture * circleOfConfusionMm)
        val hyperfocalMeters = hyperfocal / 1000f
        
        val nearLimit = (hyperfocalMeters * focusDistanceMeters) / 
                        (hyperfocalMeters + (focusDistanceMeters - focalLengthMm / 1000f))
        
        val farLimit = if (focusDistanceMeters >= hyperfocalMeters) {
            Float.POSITIVE_INFINITY
        } else {
            (hyperfocalMeters * focusDistanceMeters) / 
            (hyperfocalMeters - (focusDistanceMeters - focalLengthMm / 1000f))
        }
        
        return nearLimit.coerceAtLeast(0f) to farLimit
    }

    /**
     * Check if subject is within acceptable focus (depth of field).
     */
    fun isWithinDepthOfField(
        subjectDistanceMeters: Float,
        nearLimitMeters: Float,
        farLimitMeters: Float
    ): Boolean {
        return subjectDistanceMeters >= nearLimitMeters && subjectDistanceMeters <= farLimitMeters
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FOCUS ASSIST UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get recommended focus window ratio based on tracking mode.
     */
    fun getRecommendedFocusWindowRatio(trackingMode: FocusTrackingMode): Float {
        val base = when (trackingMode) {
            FocusTrackingMode.SINGLE_POINT -> DEFAULT_FOCUS_WINDOW_RATIO
            FocusTrackingMode.CONTINUOUS_TRACKING -> DEFAULT_FOCUS_WINDOW_RATIO
            FocusTrackingMode.FACE_PRIORITY -> FACE_FOCUS_WINDOW_RATIO
            FocusTrackingMode.SUBJECT_TRACKING -> DEFAULT_FOCUS_WINDOW_RATIO
            FocusTrackingMode.ZONE_FOCUS -> WIDE_FOCUS_WINDOW_RATIO
            FocusTrackingMode.WIDE_AREA -> WIDE_FOCUS_WINDOW_RATIO
        }
        return if (strictHighEndEnabled()) {
            (base * STRICT_FOCUS_WINDOW_MULTIPLIER).coerceAtLeast(EYE_FOCUS_WINDOW_RATIO)
        } else {
            base
        }
    }
}
