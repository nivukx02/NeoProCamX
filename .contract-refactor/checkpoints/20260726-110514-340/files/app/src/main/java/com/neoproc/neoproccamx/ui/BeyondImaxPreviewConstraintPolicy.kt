package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode

internal data class BeyondImaxPreviewConstraints(
    val previewAspect: PreviewAspectRatio,
    val resetZoomToOne: Boolean
)

internal object BeyondImaxPreviewConstraintPolicy {
    fun resolve(
        uiMode: AppMode,
        cinemaMode: CinemaVideoMode,
        currentAspect: PreviewAspectRatio
    ): BeyondImaxPreviewConstraints {
        val rawVideoActive = uiMode == AppMode.VIDEO && cinemaMode == CinemaVideoMode.RAW
        return BeyondImaxPreviewConstraints(
            previewAspect = currentAspect,
            resetZoomToOne = rawVideoActive
        )
    }
}
