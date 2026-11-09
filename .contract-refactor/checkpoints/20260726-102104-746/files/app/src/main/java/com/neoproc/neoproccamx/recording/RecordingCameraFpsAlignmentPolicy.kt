package com.neoproc.neoproccamx.recording

internal data class RecordingCameraFpsAlignmentPlan(
    val encoderFps: Int,
    val previewFps: Int,
    val alignedToCamera: Boolean
)

internal object RecordingCameraFpsAlignmentPolicy {
    private const val MIN_REALTIME_FPS = 24

    fun resolve(
        requestedEncoderFps: Int,
        appliedCameraFps: Int,
        videoModeActive: Boolean
    ): RecordingCameraFpsAlignmentPlan {
        val requested = requestedEncoderFps.coerceAtLeast(MIN_REALTIME_FPS)
        if (!videoModeActive || appliedCameraFps <= 0) {
            return RecordingCameraFpsAlignmentPlan(
                encoderFps = requested,
                previewFps = requested,
                alignedToCamera = false
            )
        }

        val applied = appliedCameraFps.coerceAtLeast(MIN_REALTIME_FPS)
        return RecordingCameraFpsAlignmentPlan(
            encoderFps = applied,
            previewFps = applied,
            alignedToCamera = applied != requested
        )
    }
}
