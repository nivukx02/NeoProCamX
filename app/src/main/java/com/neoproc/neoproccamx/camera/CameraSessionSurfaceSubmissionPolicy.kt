package com.neoproc.neoproccamx.camera

/** Final, side-effect-free admission check immediately before CameraDevice session submission. */
internal object CameraSessionSurfaceSubmissionPolicy {
    enum class Rejection {
        NONE,
        CAMERA_CLOSING,
        STALE_CAMERA_DEVICE,
        STALE_SESSION_GENERATION,
        SURFACE_OWNERSHIP_CHANGED,
        SURFACE_SET_CHANGED,
        SESSION_CONFIGURATION_CHANGED,
        INVALID_OR_ABANDONED_SURFACE
    }

    data class Decision(
        val shouldSubmit: Boolean,
        val rejection: Rejection
    )

    fun evaluate(
        cameraClosing: Boolean,
        cameraDeviceCurrent: Boolean,
        sessionGenerationCurrent: Boolean,
        surfaceOwnershipEpochCurrent: Boolean,
        surfaceIdentitySetCurrent: Boolean,
        sessionConfigurationCurrent: Boolean,
        allSurfacesValid: Boolean
    ): Decision {
        val rejection = when {
            cameraClosing -> Rejection.CAMERA_CLOSING
            !cameraDeviceCurrent -> Rejection.STALE_CAMERA_DEVICE
            !sessionGenerationCurrent -> Rejection.STALE_SESSION_GENERATION
            !surfaceOwnershipEpochCurrent -> Rejection.SURFACE_OWNERSHIP_CHANGED
            !surfaceIdentitySetCurrent -> Rejection.SURFACE_SET_CHANGED
            !sessionConfigurationCurrent -> Rejection.SESSION_CONFIGURATION_CHANGED
            !allSurfacesValid -> Rejection.INVALID_OR_ABANDONED_SURFACE
            else -> Rejection.NONE
        }
        return Decision(
            shouldSubmit = rejection == Rejection.NONE,
            rejection = rejection
        )
    }

    fun isRecoverableSurfaceRace(rejection: Rejection): Boolean = when (rejection) {
        Rejection.SURFACE_OWNERSHIP_CHANGED,
        Rejection.SURFACE_SET_CHANGED,
        Rejection.SESSION_CONFIGURATION_CHANGED,
        Rejection.INVALID_OR_ABANDONED_SURFACE -> true
        else -> false
    }

    fun isSurfaceLifecycleFailure(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        if (!message.contains("surface")) return false
        return message.contains("abandon") ||
            message.contains("released") ||
            message.contains("invalid") ||
            message.contains("disconnect")
    }
}
