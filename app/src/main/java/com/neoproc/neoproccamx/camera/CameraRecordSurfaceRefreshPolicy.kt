package com.neoproc.neoproccamx.camera

internal object CameraRecordSurfaceRefreshPolicy {
    data class Decision(
        val refreshSessionImmediately: Boolean,
        val clearRecordingEncoderTargets: Boolean
    )

    fun resolve(
        surfaceAttached: Boolean,
        refreshSessionRequested: Boolean
    ): Decision {
        return Decision(
            refreshSessionImmediately = surfaceAttached && refreshSessionRequested,
            clearRecordingEncoderTargets = !surfaceAttached
        )
    }
}
