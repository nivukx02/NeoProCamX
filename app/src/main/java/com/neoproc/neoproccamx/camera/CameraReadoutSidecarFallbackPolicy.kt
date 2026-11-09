package com.neoproc.neoproccamx.camera

internal enum class CameraReadoutSidecarMode {
    MAXIMUM,
    ENCODER_MATCHED,
    DISABLED
}

internal data class CameraReadoutSidecarFallbackDecision(
    val shouldRetry: Boolean,
    val nextMode: CameraReadoutSidecarMode?,
    val warning: String?
)

internal object CameraReadoutSidecarFallbackPolicy {
    fun resolve(
        currentMode: CameraReadoutSidecarMode,
        recordingActive: Boolean,
        uncompressedReadoutRecording: Boolean = false,
        rawVideoCaptureEnabled: Boolean,
        hasYuvReader: Boolean,
        recordingReadoutOverrideSize: CameraStreamSize?,
        recordingEncoderTargetSize: CameraStreamSize?,
        supportedYuvSizes: List<CameraStreamSize>
    ): CameraReadoutSidecarFallbackDecision {
        if (!recordingActive || uncompressedReadoutRecording || rawVideoCaptureEnabled || !hasYuvReader) {
            return noRetry()
        }

        return when (currentMode) {
            CameraReadoutSidecarMode.MAXIMUM -> {
                val encoderMatchedAvailable = recordingEncoderTargetSize != null &&
                    supportedYuvSizes.any { it.sameDimensions(recordingEncoderTargetSize) } &&
                    recordingReadoutOverrideSize?.sameDimensions(recordingEncoderTargetSize) != true
                if (encoderMatchedAvailable) {
                    retry(
                        nextMode = CameraReadoutSidecarMode.ENCODER_MATCHED,
                        warning = CameraPipelineDiagnostics.readoutSidecarSessionFallbackWarning(
                            "max sensor YUV sidecar",
                            "encoder-matched YUV sidecar"
                        )
                    )
                } else {
                    retry(
                        nextMode = CameraReadoutSidecarMode.DISABLED,
                        warning = CameraPipelineDiagnostics.readoutSidecarSessionFallbackWarning(
                            "max sensor YUV sidecar",
                            "encoder-only"
                        )
                    )
                }
            }
            CameraReadoutSidecarMode.ENCODER_MATCHED -> retry(
                nextMode = CameraReadoutSidecarMode.DISABLED,
                warning = CameraPipelineDiagnostics.readoutSidecarSessionFallbackWarning(
                    "encoder-matched YUV sidecar",
                    "encoder-only"
                )
            )
            CameraReadoutSidecarMode.DISABLED -> noRetry()
        }
    }

    private fun retry(
        nextMode: CameraReadoutSidecarMode,
        warning: String
    ): CameraReadoutSidecarFallbackDecision =
        CameraReadoutSidecarFallbackDecision(
            shouldRetry = true,
            nextMode = nextMode,
            warning = warning
        )

    private fun noRetry(): CameraReadoutSidecarFallbackDecision =
        CameraReadoutSidecarFallbackDecision(
            shouldRetry = false,
            nextMode = null,
            warning = null
        )

    private fun CameraStreamSize.sameDimensions(other: CameraStreamSize): Boolean =
        (width == other.width && height == other.height) ||
            (width == other.height && height == other.width)
}
