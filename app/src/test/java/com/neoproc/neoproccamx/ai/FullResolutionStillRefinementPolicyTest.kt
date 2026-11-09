package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResolutionStillRefinementPolicyTest {

    @Test
    fun highQualityStill_keepsEveryApplicableSpatialStageAtNativeResolution() {
        val decision = FullResolutionStillRefinementPolicy.resolve(
            EnhancementEngine.EnhancementConfig(
                denoiseStrength = 0.22f,
                sharpenStrength = 0.32f,
                microContrastStrength = 0.18f,
                residualChromaSuppression = 0.12f,
                temporalDenoiseWeight = 0.40f,
                preferNeuralDenoise = true
            )
        )

        assertTrue(decision.preservesAllRequestedSpatialStages)
        assertTrue(decision.residualChromaSuppression > 0f)
        assertEquals(0f, decision.temporalDenoiseWeight, 0f)
        assertFalse(decision.preferNeuralDenoise)
    }

    @Test
    fun unsafeStrengths_areBoundedWithoutDisablingRequestedStages() {
        val decision = FullResolutionStillRefinementPolicy.resolve(
            EnhancementEngine.EnhancementConfig(
                denoiseStrength = 1f,
                sharpenStrength = 1f,
                microContrastStrength = 1f,
                residualChromaSuppression = 1f
            )
        )

        assertEquals(0.30f, decision.denoiseStrength, 0f)
        assertEquals(0.45f, decision.sharpenStrength, 0f)
        assertEquals(0.36f, decision.microContrastStrength, 0f)
        assertEquals(0.24f, decision.residualChromaSuppression, 0f)
        assertTrue(decision.preservesAllRequestedSpatialStages)
    }
}
