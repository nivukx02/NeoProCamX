package com.neoproc.neoproccamx.ui

internal object PreviewLutRequestPolicy {

    fun build(
        uiMode: AppMode,
        deliveryState: VideoFormatDeliveryState,
        previewLookState: VideoFormatPreviewLookState,
        recordingActive: Boolean,
        previewFpsHint: Float,
        colorProfile: String,
        lutEnabled: Boolean,
        lutProfile: String,
        customLutPath: String?,
        saturation: Float,
        contrast: Float,
        brightness: Float,
        hueShift: Float,
        highlights: Float,
        shadows: Float
    ): PreviewLutRequest {
        val previewFpsBucket = when {
            previewFpsHint >= 54f -> 60
            previewFpsHint >= 44f -> 48
            previewFpsHint >= 34f -> 36
            else -> 30
        }
        val colorCriticalPreview =
            deliveryState.truthfulColorDelivery ||
                previewLookState.hdrEnabled ||
                previewLookState.logEnabled ||
                previewLookState.rawEnabled ||
                deliveryState.effectiveHdrMode != "OFF" ||
                deliveryState.logVideoActive ||
                deliveryState.rawVideoActive
        val effectiveLutEnabled = lutEnabled && !colorCriticalPreview
        val effectiveCustomLutPath = if (effectiveLutEnabled) customLutPath else null
        val neutralVideoPreview =
            VideoRenderProfileQualityHintPolicy.isVideoSurface(uiMode) &&
                deliveryState.neutralPreview &&
                !previewLookState.logEnabled &&
                !previewLookState.hdrEnabled &&
                !effectiveLutEnabled

        return PreviewLutRequest(
            neutralVideoPreview = neutralVideoPreview,
            uiMode = uiMode,
            recordingActive = recordingActive,
            previewFpsBucket = previewFpsBucket,
            rawVideoActive = deliveryState.rawVideoActive,
            hdrEnabled = previewLookState.hdrEnabled,
            colorProfile = colorProfile,
            lutEnabled = effectiveLutEnabled,
            lutProfile = lutProfile,
            customLutPath = effectiveCustomLutPath,
            logEnabled = previewLookState.logEnabled,
            effectiveHdrMode = deliveryState.effectiveHdrMode,
            effectiveColorSpace = deliveryState.effectiveColorSpace,
            truthfulColorDelivery = deliveryState.truthfulColorDelivery,
            cinemaModeLabel = deliveryState.cinemaModeLabel,
            saturation = saturation,
            contrast = contrast,
            brightness = brightness,
            hueShift = hueShift,
            highlights = highlights,
            shadows = shadows
        )
    }
}
