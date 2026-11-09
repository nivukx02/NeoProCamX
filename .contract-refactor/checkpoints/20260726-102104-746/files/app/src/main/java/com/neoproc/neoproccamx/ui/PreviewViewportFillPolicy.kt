package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * Preview viewport policy for aspect-accurate framing.
 *
 * The preview should respect the sensor-derived AUTO aspect instead of forcing
 * every mode into fullscreen edge-to-edge presentation.
 */
internal object PreviewViewportFillPolicy {

    @Suppress("UNUSED_PARAMETER")
    fun shouldFillViewport(
        appMode: AppMode,
        previewAspectRatio: PreviewAspectRatio
    ): Boolean {
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun shouldFillViewport(
        appMode: AppMode,
        previewAspectRatio: PreviewAspectRatio,
        isRecording: Boolean,
        strictHighEnd: Boolean = HighEndQualityPolicy.strictHighEndOnly
    ): Boolean {
        return false
    }

    fun resolveViewportFill(
        appMode: AppMode,
        previewAspectRatio: PreviewAspectRatio,
        isRecording: Boolean = false
    ): ViewportFillResult {
        val shouldFill = shouldFillViewport(appMode, previewAspectRatio, isRecording)
        val barMode = if (shouldFill) {
            BarMode.NONE
        } else {
            resolveBarMode(previewAspectRatio)
        }
        return ViewportFillResult(
            fillViewport = shouldFill,
            barMode = barMode,
            aspectRatioLabel = previewAspectRatio.label,
            isAccurateFraming = !shouldFill
        )
    }

    private fun resolveBarMode(aspect: PreviewAspectRatio): BarMode {
        val ratio = aspect.outputRatio() ?: return BarMode.NONE
        val displayRatio = 16f / 9f

        return when {
            ratio > displayRatio + 0.05f -> BarMode.LETTERBOX
            ratio < displayRatio - 0.05f -> BarMode.PILLARBOX
            else -> BarMode.NONE
        }
    }

    enum class BarMode {
        NONE,
        LETTERBOX,
        PILLARBOX
    }

    data class ViewportFillResult(
        val fillViewport: Boolean,
        val barMode: BarMode,
        val aspectRatioLabel: String,
        val isAccurateFraming: Boolean
    )
}
