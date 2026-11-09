package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadianceMergeTest {

    @Test
    fun exposureWeight_favorsMiddleValuesAndRejectsClips() {
        assertEquals(1f, RadianceMerge.exposureWeight(0.5f), 1e-7f)
        assertEquals(0f, RadianceMerge.exposureWeight(0f), 1e-7f)
        assertEquals(0f, RadianceMerge.exposureWeight(1f), 1e-7f)
        assertTrue(RadianceMerge.exposureWeight(0.25f) > 0.98f)
    }

    @Test
    fun mergeFallsBackToReferenceWhenMotionConfidenceIsBelowHalf() {
        val samples = listOf(
            RadianceSample(0.20f, 0.20f, 0.20f, normalizedSignal = 0.50f, exposureScale = 1f, motionConfidence = 1f),
            RadianceSample(1.20f, 0.10f, 0.10f, normalizedSignal = 0.45f, exposureScale = 2f, motionConfidence = 0.40f)
        )

        val result = RadianceMerge.mergeExposureStack(samples, referenceIndex = 0)

        assertTrue(result.usedReferenceFallback)
        assertEquals(0.20f, result.r, 0f)
        assertEquals(0.20f, result.g, 0f)
        assertEquals(0.20f, result.b, 0f)
    }

    @Test
    fun mergeReconstructsLinearRadianceAcrossExposureStack() {
        val samples = listOf(
            RadianceSample(0.50f, 0.25f, 0.10f, normalizedSignal = 0.50f, exposureScale = 1f, motionConfidence = 1f),
            RadianceSample(1.00f, 0.50f, 0.20f, normalizedSignal = 0.50f, exposureScale = 2f, motionConfidence = 1f),
            RadianceSample(0.25f, 0.125f, 0.05f, normalizedSignal = 0.50f, exposureScale = 0.5f, motionConfidence = 1f)
        )

        val result = RadianceMerge.mergeExposureStack(samples, referenceIndex = 0)

        assertFalse(result.usedReferenceFallback)
        assertEquals(0.50f, result.r, 1e-5f)
        assertEquals(0.25f, result.g, 1e-5f)
        assertEquals(0.10f, result.b, 1e-5f)
    }

    @Test
    fun fourValidFramesProvideAtLeastSixDbShadowSnrGain() {
        val gain = RadianceMerge.theoreticalSnrGainDb(validFrameCount = 4)

        assertTrue(gain >= 6.0f)
    }

    @Test
    fun temporalEmaUsesPointOneFiveAlphaForBrightnessStability() {
        val previous = 1.00f
        val current = 1.05f
        val smoothed = RadianceMerge.temporalEma(previous, current)

        assertEquals(1.0075f, smoothed, 1e-6f)
        assertTrue((smoothed - previous) / previous <= 0.01f)
    }
}
