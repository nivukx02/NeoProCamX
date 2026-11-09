package com.neoproc.neoproccamx.rendering

internal object PreviewClipBoundsPolicy {
    fun shouldApplyClip(
        previewFillViewport: Boolean,
        presentationHandlesAspectFrame: Boolean
    ): Boolean = !previewFillViewport && !presentationHandlesAspectFrame
}
