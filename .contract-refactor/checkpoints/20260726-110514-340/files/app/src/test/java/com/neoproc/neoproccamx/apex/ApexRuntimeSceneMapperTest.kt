package com.neoproc.neoproccamx.apex

import com.neoproc.neoproccamx.camera.LiveResult
import com.neoproc.neoproccamx.pipeline.CaptureRecommendation
import com.neoproc.neoproccamx.pipeline.SceneAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexRuntimeSceneMapperTest {

    @Test
    fun mapsPipelineSceneToAdaptiveApexMetrics() {
        val scene = SceneAnalysis(
            averageLuma = 0.34f,
            estimatedLux = 18f,
            motionScore = 0.22f,
            edgeDensity = 0.28f,
            dynamicRangeScore = 0.78f,
            shadowRatio = 0.42f,
            highlightRatio = 0.21f,
            timestamp = 123_456_789L,
            normalizedNoiseFloor = 0.46f,
            flatRegionStdDev = 12.0f,
            realIsoNoiseFloor = 2_200f,
            perceptualSharpness = 0.34f
        )
        val recommendation = CaptureRecommendation(
            scene = scene,
            isoCeiling = 1_250,
            targetExposureNs = 24_000_000L,
            stackFrameCount = 5,
            temporalNoiseReduction = true,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2),
            sharpeningBoost = 0.31f
        )
        val liveResult = LiveResult(
            iso = 1_600,
            exposureNs = 18_000_000L,
            afState = 0,
            aeState = 0,
            awbState = 0,
            focusDist = 0.8f
        )

        val metrics = ApexRuntimeSceneMapper.fromPipeline(
            scene = scene,
            recommendation = recommendation,
            liveResult = liveResult
        )

        assertEquals(123_456_789L, metrics.frameTimestampNs)
        assertEquals(1_600, metrics.iso)
        assertEquals(18_000_000L, metrics.exposureTimeNs)
        assertTrue(metrics.dynamicRangeScore > 0.70f)
        assertTrue(metrics.shadowNeed > 0.30f)
        assertTrue(metrics.noiseRisk > 0.35f)
        assertTrue(metrics.chromaNoiseRisk > 0.35f)
        assertTrue(metrics.localContrastScore > 0.20f)
        assertTrue(metrics.histogramSpread > 0f)
        assertTrue(metrics.perceptualStopEstimate > 0f)
        assertTrue(metrics.p99Nits > metrics.p95Nits)
        assertTrue(metrics.p95Nits > metrics.p50Nits)
        assertTrue(metrics.sceneWhite in 0.18f..0.82f)
    }

    @Test
    fun fallsBackToLiveCaptureMetadataWhenSceneAnalysisIsUnavailable() {
        val metrics = ApexRuntimeSceneMapper.fromPipeline(
            scene = null,
            recommendation = null,
            liveResult = LiveResult(
                iso = 3_200,
                exposureNs = 33_333_333L,
                afState = 0,
                aeState = 0,
                awbState = 0,
                focusDist = 0f
            )
        )

        assertEquals(3_200, metrics.iso)
        assertEquals(33_333_333L, metrics.exposureTimeNs)
        assertTrue(metrics.noiseRisk >= 0.80f)
        assertTrue(metrics.shadowNeed > 0f)
        assertTrue(metrics.sceneWhite >= 0.18f)
        assertTrue(metrics.localContrastScore > 0f)
    }
}
