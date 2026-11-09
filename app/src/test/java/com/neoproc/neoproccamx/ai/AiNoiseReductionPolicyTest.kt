package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class AiNoiseReductionPolicyTest {

    @Test
    fun strictLowLightNoise_raisesDenoiseAndResidualChromaSuppression() {
        val tuning = AiNoiseReductionPolicy.resolve(
            baseDenoiseStrength = 0.34f,
            sceneType = SceneType.NIGHT,
            profile = AdaptiveRefinementProfile(
                normalizedNoiseFloor = 0.86f,
                perceptualSharpness = 0.18f,
                denoiseBoost = 0.20f,
                sharpenBoost = 0.02f,
                laplacianRefineStrength = 0.18f
            ),
            strictHighEnd = true
        )

        assertTrue("denoiseStrength=${tuning.denoiseStrength}", tuning.denoiseStrength >= 0.68f)
        assertTrue("residualChromaSuppression=${tuning.residualChromaSuppression}", tuning.residualChromaSuppression >= 0.78f)
        assertTrue("spatialSigma=${tuning.spatialSigma}", tuning.spatialSigma >= 2.6f)
        assertTrue("intensitySigma=${tuning.intensitySigma}", tuning.intensitySigma >= 28f)
    }

    @Test
    fun strictExtremeRgbGrain_drivesAiNoiseCancellationNearCeiling() {
        val tuning = AiNoiseReductionPolicy.resolve(
            baseDenoiseStrength = 0.36f,
            sceneType = SceneType.NIGHT_EXTREME,
            profile = AdaptiveRefinementProfile(
                normalizedNoiseFloor = 0.97f,
                perceptualSharpness = 0.12f,
                denoiseBoost = 0.35f,
                sharpenBoost = 0.01f,
                laplacianRefineStrength = 0.10f
            ),
            strictHighEnd = true
        )

        assertTrue("denoiseStrength=${tuning.denoiseStrength}", tuning.denoiseStrength >= 0.94f)
        assertTrue("residualChromaSuppression=${tuning.residualChromaSuppression}", tuning.residualChromaSuppression >= 0.99f)
        assertTrue("spatialSigma=${tuning.spatialSigma}", tuning.spatialSigma >= 3.55f)
        assertTrue("intensitySigma=${tuning.intensitySigma}", tuning.intensitySigma >= 42f)
    }

    @Test
    fun strictModerateRgbGrain_appliesStrictBiasInsteadOfDroppingItFromIfExpression() {
        val profile = AdaptiveRefinementProfile(
            normalizedNoiseFloor = 0.35f,
            perceptualSharpness = 0.30f,
            denoiseBoost = 0.08f,
            sharpenBoost = 0.03f,
            laplacianRefineStrength = 0.20f
        )
        val standard = AiNoiseReductionPolicy.resolve(
            baseDenoiseStrength = 0.22f,
            sceneType = SceneType.INDOOR,
            profile = profile,
            strictHighEnd = false
        )
        val strict = AiNoiseReductionPolicy.resolve(
            baseDenoiseStrength = 0.22f,
            sceneType = SceneType.INDOOR,
            profile = profile,
            strictHighEnd = true
        )

        assertTrue(
            "standard=${standard.denoiseStrength}, strict=${strict.denoiseStrength}",
            strict.denoiseStrength >= standard.denoiseStrength + 0.09f
        )
        assertTrue(
            "standard=${standard.residualChromaSuppression}, strict=${strict.residualChromaSuppression}",
            strict.residualChromaSuppression >= standard.residualChromaSuppression + 0.08f
        )
    }

    @Test
    fun cleanDetailedScene_keepsDenoiseConservativeForDetail() {
        val tuning = AiNoiseReductionPolicy.resolve(
            baseDenoiseStrength = 0.20f,
            sceneType = SceneType.LANDSCAPE,
            profile = AdaptiveRefinementProfile(
                normalizedNoiseFloor = 0.025f,
                perceptualSharpness = 0.74f,
                denoiseBoost = 0.01f,
                sharpenBoost = 0.08f,
                laplacianRefineStrength = 0.38f
            ),
            strictHighEnd = true
        )

        assertTrue("denoiseStrength=${tuning.denoiseStrength}", tuning.denoiseStrength <= 0.18f)
        assertTrue("residualChromaSuppression=${tuning.residualChromaSuppression}", tuning.residualChromaSuppression <= 0.16f)
        assertTrue("intensitySigma=${tuning.intensitySigma}", tuning.intensitySigma <= 22f)
    }
}
