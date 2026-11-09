package com.neoproc.neoproccamx.ui

internal data class CameraDockTranslations(
    val statusTranslationY: Float,
    val modeTranslationY: Float,
    val captureTranslationY: Float
)

internal object CameraDockLayoutPolicy {
    fun resolveAnchoredTranslations(
        previewTopPx: Float,
        previewBottomPx: Float,
        statusTopPx: Float,
        statusHeightPx: Float,
        modeTopPx: Float,
        modeHeightPx: Float,
        captureTopPx: Float,
        captureHeightPx: Float,
        viewportHeightPx: Float,
        statusPreviewOverlapPx: Float,
        previewModeGapPx: Float,
        modeCaptureGapPx: Float
    ): CameraDockTranslations {
        val desiredStatusTop = (previewTopPx - statusHeightPx + statusPreviewOverlapPx)
            .coerceAtLeast(0f)
        val unboundedModeTop = previewBottomPx + previewModeGapPx
        val unboundedCaptureTop = unboundedModeTop + modeHeightPx + modeCaptureGapPx
        val lowerStackOverflow = (unboundedCaptureTop + captureHeightPx - viewportHeightPx)
            .coerceAtLeast(0f)
        val desiredModeTop = unboundedModeTop - lowerStackOverflow
        val desiredCaptureTop = unboundedCaptureTop - lowerStackOverflow

        return CameraDockTranslations(
            statusTranslationY = desiredStatusTop - statusTopPx,
            modeTranslationY = desiredModeTop - modeTopPx,
            captureTranslationY = desiredCaptureTop - captureTopPx
        )
    }

    fun captureRowTranslationY(
        controlsVisible: Boolean,
        modeRailHeightPx: Float
    ): Float =
        0f

    fun modeRailTranslationY(
        controlsVisible: Boolean,
        modeRailHeightPx: Float
    ): Float =
        0f
}
