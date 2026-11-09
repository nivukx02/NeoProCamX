package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewAspectResolverTest {

    @Test
    fun resolveStreamAspect_preservesLongShortRatioInPortraitBounds() {
        val aspect = PreviewAspectResolver.resolveStreamAspect(
            streamWidth = 1920,
            streamHeight = 1440,
            boundsWidth = 1080,
            boundsHeight = 2400
        )

        assertEquals(4f / 3f, aspect, 0.0001f)
    }

    @Test
    fun resolveDisplayAspect_convertsSensorAspectToPortraitDisplaySpace() {
        val aspect = PreviewAspectResolver.resolveDisplayAspect(
            boundsWidth = 1080,
            boundsHeight = 2400,
            streamWidth = 1920,
            streamHeight = 1440,
            displayContentAspectRatioOverride = null,
            aspectRatioOverride = null
        )

        assertEquals(3f / 4f, aspect, 0.0001f)
    }

    @Test
    fun resolveDisplayAspect_convertsLandscapeAspectOverrideForPortraitBounds() {
        val aspect = PreviewAspectResolver.resolveDisplayAspect(
            boundsWidth = 1080,
            boundsHeight = 2400,
            streamWidth = 1920,
            streamHeight = 1440,
            displayContentAspectRatioOverride = null,
            aspectRatioOverride = 16f / 9f
        )

        assertEquals(9f / 16f, aspect, 0.0001f)
    }

    @Test
    fun resolveDisplayAspect_preservesAspectOverrideAlreadyAlignedToBounds() {
        val aspect = PreviewAspectResolver.resolveDisplayAspect(
            boundsWidth = 1080,
            boundsHeight = 2400,
            streamWidth = 1920,
            streamHeight = 1440,
            displayContentAspectRatioOverride = null,
            aspectRatioOverride = 9f / 16f
        )

        assertEquals(9f / 16f, aspect, 0.0001f)
    }
}
