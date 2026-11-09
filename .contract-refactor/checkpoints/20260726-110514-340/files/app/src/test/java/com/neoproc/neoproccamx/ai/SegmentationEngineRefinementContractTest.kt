package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationEngineRefinementContractTest {

    @Test
    fun guidedFilterMaskRefineTransfersHighResolutionLumaBoundary() {
        val lowMask = floatArrayOf(
            0.95f, 0.05f,
            0.95f, 0.05f
        )
        val lumaGuide = floatArrayOf(
            0.12f, 0.12f, 0.86f, 0.86f,
            0.12f, 0.12f, 0.86f, 0.86f,
            0.12f, 0.12f, 0.86f, 0.86f,
            0.12f, 0.12f, 0.86f, 0.86f
        )

        val refined = SegmentationEngine.guidedFilterMaskRefine(
            lowMask = lowMask,
            lowWidth = 2,
            lowHeight = 2,
            lumaGuide = lumaGuide,
            guideWidth = 4,
            guideHeight = 4,
            radius = 1,
            epsilon = 1.0e-4f
        )

        assertEquals(16, refined.size)
        assertTrue(refined.all { it in 0f..1f })
        assertTrue("edge left=${refined[1]} right=${refined[2]}", refined[1] - refined[2] > 0.28f)
    }

    @Test
    fun backwardWarpedTemporalBlendDropsHistoryUnderLargeMotion() {
        val current = FloatArray(9) { 0f }
        val previous = FloatArray(9) { 1f }
        val staticFlow = FloatArray(9) { 0f }
        val fastFlow = FloatArray(9) { 4f }

        val staticBlend = SegmentationEngine.motionAdaptiveBackwardWarpTemporalBlend(
            currentMask = current,
            previousMask = previous,
            width = 3,
            height = 3,
            flowX = staticFlow,
            flowY = staticFlow,
            baseAlpha = 0.85f,
            beta = 0.5f
        )
        val fastBlend = SegmentationEngine.motionAdaptiveBackwardWarpTemporalBlend(
            currentMask = current,
            previousMask = previous,
            width = 3,
            height = 3,
            flowX = fastFlow,
            flowY = staticFlow,
            baseAlpha = 0.85f,
            beta = 0.5f
        )

        assertTrue(staticBlend.mask[4] > 0.8f)
        assertTrue(fastBlend.mask[4] < staticBlend.mask[4])
        assertTrue(fastBlend.meanHistoryWeight < staticBlend.meanHistoryWeight)
        assertTrue(fastBlend.meanMotion > staticBlend.meanMotion)
    }

    @Test
    fun skinChromaMahalanobisFallbackPrefersPriorColoredCenter() {
        val width = 6
        val height = 6
        val cb = FloatArray(width * height) { 40f }
        val cr = FloatArray(width * height) { 220f }
        for (y in 2..3) {
            for (x in 2..3) {
                cb[y * width + x] = 103f
                cr[y * width + x] = 151f
            }
        }

        val fallback = SegmentationEngine.skinChromaMahalanobisFallback(
            cb = cb,
            cr = cr,
            width = width,
            height = height,
            lastCenterX = 0.5f,
            lastCenterY = 0.5f
        )

        val center = fallback.mask[3 * width + 3]
        val corner = fallback.mask[0]
        assertEquals(width * height, fallback.mask.size)
        assertTrue("center=$center corner=$corner", center > corner)
        assertTrue(center > 0.70f)
        assertTrue(fallback.confidence[3 * width + 3] > fallback.confidence[0])
    }
}
