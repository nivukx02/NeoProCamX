package com.neoproc.neoproccamx.camera

internal data class CameraSessionCreateFailureDecision(
    val shouldRecoverCamera: Boolean,
    val shouldIgnoreFailure: Boolean,
    val message: String
)

internal object CameraSessionCreateFailurePolicy {

    fun resolve(
        error: IllegalStateException,
        sessionGenerationCurrent: Boolean = true,
        cameraDeviceStillCurrent: Boolean = true,
        closingCamera: Boolean = false
    ): CameraSessionCreateFailureDecision {
        val rawMessage = error.message?.trim().orEmpty()
        val message = if (rawMessage.isEmpty()) {
            "Session create failed"
        } else {
            "Session create failed: $rawMessage"
        }
        val recoverableIllegalState = isRecoverableIllegalState(rawMessage)
        val staleClosedDevice =
            recoverableIllegalState &&
                (!sessionGenerationCurrent || !cameraDeviceStillCurrent || closingCamera)
        return CameraSessionCreateFailureDecision(
            shouldRecoverCamera = recoverableIllegalState && !staleClosedDevice,
            shouldIgnoreFailure = staleClosedDevice,
            message = message
        )
    }

    private fun isRecoverableIllegalState(message: String): Boolean {
        if (message.isBlank()) return false
        val normalized = message.lowercase()
        return normalized.contains("already closed") ||
            normalized.contains("camera device was closed") ||
            normalized.contains("device was closed") ||
            normalized.contains("camera has been closed")
    }
}
