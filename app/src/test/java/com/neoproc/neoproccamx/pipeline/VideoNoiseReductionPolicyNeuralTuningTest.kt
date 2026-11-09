package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNoiseReductionPolicyNeuralTuningTest {

    @Test
    fun highIsoRaisesNeuralChromaSuppressionMoreThanLumaTextureSuppression() {
        val lowIso = plan(iso = 400f, textureConfidence = 0.25f)
        val highIso = plan(iso = 25_600f, textureConfidence = 0.25f)

        assertTrue(highIso.neuralDenoise.enabled)
        assertTrue(highIso.neuralDenoise.chromaThreshold > lowIso.neuralDenoise.chromaThreshold)
        assertTrue(highIso.neuralDenoise.chromaThreshold > highIso.neuralDenoise.lumaThreshold)
    }

    @Test
    fun textureConfidenceProtectsLumaDetailWhileAllowingChromaCleanup() {
        val flat = plan(textureConfidence = 0.05f)
        val textured = plan(textureConfidence = 0.92f)

        assertTrue(textured.neuralDenoise.lumaThreshold < flat.neuralDenoise.lumaThreshold)
        assertTrue(textured.neuralDenoise.texturePreservation > flat.neuralDenoise.texturePreservation)
        assertTrue(textured.neuralDenoise.chromaThreshold >= textured.neuralDenoise.lumaThreshold)
    }

    private fun plan(iso: Float = 3200f, textureConfidence: Float): VideoNoiseReductionPlan =
        VideoNoiseReductionPolicy.resolve(
            VideoNoiseReductionRequest(
                signalFamily = VideoImageSignalFamily.LOG,
                recording = true,
                lumaLevel = 0.18f,
                estimatedIso = iso,
                motionScore = 0.08f,
                skinToneConfidence = 0.2f,
                textureConfidence = textureConfidence,
                edgeDensity = 0.22f,
                metrics = VideoNoiseMetrics(
                    chromaNoise = 0.55f,
                    lumaNoise = 0.42f,
                    temporalInstability = 0.20f,
                    frameToFrameRgbVariance = 0.47f
                ),
                strictHighEnd = true
            )
        )
}
