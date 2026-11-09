package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceTransformControllerTest {

    @Test
    fun buildDirectSurfacePresentationFitsPreviewIntoGridBounds() {
        val presentation = PreviewSurfaceTransformController.buildDirectSurfacePresentation(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1920,
            streamHeight = 1440,
            targetAspect = 9f / 16f,
            previewFillViewport = false,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertEquals(1000f, presentation.contentBounds.width, 0.0001f)
        assertEquals(1777.7778f, presentation.contentBounds.height, 0.001f)
        assertEquals(1.3333334f, presentation.scaleX, 0.0001f)
        assertEquals(0.8888889f, presentation.scaleY, 0.0001f)
        assertEquals(-166.6667f, presentation.translationX, 0.001f)
        assertEquals(111.1111f, presentation.translationY, 0.001f)
        val clip = requireNotNull(presentation.localClipBounds)
        assertEquals(125f, clip.left, 0.001f)
        assertEquals(0f, clip.top, 0.001f)
        assertEquals(875f, clip.right, 0.001f)
        assertEquals(2000f, clip.bottom, 0.001f)
    }

    @Test
    fun buildDirectSurfacePresentationPlacesFourThreeFrameCloserToTopSensorDock() {
        val presentation = PreviewSurfaceTransformController.buildDirectSurfacePresentation(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1920,
            streamHeight = 1440,
            targetAspect = 3f / 4f,
            previewFillViewport = false,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertEquals(1000f, presentation.contentBounds.width, 0.0001f)
        assertEquals(1333.3334f, presentation.contentBounds.height, 0.001f)
        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(0.6666667f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.0001f)
        assertEquals(186.6667f, presentation.translationY, 0.001f)
        assertEquals(null, presentation.localClipBounds)
    }

    @Test
    fun buildDirectSurfacePresentationKeepsFullSixteenNineFrameWhenPortraitAspectMatches() {
        val presentation = PreviewSurfaceTransformController.buildDirectSurfacePresentation(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1920,
            streamHeight = 1080,
            targetAspect = 9f / 16f,
            previewFillViewport = false,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertEquals(1000f, presentation.contentBounds.width, 0.0001f)
        assertEquals(1777.7778f, presentation.contentBounds.height, 0.001f)
        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(0.8888889f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.0001f)
        assertEquals(111.1111f, presentation.translationY, 0.001f)
        assertEquals(null, presentation.localClipBounds)
    }

    @Test
    fun buildPlanKeepsFitBoundsAndNinetyDegreeDestinationMapping() {
        val plan = PreviewSurfaceTransformController.buildPlan(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1920,
            streamHeight = 1440,
            targetAspect = 16f / 9f,
            previewFillViewport = false,
            rotationDegrees = 90,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertEquals(1000f, plan.contentBounds.width, 0.0001f)
        assertEquals(562.5f, plan.contentBounds.height, 0.0001f)
        assertArrayEquals(
            floatArrayOf(
                0f, 1281.25f,
                0f, 718.75f,
                1000f, 1281.25f,
                1000f, 718.75f
            ),
            plan.destinationPoints,
            0.0001f
        )
    }

    @Test
    fun buildPlanDoesNotCropPortraitFourThreeWhenDisplayAspectAlreadyMatches() {
        val plan = PreviewSurfaceTransformController.buildPlan(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1920,
            streamHeight = 1440,
            targetAspect = 3f / 4f,
            previewFillViewport = false,
            rotationDegrees = 0,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertArrayEquals(
            floatArrayOf(
                0f, 333.3333f,
                1000f, 333.3333f,
                0f, 1666.6667f,
                1000f, 1666.6667f
            ),
            plan.sourcePoints,
            0.001f
        )
    }

    @Test
    fun buildPlanCenterCropsSourceWhenTargetAspectDiffersFromStream() {
        val plan = PreviewSurfaceTransformController.buildPlan(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1920,
            streamHeight = 1440,
            targetAspect = 16f / 9f,
            previewFillViewport = false,
            rotationDegrees = 0,
            mirrored = false,
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

        assertArrayEquals(
            floatArrayOf(
                0f, 718.75f,
                1000f, 718.75f,
                0f, 1281.25f,
                1000f, 1281.25f
            ),
            plan.sourcePoints,
            0.001f
        )
    }

    @Test
    fun buildDirectSurfacePresentationCropsCinematicFromFourThreeSensorWithoutStretch() {
        val presentation = PreviewSurfaceTransformController.buildDirectSurfacePresentation(
            viewWidth = 1080f,
            viewHeight = 2400f,
            streamWidth = 1600,
            streamHeight = 1200,
            targetAspect = 1f / 2.39f,
            previewFillViewport = false,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertEquals(1.6666666f, presentation.scaleX, 0.0001f)
        assertEquals(1f, presentation.scaleY, 0.0001f)
        assertEquals(-360f, presentation.translationX, 0.001f)
        assertEquals(0f, presentation.translationY, 0.001f)
        val clip = requireNotNull(presentation.localClipBounds)
        assertEquals(238.7448f, clip.left, 0.001f)
        assertEquals(841.2552f, clip.right, 0.001f)
        assertTrue(presentation.contentBounds.width < 1080f)
        assertEquals(2400f, presentation.contentBounds.height, 0.0001f)
    }

    @Test
    fun buildDirectSurfacePresentationCropsStableFourThreeTransportToSquareWithoutStretch() {
        val presentation = PreviewSurfaceTransformController.buildDirectSurfacePresentation(
            viewWidth = 1000f,
            viewHeight = 2000f,
            streamWidth = 1280,
            streamHeight = 960,
            targetAspect = 1f,
            previewFillViewport = false,
            mirrored = false,
            sourceCropRegion = null,
            stabilizationStrength = 0f,
            stabilizationAnchorX = 0.5f,
            stabilizationAnchorY = 0.5f
        )

        assertEquals(1f, presentation.scaleX, 0.0001f)
        assertEquals(2f / 3f, presentation.scaleY, 0.0001f)
        assertEquals(0f, presentation.translationX, 0.001f)
        assertEquals(333.3333f, presentation.translationY, 0.001f)
        val clip = requireNotNull(presentation.localClipBounds)
        assertEquals(0f, clip.left, 0.001f)
        assertEquals(250f, clip.top, 0.001f)
        assertEquals(1000f, clip.right, 0.001f)
        assertEquals(1750f, clip.bottom, 0.001f)
    }
}
