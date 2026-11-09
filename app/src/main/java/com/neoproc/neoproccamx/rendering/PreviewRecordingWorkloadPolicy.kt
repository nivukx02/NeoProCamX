package com.neoproc.neoproccamx.rendering

/**
 * Defines which visible-monitor stages may contend with the recording GPU queue.
 *
 * Recording priority never changes the camera stream, encoder graph, resolution, bit depth,
 * bitrate, colour transform, creative LUT, or recorded-pixel enhancement. It only replaces the
 * monitor's optional multi-sample spatial and FP16 temporal passes with its single-sample 10-bit
 * colour-correct presentation while a recording is active. The SurfaceTexture is still consumed
 * and presented for every available frame, preventing its BufferQueue from stalling Camera2.
 */
internal data class PreviewRecordingWorkloadDecision(
    val waitForExactMetadata: Boolean,
    val applySpatialEnhancement: Boolean,
    val applyTemporalFusion: Boolean,
    val applyWhiteBalance: Boolean,
    val applyCreativeLut: Boolean,
    val presentEveryAvailableFrame: Boolean
)

internal object PreviewRecordingWorkloadPolicy {
    private val INTERACTIVE = PreviewRecordingWorkloadDecision(
        waitForExactMetadata = true,
        applySpatialEnhancement = true,
        applyTemporalFusion = true,
        applyWhiteBalance = true,
        applyCreativeLut = true,
        presentEveryAvailableFrame = true
    )

    private val RECORDING_PRIORITY = PreviewRecordingWorkloadDecision(
        waitForExactMetadata = false,
        applySpatialEnhancement = false,
        applyTemporalFusion = false,
        applyWhiteBalance = true,
        applyCreativeLut = true,
        presentEveryAvailableFrame = true
    )

    fun resolve(recordingActive: Boolean): PreviewRecordingWorkloadDecision =
        if (recordingActive) RECORDING_PRIORITY else INTERACTIVE
}
