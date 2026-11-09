package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSurfacePresentationResolverTest {

    @Test
    fun resolve_usesDirectPresentationWhenNativeCompositorIsOff() {
        val presentation = PreviewSurfacePresentationResolver.resolve(
            PreviewSurfacePresentationInput(
                viewWidth = 1000f,
                viewHeight = 2000f,
                streamWidth = 1920,
                streamHeight = 1440,
                targetAspect = 9f / 16f,
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

        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(0.8888889f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.001f)
        assertEquals(111.1111f, presentation.translationY, 0.001f)
        assertEquals(0f, presentation.rotation, 0.0001f)
    }

    @Test
    fun resolve_nativeFourThreePresentationUsesUpperSurfaceFrameAndLeavesRotationToVulkan() {
        val presentation = PreviewSurfacePresentationResolver.resolve(
            PreviewSurfacePresentationInput(
                viewWidth = 1000f,
                viewHeight = 2000f,
                streamWidth = 1920,
                streamHeight = 1440,
                targetAspect = 3f / 4f,
                previewFillViewport = false,
                rotationDegrees = 90,
                mirrored = true,
                nativeCompositorActive = true,
                sourceCropRegion = NormalizedCropRegion(
                    left = 0.125f,
                    top = 0f,
                    right = 0.875f,
                    bottom = 1f
                ),
                stabilizationStrength = 0f,
                stabilizationAnchorX = 0.5f,
                stabilizationAnchorY = 0.5f
            )
        )

        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(0.6666667f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.0001f)
        assertEquals(186.6667f, presentation.translationY, 0.001f)
        assertEquals(0f, presentation.rotation, 0.0001f)
    }

    @Test
    fun resolve_directPresentationPlacesPortraitFourThreeCloserToTopSensorDock() {
        val presentation = PreviewSurfacePresentationResolver.resolve(
            PreviewSurfacePresentationInput(
                viewWidth = 1000f,
                viewHeight = 2000f,
                streamWidth = 1920,
                streamHeight = 1440,
                targetAspect = 3f / 4f,
                previewFillViewport = false,
                rotationDegrees = 90,
                mirrored = false,
                nativeCompositorActive = false,
                sourceCropRegion = null,
                stabilizationStrength = 0f,
                stabilizationAnchorX = 0.5f,
                stabilizationAnchorY = 0.5f
            )
        )

        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(0.6666667f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.0001f)
        assertEquals(186.6667f, presentation.translationY, 0.001f)
        assertEquals(0f, presentation.rotation, 0.0001f)
    }

    @Test
    fun resolve_nativePresentationKeepsSurfaceLayerUnscaledWhenShaderFramesPreview() {
        val presentation = PreviewSurfacePresentationResolver.resolve(
            PreviewSurfacePresentationInput(
                viewWidth = 1000f,
                viewHeight = 2000f,
                streamWidth = 1920,
                streamHeight = 1440,
                targetAspect = 9f / 16f,
                previewFillViewport = false,
                rotationDegrees = 0,
                mirrored = false,
                nativeCompositorActive = true,
                sourceCropRegion = null,
                stabilizationStrength = 0f,
                stabilizationAnchorX = 0.5f,
                stabilizationAnchorY = 0.5f
            )
        )

        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(1f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.0001f)
        assertEquals(0f, presentation.translationY, 0.001f)
        assertEquals(0f, presentation.rotation, 0.0001f)
    }
}
