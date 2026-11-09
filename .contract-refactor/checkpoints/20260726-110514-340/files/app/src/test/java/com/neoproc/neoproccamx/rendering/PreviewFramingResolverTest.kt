package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFramingResolverTest {

    @Test
    fun resolve_combinesAspectResolutionAndDirectPresentation() {
        val framing = PreviewFramingResolver.resolve(
            PreviewFramingRequest(
                viewWidth = 1000,
                viewHeight = 2000,
                streamWidth = 1920,
                streamHeight = 1440,
                displayContentAspectRatioOverride = null,
                aspectRatioOverride = 16f / 9f,
                previewFillViewport = false,
                rotationDegrees = 0,
                mirrored = false,
                nativeCompositorActive = false,
                sourceCropRegion = null,
                stabilizationStrength = 0f,
                stabilizationAnchorX = 0.5f,
                stabilizationAnchorY = 0.5f
                )
        )

        assertEquals(9f / 16f, framing.targetAspect, 0.0001f)
        assertEquals(1f, framing.presentation.scaleX, 0.0001f)
        assertEquals(0.8888889f, framing.presentation.scaleY, 0.0001f)
        assertEquals(111.1111f, framing.presentation.translationY, 0.001f)
    }

    @Test
    fun resolveTargetAspect_prefersDisplayOverride() {
        val aspect = PreviewFramingResolver.resolveTargetAspect(
            PreviewFramingRequest(
                viewWidth = 1080,
                viewHeight = 2400,
                streamWidth = 1920,
                streamHeight = 1440,
                displayContentAspectRatioOverride = 4f / 3f,
                aspectRatioOverride = 16f / 9f,
                previewFillViewport = false,
                rotationDegrees = 0,
                mirrored = false,
                nativeCompositorActive = false,
                sourceCropRegion = NormalizedCropRegion.FULL,
                stabilizationStrength = 0f,
                stabilizationAnchorX = 0.5f,
                stabilizationAnchorY = 0.5f
                )
        )

        assertEquals(3f / 4f, aspect, 0.0001f)
    }
}
