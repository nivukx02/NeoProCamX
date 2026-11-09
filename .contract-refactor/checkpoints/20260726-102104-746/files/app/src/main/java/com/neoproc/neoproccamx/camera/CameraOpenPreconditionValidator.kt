package com.neoproc.neoproccamx.camera

/**
 * Policy for validating camera open preconditions
 */
class CameraOpenPreconditionValidator {

    /**
     * Validates preconditions for opening camera
     */
    fun validateOpenPreconditions(cameraId: String): CameraLifecycleOutcome {
        return if (cameraId.isBlank()) {
            CameraLifecycleOutcome.Blocked("No camera selected", retryable = true)
        } else {
            CameraLifecycleOutcome.Proceed
        }
    }
}
