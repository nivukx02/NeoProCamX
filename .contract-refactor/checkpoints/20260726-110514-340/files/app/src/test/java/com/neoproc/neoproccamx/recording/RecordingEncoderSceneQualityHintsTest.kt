package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEncoderSceneQualityHintsTest {

    @Test
    fun fromMetadataCombinesMotionEdgesDynamicRangeAndHighlightsIntoSceneComplexity() {
        val hints = RecordingEncoderSceneQualityHints.fromMetadata(
            "lux=4.5;motion=0.420;edge=0.600;dynamicRange=0.800;highlight=0.300;dropEstimate=3;thermalLabel=Severe"
        )

        assertTrue("sceneComplexity=${hints.sceneComplexity}", hints.sceneComplexity > 0.55f)
        assertEquals(4.5f, hints.estimatedLux ?: 0f, 0.001f)
        assertEquals(3, hints.droppedFrames)
        assertEquals(4, hints.thermalSeverity)
        assertTrue(hints.lowLight)
        assertTrue(hints.thermalDowngradeActive)
    }

    @Test
    fun fromMetadataFallsBackSafelyWhenSceneMetadataIsMissing() {
        val hints = RecordingEncoderSceneQualityHints.fromMetadata("")

        assertEquals(0f, hints.sceneComplexity, 0.001f)
        assertEquals(null, hints.estimatedLux)
        assertEquals(0, hints.droppedFrames)
        assertEquals(0, hints.thermalSeverity)
    }

    @Test
    fun fromMetadataCarriesV20MeasuredSourcePressureIntoEncoderPlanning() {
        val hints = RecordingEncoderSceneQualityHints.fromMetadata(
            "v20MotionRisk=0.840;v20NoiseRisk=0.730;" +
                "v20AntiMotionBlurEnabled=true;" +
                "v20ProcessedVideoSourceQualityTier=MOTION_STABLE_LOW_NOISE_DETAIL;" +
                "v20ProcessedVideoSourceQualityBudgetMultiplier=1.320"
        )

        assertEquals(0.84f, hints.motionRisk, 0.001f)
        assertEquals(0.73f, hints.noiseRisk, 0.001f)
        assertTrue(hints.antiMotionBlurEnabled)
        assertEquals("MOTION_STABLE_LOW_NOISE_DETAIL", hints.sourceQualityTier)
        assertEquals(1.32, hints.sourceQualityBudgetMultiplier, 0.001)
        assertTrue(hints.hasSceneSignal)
    }
}
