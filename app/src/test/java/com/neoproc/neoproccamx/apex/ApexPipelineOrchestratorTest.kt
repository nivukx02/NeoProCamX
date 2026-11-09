package com.neoproc.neoproccamx.apex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexPipelineOrchestratorTest {

    @Test
    fun injectApexUniformsWritesSceneAdaptiveToneDetailAndNoiseControls() {
        val grade = floatArrayOf(
            1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
            0f, 0f, 0f, 0f,
            50f, 0f, 1f, 1f
        )
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.82f,
            shadowNeed = 0.64f,
            noiseRisk = 0.72f,
            chromaNoiseRisk = 0.68f,
            motionScore = 0.18f,
            sceneWhite = 0.42f
        )
        val params = ApexProfileParams.compute(metrics, ApexMode.SHARP)

        ApexPipelineOrchestrator.injectApexUniforms(grade, params, metrics)

        assertEquals(params.sourcePeakNits, grade[4], 0.001f)
        assertEquals(params.targetPeakNits, grade[5], 0.001f)
        assertEquals(params.kneeRatio, grade[6], 0.001f)
        assertEquals(params.shoulderStrength, grade[7], 0.001f)
        assertEquals(params.shadowLift, grade[8], 0.0001f)
        assertEquals(params.detailGain1, grade[9], 0.0001f)
        assertEquals(params.colorVolumeCompression, grade[10], 0.0001f)
        assertEquals(params.saturationPreservation, grade[11], 0.0001f)
        assertEquals(params.baseChromaDenoise, grade[13], 0.0001f)
        assertEquals(0.24f, grade[3], 0.0001f)
        assertEquals(metrics.noiseRisk, grade[14], 0.0001f)
        assertEquals(metrics.motionScore, grade[15], 0.0001f)
        assertTrue(grade.all { it.isFinite() })
    }

    @Test
    fun zeroModeWritesNegativeDetailGainForNoiseSuppression() {
        val grade = FloatArray(16) { 0f }
        grade[0] = 1f
        grade[1] = 1f
        grade[12] = 51f
        val metrics = SceneMetrics(noiseRisk = 0.55f, motionScore = 0.1f, sceneWhite = 0.24f)
        val params = ApexProfileParams.compute(metrics, ApexMode.ZERO)

        ApexPipelineOrchestrator.injectApexUniforms(grade, params, metrics)

        assertTrue(grade[9] < 0f)
        assertEquals(metrics.noiseRisk, grade[14], 0.0001f)
    }

    @Test
    fun buildPassPlanKeepsPreviewAndRecordingSignatureDeterministic() {
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.70f,
            shadowNeed = 0.44f,
            localContrastScore = 0.62f,
            noiseRisk = 0.08f,
            motionScore = 0.12f,
            sceneWhite = 0.30f
        )
        val params = ApexProfileParams.compute(metrics, ApexMode.SHARP)

        val previewPlan = ApexPipelineOrchestrator.buildPassPlan(params, metrics)
        val recordingPlan = ApexPipelineOrchestrator.buildPassPlan(params, metrics)

        assertEquals(previewPlan.previewRecordingSignature, recordingPlan.previewRecordingSignature)
        assertTrue(previewPlan.passes.contains(ApexPipelineOrchestrator.ApexVulkanPass.ICTCP_CONVERT))
        assertTrue(previewPlan.passes.contains(ApexPipelineOrchestrator.ApexVulkanPass.MULTI_SCALE_DETAIL))
        assertTrue(previewPlan.passes.contains(ApexPipelineOrchestrator.ApexVulkanPass.TONE_MAP_DITHER))
    }

    @Test
    fun passPlanSignatureChangesWhenShaderConsumedApexUniformsChange() {
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.70f,
            shadowNeed = 0.44f,
            localContrastScore = 0.62f,
            noiseRisk = 0.08f,
            motionScore = 0.12f,
            sceneWhite = 0.30f
        )
        val params = ApexProfileParams.compute(metrics, ApexMode.CINEMA)
        val base = ApexPipelineOrchestrator.buildPassPlan(params, metrics)

        assertTrue(
            base.previewRecordingSignature != ApexPipelineOrchestrator
                .buildPassPlan(params.copy(colorVolumeCompression = params.colorVolumeCompression + 0.01f), metrics)
                .previewRecordingSignature
        )
        assertTrue(
            base.previewRecordingSignature != ApexPipelineOrchestrator
                .buildPassPlan(params.copy(saturationPreservation = params.saturationPreservation - 0.01f), metrics)
                .previewRecordingSignature
        )
        assertTrue(
            base.previewRecordingSignature != ApexPipelineOrchestrator
                .buildPassPlan(params.copy(baseChromaDenoise = params.baseChromaDenoise - 0.01f), metrics)
                .previewRecordingSignature
        )
    }
}
