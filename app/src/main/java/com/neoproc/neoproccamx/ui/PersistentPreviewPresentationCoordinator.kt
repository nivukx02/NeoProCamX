package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewCompositor
import com.neoproc.neoproccamx.rendering.PersistentPreviewOutputRouter
import com.neoproc.neoproccamx.rendering.PreviewGlCompositor

internal enum class PersistentPreviewOwner(val routerSource: Int) {
    SDR(PersistentPreviewOutputRouter.SOURCE_SDR),
    HDR(PersistentPreviewOutputRouter.SOURCE_HDR)
}

/** Limits full shader/output work to the visible preview and its bounded warmup peer. */
internal class PersistentPreviewPresentationCoordinator(
    private val sdr: PreviewGlCompositor,
    private val hdr: Hdr10PqPreviewCompositor
) {
    fun update(visibleOwner: PersistentPreviewOwner?, warmingOwner: PersistentPreviewOwner? = null) {
        sdr.setFramePresentationEnabled(
            visibleOwner == PersistentPreviewOwner.SDR || warmingOwner == PersistentPreviewOwner.SDR
        )
        hdr.setFramePresentationEnabled(
            visibleOwner == PersistentPreviewOwner.HDR || warmingOwner == PersistentPreviewOwner.HDR
        )
    }
}
