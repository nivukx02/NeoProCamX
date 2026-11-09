package com.neoproc.neoproccamx.camera

internal data class CameraPreviewStartFailureDecision(
    val shouldIgnoreFailure: Boolean,
    val shouldSurfaceError: Boolean,
    val message: String
)

internal object CameraPreviewStartFailurePolicy {

    fun resolve(
        error: IllegalStateException,
        sessionGenerationCurrent: Boolean = true,
        sessionStillCurrent: Boolean = true,
        cameraDeviceStillCurrent: Boolean = true,
        closingCamera: Boolean = false
    ): CameraPreviewStartFailureDecision {
        val rawMessage = error.message?.trim().orEmpty()
        val message = if (rawMessage.isEmpty()) {
            "Preview start failed"
        } else {
            "Preview start failed: $rawMessage"
        }
        val closedDeviceFailure = isClosedLifecycleFailure(rawMessage)
        val staleLifecycleFailure =
            closedDeviceFailure &&
                (!sessionGenerationCurrent || !sessionStillCurrent || !cameraDeviceStillCurrent || closingCamera)
        return CameraPreviewStartFailureDecision(
            shouldIgnoreFailure = staleLifecycleFailure,
            shouldSurfaceError = !closedDeviceFailure,
            message = message
        )
    }

    fun isUnconfiguredSurfaceFailure(error: Throwable): Boolean {
        val normalized = error.message?.lowercase().orEmpty()
        return normalized.contains("unconfigured") &&
            normalized.contains("surface")
    }

    private fun isClosedLifecycleFailure(message: String): Boolean {
        if (message.isBlank()) return false
        val normalized = message.lowercase()
        return normalized.contains("already closed") ||
            normalized.contains("camera device was closed") ||
            normalized.contains("device was closed") ||
            normalized.contains("camera has been closed") ||
            normalized.contains("session has been closed") ||
            normalized.contains("session was closed") ||
            normalized.contains("session is closed")
    }
}
