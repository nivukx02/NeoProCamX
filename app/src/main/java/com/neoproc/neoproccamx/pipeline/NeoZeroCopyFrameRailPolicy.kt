package com.neoproc.neoproccamx.pipeline

/**
 * Submission authority for the camera-master AHardwareBuffer path.
 *
 * Camera images are already zero-copy hardware buffers. When no exact live metadata transform is
 * enabled, moving their native submission to another Java worker provides no overlap and can force
 * an EGL driver ownership hand-off. The immediate route keeps acquisition ordering and native
 * submission on one FIFO worker. The bounded overlap route remains available when a future live
 * metadata transform has real preparation work that can overlap the previous GPU submission.
 */
internal object NeoZeroCopyFrameRailPolicy {
    const val TECHNOLOGY_ID: String = "NEO_ZERO_COPY_FRAMERAIL_V1"

    fun route(liveExactMetadataProcessingEnabled: Boolean): NeoFrameRailSubmissionRoute =
        if (liveExactMetadataProcessingEnabled) {
            NeoFrameRailSubmissionRoute.BOUNDED_METADATA_OVERLAP
        } else {
            NeoFrameRailSubmissionRoute.IMMEDIATE_AHARDWAREBUFFER
        }
}

internal enum class NeoFrameRailSubmissionRoute {
    IMMEDIATE_AHARDWAREBUFFER,
    BOUNDED_METADATA_OVERLAP
}
