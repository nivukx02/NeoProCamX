package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimatorCinemaContractTest {

    @Test
    fun inverseDepthAndCircleOfConfusionFollowPhysicalFocusModel() {
        val nearMeters = DepthEstimator.normalizedInverseDepthToMeters(
            normalizedInverseDepth = 1f,
            nearMeters = 0.1f,
            farMeters = 100f
        )
        val farMeters = DepthEstimator.normalizedInverseDepthToMeters(
            normalizedInverseDepth = 0f,
            nearMeters = 0.1f,
            farMeters = 100f
        )

        assertEquals(0.1f, nearMeters, 0.0001f)
        assertEquals(100f, farMeters, 0.01f)

        val focusedRadius = DepthEstimator.circleOfConfusionRadiusPixels(
            normalizedInverseDepth = 0.2f,
            focusDistanceMeters = DepthEstimator.normalizedInverseDepthToMeters(0.2f),
            viewportWidthPixels = 1920
        )
        val defocusedRadius = DepthEstimator.circleOfConfusionRadiusPixels(
            normalizedInverseDepth = 0.9f,
            focusDistanceMeters = DepthEstimator.normalizedInverseDepthToMeters(0.2f),
            viewportWidthPixels = 1920
        )

        assertTrue(focusedRadius < 0.01f)
        assertTrue(defocusedRadius > focusedRadius)
    }

    @Test
    fun guidedUpsamplerPreservesDepthDiscontinuityAtLumaEdge() {
        val lowDepth = floatArrayOf(
            0.9f, 0.1f,
            0.9f, 0.1f
        )
        val luma = floatArrayOf(
            0.1f, 0.1f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.9f, 0.9f,
            0.1f, 0.1f, 0.9f, 0.9f
        )

        val upsampled = DepthEstimator.guidedFilterDepthUpsample(
            lowDepth = lowDepth,
            lowWidth = 2,
            lowHeight = 2,
            lumaGuide = luma,
            guideWidth = 4,
            guideHeight = 4,
            radius = 1,
            epsilon = 1.0e-4f
        )

        assertEquals(16, upsampled.size)
        assertTrue(upsampled[1] - upsampled[2] > 0.28f)
        assertTrue(upsampled.all { it in 0f..1f })
    }

    @Test
    fun temporalSmoothingUsesMotionAdaptiveHistoryWeight() {
        val previous = floatArrayOf(0.2f)
        val current = floatArrayOf(0.8f)
        val staticBlend = DepthEstimator.motionAdaptiveTemporalDepthSmoothing(
            currentDepth = current,
            previousDepth = previous,
            currentLuma = floatArrayOf(0.5f),
            previousLuma = floatArrayOf(0.5f)
        )
        val motionBlend = DepthEstimator.motionAdaptiveTemporalDepthSmoothing(
            currentDepth = current,
            previousDepth = previous,
            currentLuma = floatArrayOf(1f),
            previousLuma = floatArrayOf(0f)
        )

        assertEquals(0.59f, staticBlend.depth[0], 0.0001f)
        assertTrue(motionBlend.depth[0] > staticBlend.depth[0])
        assertTrue(motionBlend.meanMotion > staticBlend.meanMotion)
        assertTrue(motionBlend.meanHistoryWeight < staticBlend.meanHistoryWeight)
    }

    @Test
    fun depthDataCarriesHighResolutionGuideAndMotionMetadata() {
        val depth = DepthData(
            mapWidth = 2,
            mapHeight = 2,
            normalizedDepth = floatArrayOf(0f, 0.25f, 0.75f, 1f),
            highResolutionDepth = FloatArray(16) { 0.5f },
            highResolutionWidth = 4,
            highResolutionHeight = 4,
            lumaGuide = FloatArray(16) { 0.2f },
            temporalMotionMean = 0.12f,
            temporalHistoryWeightMean = 0.31f,
            circleOfConfusionRadius = FloatArray(16) { 2f },
            bokehRenderBackend = "VULKAN_VOGEL_SPIRAL"
        )

        assertEquals(16, depth.highResolutionDepth?.size)
        assertEquals(4, depth.highResolutionWidth)
        assertEquals(0.12f, depth.temporalMotionMean, 0.0001f)
        assertEquals("VULKAN_VOGEL_SPIRAL", depth.bokehRenderBackend)
    }
}
