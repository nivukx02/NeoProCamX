package com.neoproc.neoproccamx.camera

internal object CameraReadoutSidecarPerformancePolicy {
    @Suppress("UNUSED_PARAMETER")
    fun resolvePreferredMode(
        targetFps: Int,
        uncompressedReadoutRecording: Boolean = false,
        rawVideoCaptureEnabled: Boolean,
        recordingReadoutOverrideSize: CameraStreamSize?,
        recordingEncoderTargetSize: CameraStreamSize?,
        supportedYuvSizes: List<CameraStreamSize>
    ): CameraReadoutSidecarMode {
        if (uncompressedReadoutRecording) return CameraReadoutSidecarMode.MAXIMUM
        if (rawVideoCaptureEnabled) return CameraReadoutSidecarMode.MAXIMUM
        return CameraReadoutSidecarMode.DISABLED
    }
}
