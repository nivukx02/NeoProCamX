package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformMathTest {

    @Test
    fun fitBoundsLetterboxesIntoView() {
        val bounds = PreviewTransformMath.fitBounds(
            viewWidth = 1000f,
            viewHeight = 2000f,
            targetAspect = 16f / 9f
        )

        assertEquals(1000f, bounds.width, 0.0001f)
        assertEquals(562.5f, bounds.height, 0.0001f)
        assertEquals(718.75f, bounds.top, 0.0001f)
        assertEquals(1281.25f, bounds.bottom, 0.0001f)
    }

    @Test
    fun fillBoundsExpandsPastViewportWhenNeeded() {
        val bounds = PreviewTransformMath.fillBounds(
            viewWidth = 1000f,
            viewHeight = 2000f,
            targetAspect = 16f / 9f
        )

        assertTrue(bounds.left < 0f)
        assertTrue(bounds.right > 1000f)
        assertEquals(2000f, bounds.height, 0.0001f)
    }

    @Test
    fun destinationCornerMappingRotatesNinetyDegrees() {
        val content = PreviewTransformMath.RectBounds(
            left = 10f,
            top = 20f,
            right = 110f,
            bottom = 220f
        )

        val mapping = PreviewTransformMath.buildDestinationCornerMapping(
            contentBounds = content,
            rotationDegrees = 90,
            mirrored = false
        )

        assertArrayEquals(
            floatArrayOf(
                10f, 220f,
                10f, 20f,
                110f, 220f,
                110f, 20f
            ),
            mapping,
            0.0001f
        )
    }

    @Test
    fun destinationCornerMappingMirrorsHorizontally() {
        val content = PreviewTransformMath.RectBounds(
            left = 100f,
            top = 50f,
            right = 300f,
            bottom = 250f
        )

        val mapping = PreviewTransformMath.buildDestinationCornerMapping(
            contentBounds = content,
            rotationDegrees = 0,
            mirrored = true
        )

        assertArrayEquals(
            floatArrayOf(
                300f, 50f,
                100f, 50f,
                300f, 250f,
                100f, 250f
            ),
            mapping,
            0.0001f
        )
    }

    @Test
    fun sourceCornerMappingCropsFourThreeIntoSquare() {
        val mapping = PreviewTransformMath.buildSourceCornerMapping(
            viewWidth = 400f,
            viewHeight = 600f,
            sourceAspect = 2f / 3f,
            targetAspect = 1f,
            fillViewport = false
        )

        assertArrayEquals(
            floatArrayOf(
                0f, 100f,
                400f, 100f,
                0f, 500f,
                400f, 500f
            ),
            mapping,
            0.0001f
        )
    }

    @Test
    fun sourceCornerMappingAppliesExplicitCropInsideFittedSensorBounds() {
        val mapping = PreviewTransformMath.buildSourceCornerMapping(
            viewWidth = 1000f,
            viewHeight = 2000f,
            sourceAspect = 3f / 4f,
            targetAspect = 9f / 16f,
            fillViewport = false,
            sourceCropRegion = com.neoproc.neoproccamx.camera.NormalizedCropRegion(
                left = 0f,
                top = 0.125f,
                right = 1f,
                bottom = 0.875f
            )
        )

        assertArrayEquals(
            floatArrayOf(
                0f, 500f,
                1000f, 500f,
                0f, 1500f,
                1000f, 1500f
            ),
            mapping,
            0.0001f
        )
    }

    @Test
    fun sourceCornerMappingCenterCropsInFillModeWhenAspectDiffers() {
        val mapping = PreviewTransformMath.buildSourceCornerMapping(
            viewWidth = 400f,
            viewHeight = 300f,
            sourceAspect = 4f / 3f,
            targetAspect = 1f,
            fillViewport = true
        )

        assertArrayEquals(
            floatArrayOf(
                50f, 0f,
                350f, 0f,
                50f, 300f,
                350f, 300f
            ),
            mapping,
            0.0001f
        )
    }
}
