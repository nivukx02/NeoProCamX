package com.neoproc.neoproccamx.ui

internal data class VideoFormatPreviewLookState(
    val logEnabled: Boolean = false,
    val hdrEnabled: Boolean = false,
    val hdrMode: String = "OFF",
    val rawEnabled: Boolean = false,
    val displayReferredMonitoring: Boolean = false,
    val previewKind: VideoPipelinePreviewKind = VideoPipelinePreviewKind.SDR,
    val toneMap: VideoPipelineToneMap = VideoPipelineToneMap.REC709_NEUTRAL
)

internal object VideoFormatPreviewLookPolicy {
    /**
     * Resolves the preview look state from the active delivery configuration.
     *
     * RAW preview uses a dedicated monitor transform so the live image is
     * visible, while saved RAW delivery stays sensor-original. LOG preview keeps
     * the selected log transfer visible instead of applying the monitor look;
     * HDR uses a display-referred monitor transform for the live preview while
     * the encoder path keeps the selected original transfer curve.
     */
    fun resolve(deliveryState: VideoFormatDeliveryState): VideoFormatPreviewLookState {
        return when {
            deliveryState.rawVideoActive -> VideoFormatPreviewLookState(
                rawEnabled = true,
                displayReferredMonitoring = true,
                previewKind = VideoPipelinePreviewKind.RAW_SENSOR,
                toneMap = VideoPipelineToneMap.RAW_LINEAR_MONITOR
            )
            deliveryState.logVideoActive -> VideoFormatPreviewLookState(
                logEnabled = true,
                displayReferredMonitoring = false,
                previewKind = VideoPipelinePreviewKind.LOG_10BIT,
                toneMap = VideoPipelineToneMap.LOG_ORIGINAL
            )
            deliveryState.effectiveHdrMode != "OFF" -> VideoFormatPreviewLookState(
                hdrEnabled = true,
                hdrMode = deliveryState.effectiveHdrMode,
                displayReferredMonitoring = true,
                previewKind = VideoPipelinePreviewKind.HDR_10BIT,
                toneMap = if (deliveryState.effectiveHdrMode == "HLG") {
                    VideoPipelineToneMap.BT2100_HLG
                } else {
                    VideoPipelineToneMap.BT2100_PQ
                }
            )
            deliveryState.pipelineContract.toneMap == VideoPipelineToneMap.WIDE_GAMUT_SDR -> VideoFormatPreviewLookState(
                displayReferredMonitoring = true,
                previewKind = VideoPipelinePreviewKind.SDR,
                toneMap = VideoPipelineToneMap.WIDE_GAMUT_SDR
            )
            else -> VideoFormatPreviewLookState()
        }
    }
}
