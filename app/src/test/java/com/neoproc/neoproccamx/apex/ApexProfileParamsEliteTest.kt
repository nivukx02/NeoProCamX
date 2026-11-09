package com.neoproc.neoproccamx.apex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexProfileParamsEliteTest {

    @Test
    fun sharpModeKeepsContractFormulaAndAddsNoiseGatedDetailHeadroom() {
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.80f,
            shadowNeed = 0.50f,
            exposureStability = 1.0f,
            localContrastScore = 0.72f,
            noiseRisk = 0.04f,
            chromaNoiseRisk = 0.03f,
            motionScore = 0.05f,
            sceneWhite = 0.34f
        )

        val params = ApexProfileParams.compute(metrics, ApexMode.SHARP)

        assertEquals(13_600f, params.sourcePeakNits, 0.001f)
        assertEquals(2_320f, params.targetPeakNits, 0.001f)
        assertEquals(0.00212f, params.shadowLift, 0.00001f)
        assertTrue(params.shoulderStrength >= 4.80f + 0.62f * 0.80f)
        assertTrue(params.detailGain1 > 0.78f)
        assertTrue(params.detailGain2 > 0.28f)
        assertTrue(params.localToneStrength > 0f)
        assertTrue(params.motionDetailDamping < 1f)
    }

    @Test
    fun zeroModeRemainsNearLinearAndNeverAmplifiesDetail() {
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.95f,
            shadowNeed = 0.80f,
            localContrastScore = 0.90f,
            noiseRisk = 0.02f,
            motionScore = 0.02f,
            sceneWhite = 0.24f
        )

        val params = ApexProfileParams.compute(metrics, ApexMode.ZERO)

        assertEquals(0f, params.detailGain1, 0f)
        assertEquals(0f, params.detailGain2, 0f)
        assertEquals(0f, params.detailGain3, 0f)
        assertEquals(0f, params.localToneStrength, 0f)
        assertTrue(params.toeStrength <= 0.02f)
        assertTrue(params.saturationPreservation >= 0.82f)
    }
}
