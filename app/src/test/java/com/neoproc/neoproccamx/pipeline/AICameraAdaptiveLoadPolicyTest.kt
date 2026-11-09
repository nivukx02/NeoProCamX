package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AICameraAdaptiveLoadPolicyTest {

    @Test
    fun resolve_keepsBaseProfileWhenNoLoadPenalty() {
        val profile = AICameraAdaptiveLoadPolicy.resolve(baseProfile(), loadPenalty = 0)

        assertEquals(4, profile.poseDetectionStride)
        assertEquals(8, profile.depthEstimationStride)
        assertEquals(640, profile.analysisMaxDimension)
        assertEquals(22L, profile.analysisTimeoutMs)
    }

    @Test
    fun resolve_capsAiDensityAndBitmapSizeUnderSevereLoad() {
        val profile = AICameraAdaptiveLoadPolicy.resolve(baseProfile(), loadPenalty = 3)

        assertTrue(profile.poseDetectionStride > 4)
        assertTrue(profile.segmentationStride > 6)
        assertTrue(profile.depthEstimationStride > 8)
        assertTrue(profile.enhancementStride > 4)
        assertTrue(profile.sceneDetectionStride > 6)
        assertTrue(profile.analysisMaxDimension < 640)
        assertTrue(profile.analysisMaxDimension >= 256)
        assertTrue(profile.analysisTimeoutMs < 22L)
        assertTrue(profile.analysisTimeoutMs >= 12L)
    }

    private fun baseProfile() = AICameraAdaptiveLoadBaseProfile(
        poseDetectionStride = 4,
        segmentationStride = 6,
        depthEstimationStride = 8,
        enhancementStride = 4,
        sceneDetectionStride = 6,
        analysisTimeoutMs = 22L,
        analysisMaxDimension = 640
    )
}
