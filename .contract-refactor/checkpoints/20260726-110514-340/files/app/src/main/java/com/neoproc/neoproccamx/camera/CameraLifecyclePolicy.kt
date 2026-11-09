package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraDevice
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.pow

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END CAMERA LIFECYCLE POLICY
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade camera lifecycle management with robust error recovery,
// thermal management, and seamless multi-camera transitions.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Camera operational state for professional monitoring.
 */
internal enum class CameraOperationalState {
    /** Camera is idle/closed */
    IDLE,
    /** Camera is being opened */
    OPENING,
    /** Camera is active and streaming */
    ACTIVE,
    /** Camera is recording video */
    RECORDING,
    /** Camera is capturing still image */
    CAPTURING,
    /** Camera is in error recovery */
    RECOVERING,
    /** Camera is being closed */
    CLOSING,
    /** Camera encountered fatal error */
    FATAL_ERROR,
    /** Camera is throttled due to thermal */
    THERMAL_THROTTLED
}

/**
 * Error severity classification for recovery strategy.
 */
internal enum class CameraErrorSeverity {
    /** Recoverable error - automatic retry possible */
    RECOVERABLE,
    /** Soft error - user intervention may help */
    SOFT_ERROR,
    /** Hard error - requires app restart or device change */
    HARD_ERROR,
    /** Fatal error - hardware or system failure */
    FATAL
}

/**
 * Thermal management state for sustained performance.
 */
internal enum class ThermalState {
    /** Normal operating temperature */
    NORMAL,
    /** Slightly elevated - monitor closely */
    WARM,
    /** High temperature - reduce quality */
    HOT,
    /** Critical - must reduce load significantly */
    CRITICAL,
    /** Emergency - shutdown required */
    EMERGENCY
}

internal data class CameraReopenPlan(
    val shouldSchedule: Boolean,
    val nextAttemptCount: Int,
    val delayMs: Long = 350L,
    val reduceQuality: Boolean = false
)

/**
 * Extended recovery plan with detailed strategy.
 */
internal data class CameraRecoveryPlan(
    val strategy: RecoveryStrategy,
    val delayMs: Long,
    val nextAttemptCount: Int,
    val shouldReduceQuality: Boolean,
    val shouldSwitchCamera: Boolean,
    val userMessage: String?
)

internal enum class RecoveryStrategy {
    /** Simple retry with same configuration */
    RETRY_SAME,
    /** Retry with reduced quality settings */
    RETRY_REDUCED,
    /** Switch to different camera */
    SWITCH_CAMERA,
    /** Full session rebuild */
    REBUILD_SESSION,
    /** No recovery possible */
    ABORT
}

internal object CameraLifecyclePolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ═══════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Maximum retry attempts for soft errors */
    private const val MAX_SOFT_RETRY_ATTEMPTS = 3
    
    /** Maximum retry attempts for hard errors */
    private const val MAX_HARD_RETRY_ATTEMPTS = 1
    
    /** Base delay between retries (ms) */
    private const val BASE_RETRY_DELAY_MS = 350L
    
    /** Maximum delay between retries (ms) */
    private const val MAX_RETRY_DELAY_MS = 2000L
    
    /** Exponential backoff multiplier */
    private const val BACKOFF_MULTIPLIER = 1.5f
    
    /** Thermal throttle cooldown period (ms) */
    private const val THERMAL_COOLDOWN_MS = 5000L

    // ═══════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════════════

    fun openFailureMessage(
        lockAcquired: Boolean,
        cameraId: String
    ): String? = when {
        !lockAcquired -> "Timeout acquiring camera lock"
        cameraId.isBlank() -> "No camera available"
        else -> null
    }

    fun shouldRetryOnError(error: Int): Boolean =
        error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
            error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
            error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ||
            error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE

    fun shouldUseExecutorCameraOpen(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.P

    fun shouldPostCameraCallback(
        callbacksEnabled: Boolean,
        handlerPresent: Boolean,
        threadAlive: Boolean
    ): Boolean =
        callbacksEnabled && handlerPresent && threadAlive

    /**
     * Classify error severity for recovery strategy selection.
     */
    fun classifyErrorSeverity(error: Int): CameraErrorSeverity {
        return when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> 
                CameraErrorSeverity.RECOVERABLE
            
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> 
                CameraErrorSeverity.SOFT_ERROR
            
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> 
                CameraErrorSeverity.HARD_ERROR
            
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> 
                CameraErrorSeverity.FATAL
            
            else -> CameraErrorSeverity.SOFT_ERROR
        }
    }

    /**
     * Get user-friendly error message for display.
     */
    fun getErrorMessage(error: Int): String {
        return when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> 
                "Camera is in use by another application"
            
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> 
                "Maximum number of cameras already in use"
            
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> 
                "Camera has been disabled"
            
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> 
                "Camera encountered a device error"
            
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> 
                "Camera service error - please restart the app"
            
            else -> "Unknown camera error ($error)"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECOVERY PLANNING
    // ═══════════════════════════════════════════════════════════════════════════

    fun resolveReopenPlan(
        currentAttempts: Int,
        maxAttempts: Int,
        hasPreviewSurface: Boolean,
        hasReadyCallback: Boolean,
        hasErrorCallback: Boolean,
        isClosing: Boolean,
        hasOpenDevice: Boolean
    ): CameraReopenPlan {
        if (!hasPreviewSurface || !hasReadyCallback || !hasErrorCallback || isClosing || hasOpenDevice) {
            return CameraReopenPlan(shouldSchedule = false, nextAttemptCount = currentAttempts)
        }
        if (currentAttempts >= maxAttempts) {
            return CameraReopenPlan(shouldSchedule = false, nextAttemptCount = currentAttempts)
        }
        
        // Calculate exponential backoff delay
        val delay = (BASE_RETRY_DELAY_MS * BACKOFF_MULTIPLIER.toDouble().pow(currentAttempts.toDouble()))
            .toLong()
            .coerceAtMost(MAX_RETRY_DELAY_MS)
        val strictDelay = if (strictHighEndEnabled()) {
            (delay * 0.9f).toLong().coerceAtLeast(250L)
        } else {
            delay
        }
        
        return CameraReopenPlan(
            shouldSchedule = true,
            nextAttemptCount = currentAttempts + 1,
            delayMs = strictDelay,
            reduceQuality = if (strictHighEndEnabled()) currentAttempts > 1 else currentAttempts > 0
        )
    }

    /**
     * Resolve comprehensive recovery plan based on error and state.
     */
    fun resolveRecoveryPlan(
        error: Int,
        currentAttempts: Int,
        thermalState: ThermalState = ThermalState.NORMAL,
        hasAlternateCamera: Boolean = true
    ): CameraRecoveryPlan {
        val strictHighEnd = strictHighEndEnabled()
        val severity = classifyErrorSeverity(error)
        
        // Check thermal state first
        if (thermalState == ThermalState.EMERGENCY) {
            return CameraRecoveryPlan(
                strategy = RecoveryStrategy.ABORT,
                delayMs = THERMAL_COOLDOWN_MS,
                nextAttemptCount = 0,
                shouldReduceQuality = false,
                shouldSwitchCamera = false,
                userMessage = "Device is too hot. Please wait for it to cool down."
            )
        }
        
        if (thermalState == ThermalState.CRITICAL) {
            return CameraRecoveryPlan(
                strategy = RecoveryStrategy.RETRY_REDUCED,
                delayMs = THERMAL_COOLDOWN_MS / 2,
                nextAttemptCount = currentAttempts + 1,
                shouldReduceQuality = true,
                shouldSwitchCamera = false,
                userMessage = "Reducing quality due to device temperature"
            )
        }
        
        return when (severity) {
            CameraErrorSeverity.RECOVERABLE -> {
                if (currentAttempts < MAX_SOFT_RETRY_ATTEMPTS) {
                    CameraRecoveryPlan(
                        strategy = RecoveryStrategy.RETRY_SAME,
                        delayMs = (BASE_RETRY_DELAY_MS * (currentAttempts + 1) *
                            if (strictHighEnd) 0.85f else 1f).toLong(),
                        nextAttemptCount = currentAttempts + 1,
                        shouldReduceQuality = strictHighEnd && currentAttempts >= 2,
                        shouldSwitchCamera = false,
                        userMessage = null
                    )
                } else if (hasAlternateCamera) {
                    CameraRecoveryPlan(
                        strategy = RecoveryStrategy.SWITCH_CAMERA,
                        delayMs = BASE_RETRY_DELAY_MS,
                        nextAttemptCount = 0,
                        shouldReduceQuality = false,
                        shouldSwitchCamera = true,
                        userMessage = "Switching to alternate camera"
                    )
                } else {
                    CameraRecoveryPlan(
                        strategy = RecoveryStrategy.ABORT,
                        delayMs = 0,
                        nextAttemptCount = currentAttempts,
                        shouldReduceQuality = false,
                        shouldSwitchCamera = false,
                        userMessage = getErrorMessage(error)
                    )
                }
            }
            
            CameraErrorSeverity.SOFT_ERROR -> {
                if (currentAttempts < MAX_HARD_RETRY_ATTEMPTS) {
                    CameraRecoveryPlan(
                        strategy = RecoveryStrategy.REBUILD_SESSION,
                        delayMs = ((BASE_RETRY_DELAY_MS * 2) *
                            if (strictHighEnd) 0.85f else 1f).toLong(),
                        nextAttemptCount = currentAttempts + 1,
                        shouldReduceQuality = true,
                        shouldSwitchCamera = false,
                        userMessage = null
                    )
                } else {
                    CameraRecoveryPlan(
                        strategy = RecoveryStrategy.ABORT,
                        delayMs = 0,
                        nextAttemptCount = currentAttempts,
                        shouldReduceQuality = false,
                        shouldSwitchCamera = false,
                        userMessage = getErrorMessage(error)
                    )
                }
            }
            
            CameraErrorSeverity.HARD_ERROR -> {
                CameraRecoveryPlan(
                    strategy = RecoveryStrategy.ABORT,
                    delayMs = 0,
                    nextAttemptCount = currentAttempts,
                    shouldReduceQuality = false,
                    shouldSwitchCamera = false,
                    userMessage = getErrorMessage(error)
                )
            }
            
            CameraErrorSeverity.FATAL -> {
                CameraRecoveryPlan(
                    strategy = RecoveryStrategy.ABORT,
                    delayMs = 0,
                    nextAttemptCount = currentAttempts,
                    shouldReduceQuality = false,
                    shouldSwitchCamera = false,
                    userMessage = "Camera is unavailable. ${getErrorMessage(error)}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CAMERA SWITCHING
    // ═══════════════════════════════════════════════════════════════════════════

    fun nextFacing(currentFacing: Int): Int =
        if (currentFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }

    /**
     * Calculate optimal camera switch delay for smooth transition.
     */
    fun calculateSwitchDelay(
        isRecording: Boolean,
        hasMultipleCameras: Boolean
    ): Long {
        val baseDelay = when {
            isRecording -> 500L // Longer delay to ensure clean video end
            hasMultipleCameras -> 200L // Quick switch for responsive UI
            else -> 350L // Standard delay
        }
        return if (strictHighEndEnabled()) {
            (baseDelay * 0.85f).toLong().coerceAtLeast(150L)
        } else {
            baseDelay
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // THERMAL MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Determine if camera should throttle based on thermal state.
     */
    fun shouldThrottleForThermal(state: ThermalState): Boolean {
        return state == ThermalState.HOT || 
               state == ThermalState.CRITICAL || 
               state == ThermalState.EMERGENCY
    }

    /**
     * Get quality reduction factor for thermal management.
     * Returns value 0.0-1.0 where 1.0 is full quality.
     */
    fun thermalQualityFactor(state: ThermalState): Float {
        val strictHighEnd = strictHighEndEnabled()
        return when (state) {
            ThermalState.NORMAL -> 1.0f
            ThermalState.WARM -> if (strictHighEnd) 0.97f else 0.95f
            ThermalState.HOT -> if (strictHighEnd) 0.85f else 0.8f
            ThermalState.CRITICAL -> if (strictHighEnd) 0.68f else 0.6f
            ThermalState.EMERGENCY -> 0.4f
        }
    }

    /**
     * Calculate recommended FPS ceiling for thermal state.
     */
    fun thermalFpsCeiling(state: ThermalState, maxFps: Int): Int {
        val factor = thermalQualityFactor(state)
        return (maxFps * factor).toInt().coerceAtLeast(24)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION HEALTH MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Determine if session needs refresh based on health metrics.
     */
    fun shouldRefreshSession(
        consecutiveFrameDrops: Int,
        lastSuccessfulCaptureMs: Long,
        currentTimeMs: Long,
        isRecording: Boolean
    ): Boolean {
        // Threshold is more sensitive during recording
        val dropThreshold = when {
            isRecording && strictHighEndEnabled() -> 6
            isRecording -> 5
            strictHighEndEnabled() -> 12
            else -> 10
        }
        val staleThreshold = when {
            isRecording && strictHighEndEnabled() -> 1200L
            isRecording -> 1000L
            strictHighEndEnabled() -> 3500L
            else -> 3000L
        }
        
        return consecutiveFrameDrops >= dropThreshold ||
               (currentTimeMs - lastSuccessfulCaptureMs) > staleThreshold
    }

    /**
     * Calculate session health score (0-100).
     */
    fun calculateSessionHealth(
        averageFps: Float,
        targetFps: Int,
        frameDropRate: Float,
        thermalState: ThermalState
    ): Int {
        val strictHighEnd = strictHighEndEnabled()
        var score = 100
        
        // FPS performance (40 points)
        val fpsRatio = (averageFps / targetFps.coerceAtLeast(1)).coerceIn(0f, 1f)
        score -= ((1f - fpsRatio) * if (strictHighEnd) 35 else 40).toInt()
        
        // Frame drop penalty (30 points)
        score -= (frameDropRate * if (strictHighEnd) 25 else 30).toInt().coerceAtMost(30)
        
        // Thermal penalty (30 points)
        score -= when (thermalState) {
            ThermalState.NORMAL -> 0
            ThermalState.WARM -> 5
            ThermalState.HOT -> 15
            ThermalState.CRITICAL -> 25
            ThermalState.EMERGENCY -> 30
        }
        
        return score.coerceIn(0, 100)
    }
}
