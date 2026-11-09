package com.neoproc.neoproccamx.camera

/**
 * Coordinates camera-open preconditions and retry behavior.
 */
object CameraSessionLifecycleCoordinator {
    private const val DEFAULT_MAX_RETRY_COUNT = 3
    private const val MAX_BACKOFF_SHIFT = 8

    fun validateOpenPreconditions(
        hasCameraPermission: Boolean,
        lockAcquired: Boolean,
        currentCameraId: String
    ): CameraLifecycleOutcome {
        if (!hasCameraPermission) {
            return CameraLifecycleOutcome.PermissionDenied
        }
        if (!lockAcquired) {
            return CameraLifecycleOutcome.Blocked(
                reason = "Camera lock not acquired",
                retryable = true
            )
        }
        if (currentCameraId.isBlank()) {
            return CameraLifecycleOutcome.Blocked(
                reason = "No camera selected",
                retryable = true
            )
        }
        return CameraLifecycleOutcome.Proceed
    }

    fun shouldRetryAttempt(attempt: Int, maxAttempts: Int = DEFAULT_MAX_RETRY_COUNT): Boolean =
        attempt + 1 < maxAttempts

    fun exponentialBackoffMs(attempt: Int, baseDelayMs: Long = 1_000L): Long {
        val shift = attempt.coerceIn(0, MAX_BACKOFF_SHIFT)
        return baseDelayMs * (1L shl shift)
    }

    fun shouldRebuildSession(failureCount: Int, maxFailures: Int = 5): Boolean =
        failureCount < maxFailures
}

sealed class CameraLifecycleOutcome {
    object Proceed : CameraLifecycleOutcome()
    data class Blocked(val reason: String, val retryable: Boolean = false) : CameraLifecycleOutcome()
    object Success : CameraLifecycleOutcome()
    object InvalidState : CameraLifecycleOutcome()
    data class RetryableFailure(val reason: String) : CameraLifecycleOutcome()
    data class PermanentFailure(val reason: String) : CameraLifecycleOutcome()
    object Timeout : CameraLifecycleOutcome()
    object PermissionDenied : CameraLifecycleOutcome()
    object HardwareUnavailable : CameraLifecycleOutcome()
}
