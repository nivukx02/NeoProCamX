package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewClipBoundsPolicyTest {

    @Test
    fun shouldApplyClip_onlyWhenNoPresentationOwnsAspectFrame() {
        assertFalse(
            PreviewClipBoundsPolicy.shouldApplyClip(
                previewFillViewport = false,
                presentationHandlesAspectFrame = true
            )
        )
        assertTrue(
            PreviewClipBoundsPolicy.shouldApplyClip(
                previewFillViewport = false,
                presentationHandlesAspectFrame = false
            )
        )
        assertFalse(
            PreviewClipBoundsPolicy.shouldApplyClip(
                previewFillViewport = true,
                presentationHandlesAspectFrame = false
            )
        )
    }
}
