package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f01 / CP92 — gated governor ordered-drop. Proves no-downgrade when off (equals today) and
 * the depth >= segmentation >= pose drop ordering when on; face has no stride so is dropped last.
 */
class AICameraAdaptiveLoadOrderedDropTest {

    private fun base() = AICameraAdaptiveLoadBaseProfile(
        poseDetectionStride = 4,
        segmentationStride = 6,
        depthEstimationStride = 8,
        enhancementStride = 4,
        sceneDetectionStride = 6,
        analysisTimeoutMs = 22L,
        analysisMaxDimension = 640
    )

    @Test
    fun orderedDropOffEqualsTodayForAllPenalties() {
        for (penalty in 0..3) {
            val legacy = AICameraAdaptiveLoadPolicy.resolve(base(), penalty)
            val explicitOff = AICameraAdaptiveLoadPolicy.resolve(base(), penalty, orderedDrop = false)
            assertEquals("penalty $penalty must be bit-for-bit when orderedDrop=false", legacy, explicitOff)
        }
    }

    @Test
    fun orderedDropShedsDepthHardestThenSegThenPose() {
        val b = base()
        val off = AICameraAdaptiveLoadPolicy.resolve(b, loadPenalty = 3, orderedDrop = false)
        val on = AICameraAdaptiveLoadPolicy.resolve(b, loadPenalty = 3, orderedDrop = true)

        // Depth and segmentation shed at least as hard as the uniform path; pose unchanged by ordering.
        assertTrue(on.depthEstimationStride >= off.depthEstimationStride)
        assertTrue(on.segmentationStride >= off.segmentationStride)
        assertEquals(off.poseDetectionStride, on.poseDetectionStride)

        // Growth ratios: depth >= segmentation >= pose (the ordered drop priority).
        val depthRatio = on.depthEstimationStride.toFloat() / b.depthEstimationStride
        val segRatio = on.segmentationStride.toFloat() / b.segmentationStride
        val poseRatio = on.poseDetectionStride.toFloat() / b.poseDetectionStride
        assertTrue("depth shed >= seg", depthRatio >= segRatio)
        assertTrue("seg shed >= pose", segRatio >= poseRatio)

        // All strides still strictly increase under load.
        assertTrue(on.depthEstimationStride > b.depthEstimationStride)
        assertTrue(on.segmentationStride > b.segmentationStride)
        assertTrue(on.poseDetectionStride > b.poseDetectionStride)
    }
}
