package com.neoproc.neoproccamx.niv99

/**
 * NEO NIV99 Offline Isolation Gate.
 *
 * NIV99 is a full-frame develop/conversion engine. Running its exact-frame metadata join or pixel
 * transform inside a Camera2 callback, preview compositor, or encoder deadline couples offline
 * quality work to live capture cadence. This policy is the fail-closed authority that keeps those
 * domains separate: live paths preserve the camera's original ten-bit/RAW pixels and metadata,
 * while every supported user-visible conversion continues to run NIV99 at full quality.
 */
object Niv99ExecutionBoundaryPolicy {
    const val TECHNOLOGY_ID: String = "NEO_NIV99_OFFLINE_ISOLATION_V1"

    const val LIVE_CAPTURE_METADATA_ENABLED: Boolean = false
    const val LIVE_ANALYSIS_ENABLED: Boolean = false
    const val LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED: Boolean = false
    const val LIVE_RECORDING_PIXEL_PROCESSING_ENABLED: Boolean = false
    const val OFFLINE_CONVERSION_ENABLED: Boolean = true

    const val ANY_LIVE_PROCESSING_ENABLED: Boolean = false

    fun allowsLivePixelProcessing(recordingPath: Boolean): Boolean =
        if (recordingPath) {
            LIVE_RECORDING_PIXEL_PROCESSING_ENABLED
        } else {
            LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED
        }

    fun evidence(): Map<String, String> = linkedMapOf(
        "niv99ExecutionBoundary" to TECHNOLOGY_ID,
        "niv99LiveCaptureMetadataEnabled" to LIVE_CAPTURE_METADATA_ENABLED.toString(),
        "niv99LiveAnalysisEnabled" to LIVE_ANALYSIS_ENABLED.toString(),
        "niv99LivePreviewPixelProcessingEnabled" to
            LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED.toString(),
        "niv99LiveRecordingPixelProcessingEnabled" to
            LIVE_RECORDING_PIXEL_PROCESSING_ENABLED.toString(),
        "niv99OfflineConversionEnabled" to OFFLINE_CONVERSION_ENABLED.toString()
    )
}
