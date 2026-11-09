package com.neoproc.neoproccamx.ai

internal data class AiNoiseReductionTuning(
    val denoiseStrength: Float,
    val spatialSigma: Float,
    val intensitySigma: Float,
    val residualChromaSuppression: Float
)

internal object AiNoiseReductionPolicy {

    fun resolve(
        baseDenoiseStrength: Float,
        sceneType: SceneType,
        profile: AdaptiveRefinementProfile,
        strictHighEnd: Boolean
    ): AiNoiseReductionTuning {
        val noisePressure = profile.normalizedNoiseFloor.coerceIn(0f, 1f)
        val textureConfidence = profile.perceptualSharpness.coerceIn(0f, 1f)
        val severeNoisePressure = ((noisePressure - 0.62f) / 0.38f).coerceIn(0f, 1f)
        val sceneNoiseBias = when (sceneType) {
            SceneType.NIGHT_EXTREME, SceneType.ASTRO -> 0.18f
            SceneType.NIGHT, SceneType.PORTRAIT_LOW_LIGHT, SceneType.CONCERT, SceneType.BLUE_HOUR -> 0.12f
            SceneType.INDOOR -> 0.06f
            SceneType.PORTRAIT, SceneType.PORTRAIT_GROUP -> 0.02f
            SceneType.LANDSCAPE, SceneType.OUTDOOR, SceneType.ARCHITECTURE, SceneType.MACRO -> -0.05f
            else -> 0f
        }
        val strictNoiseBias = if (strictHighEnd && noisePressure >= 0.08f) 0.12f else 0f
        val detailGuard = textureConfidence * if (noisePressure < 0.22f) 0.08f else 0.035f
        val denoiseStrength = (
            baseDenoiseStrength +
                sceneNoiseBias +
                noisePressure * 0.24f +
                profile.denoiseBoost * 1.45f +
                (if (strictHighEnd) severeNoisePressure * 0.10f else 0f) +
                strictNoiseBias -
                detailGuard
            ).coerceIn(0f, if (strictHighEnd) 0.96f else 0.64f)

        val lowLightSigmaBoost = when (sceneType) {
            SceneType.NIGHT_EXTREME, SceneType.ASTRO -> 0.35f
            SceneType.NIGHT, SceneType.PORTRAIT_LOW_LIGHT, SceneType.CONCERT, SceneType.BLUE_HOUR -> 0.20f
            else -> 0f
        }
        val spatialSigma = (
            2.0f +
                noisePressure * 1.05f +
                lowLightSigmaBoost +
                (if (strictHighEnd && noisePressure >= 0.10f) 0.32f else 0f) +
                (if (strictHighEnd) severeNoisePressure * 0.28f else 0f) -
                textureConfidence * 0.18f
            ).coerceIn(1.65f, if (strictHighEnd) 4.0f else 3.25f)
        val intensitySigma = (
            18.0f +
                noisePressure * 19.0f +
                lowLightSigmaBoost * 5.0f +
                (if (strictHighEnd && noisePressure >= 0.10f) 3.75f else 0f) +
                (if (strictHighEnd) severeNoisePressure * 5.0f else 0f) -
                textureConfidence * 2.6f
            ).coerceIn(12.0f, if (strictHighEnd) 48.0f else 36.0f)
        val residualChromaSuppression = (
            noisePressure * 0.86f +
                sceneNoiseBias.coerceAtLeast(0f) * 1.35f +
                profile.denoiseBoost * 0.52f +
                (if (strictHighEnd && noisePressure >= 0.10f) 0.12f else 0f) +
                (if (strictHighEnd) severeNoisePressure * 0.08f else 0f) -
                textureConfidence * if (noisePressure < 0.22f) 0.10f else 0.035f
            ).coerceIn(0f, if (strictHighEnd) 0.999f else 0.82f)

        return AiNoiseReductionTuning(
            denoiseStrength = denoiseStrength,
            spatialSigma = spatialSigma,
            intensitySigma = intensitySigma,
            residualChromaSuppression = residualChromaSuppression
        )
    }
}
