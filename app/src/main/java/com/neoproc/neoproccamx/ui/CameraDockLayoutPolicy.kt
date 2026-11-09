package com.neoproc.neoproccamx.ui

import kotlin.math.abs

internal data class CameraDockTranslations(
    val statusTranslationY: Float,
    val modeTranslationY: Float,
    val captureTranslationY: Float
)

internal object CameraDockLayoutPolicy {
    private const val SQUARE_ASPECT = 1f
    private const val ASPECT_EPSILON = 0.01f

    /**
     * Resolves the lower dock's preview-edge anchor without allowing a square frame to grow behind
     * the controls. Wider preview choices retain the established 4:3 dock baseline, while 1:1 uses
     * its real lower edge so the mode rail and capture dock remain outside the visible image.
     */
    fun lowerDockAnchorBottom(
        previewBottomPx: Float,
        fourThreeBottomPx: Float,
        targetAspect: Float
    ): Float =
        if (abs(targetAspect - SQUARE_ASPECT) <= ASPECT_EPSILON) {
            previewBottomPx
        } else {
            minOf(previewBottomPx, fourThreeBottomPx)
        }

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
