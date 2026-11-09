package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDecisionEngineTest {

    private val engine = SceneDecisionEngine()

    @Test
    fun brightLightScene_prefersLowIsoFastExposureAndSingleCapture() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.72f,
                estimatedLux = 520f,
                motionScore = 0.01f,
                edgeDensity = 0.22f,
                dynamicRangeScore = 0.32f,
                shadowRatio = 0.03f,
                highlightRatio = 0.07f
            )
        )

        assertEquals(50, recommendation.isoCeiling)
        assertEquals(1, recommendation.stackFrameCount)
        assertFalse(recommendation.temporalNoiseReduction)
        assertTrue(recommendation.targetExposureNs <= 1_000_000_000L / 320L)
        assertTrue(recommendation.wbLockSuggested)
        assertEquals(SceneStillStrategy.SINGLE, engine.chooseStillStrategy(false, false, recommendation))
    }

    @Test
    fun brightLightScene_inStrictHighEnd_keepsTemporalNoiseReductionEnabled() {
        val strictEngine = SceneDecisionEngine(strictHighEnd = true)
        val recommendation = strictEngine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.72f,
                estimatedLux = 520f,
                motionScore = 0.01f,
                edgeDensity = 0.22f,
                dynamicRangeScore = 0.32f,
                shadowRatio = 0.03f,
                highlightRatio = 0.07f
            )
        )

        assertTrue(recommendation.temporalNoiseReduction)
        assertTrue(recommendation.shadowDenoiseStrength >= 0.42f)
        assertTrue(recommendation.spatialDenoiseFloor >= 0.70f)
        assertTrue(recommendation.sharpeningBoost >= 0.10f)
        assertEquals(1.0f, recommendation.detailPreservationStrength, 0.0001f)
    }

    @Test
    fun strictHighEndBrightNoisyScene_doesNotCrashOnInvertedIsoCeilingRange() {
        // Regression: bright daylight (isoCeiling base 50) with a high measured noise floor drives
        // isoCeiling below 50 via the noise subtraction on the base ceiling. The strict-high-end
        // branch used coerceIn(50, isoCeiling), which inverts (min 50 > max e.g. 26) and threw
        // IllegalArgumentException on the FramePipeline thread, closing the whole app whenever the
        // analysis stream (monitoring/scopes) was active in bright light. Must not throw, and the
        // tuned ceiling must stay at the sane floor of 50.
        val strictEngine = SceneDecisionEngine(strictHighEnd = true)
        val recommendation = strictEngine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.72f,
                estimatedLux = 520f,
                motionScore = 0.01f,
                edgeDensity = 0.22f,
                dynamicRangeScore = 0.32f,
                shadowRatio = 0.03f,
                highlightRatio = 0.07f,
                normalizedNoiseFloor = 0.95f,
                flatRegionStdDev = 11.0f,
                realIsoNoiseFloor = 2_000f
            )
        )
        assertTrue("isoCeiling must stay >= 50", recommendation.isoCeiling >= 50)
    }

    @Test
    fun veryLowLightStaticScene_prefersDeepFrameStacking() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.11f,
                estimatedLux = 8f,
                motionScore = 0.01f,
                edgeDensity = 0.12f,
                dynamicRangeScore = 0.28f,
                shadowRatio = 0.34f,
                highlightRatio = 0.01f
            )
        )

        assertTrue(recommendation.isoCeiling <= 320)
        assertTrue(recommendation.targetExposureNs >= 1_000_000_000L / 20L)
        assertTrue(recommendation.stackFrameCount >= 10)
        assertTrue(recommendation.temporalNoiseReduction)
        assertTrue(recommendation.shadowDenoiseStrength >= 0.80f)
        assertEquals(SceneStillStrategy.FRAME_STACK, engine.chooseStillStrategy(false, false, recommendation))
    }

    @Test
    fun lowLightMotionScene_reducesExposureAndStackDepth() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.18f,
                estimatedLux = 18f,
                motionScore = 0.16f,
                edgeDensity = 0.20f,
                dynamicRangeScore = 0.30f,
                shadowRatio = 0.24f,
                highlightRatio = 0.02f
            )
        )

        assertTrue(recommendation.isoCeiling <= 400)
        assertTrue(recommendation.targetExposureNs <= 1_000_000_000L / 120L)
        assertEquals(6, recommendation.stackFrameCount)
        assertTrue(recommendation.sharpeningBoost >= 0.15f)
    }

    @Test
    fun highDynamicRangeStaticScene_requestsWideBracket() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.46f,
                estimatedLux = 110f,
                motionScore = 0.02f,
                edgeDensity = 0.19f,
                dynamicRangeScore = 0.72f,
                shadowRatio = 0.24f,
                highlightRatio = 0.10f
            )
        )

        assertEquals(listOf(-2, -1, 0, 1, 2), recommendation.hdrBracketEvs)
        assertTrue(recommendation.hdrTargetFrameCount >= 5)
        assertTrue(recommendation.skyProtectionStrength >= 0.70f)
        assertEquals(SceneStillStrategy.HDR_FUSION, engine.chooseStillStrategy(false, false, recommendation))
    }

    @Test
    fun highDynamicRangeMotionScene_requestsCompactBracket() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.42f,
                estimatedLux = 95f,
                motionScore = 0.14f,
                edgeDensity = 0.23f,
                dynamicRangeScore = 0.70f,
                shadowRatio = 0.20f,
                highlightRatio = 0.11f
            )
        )

        assertEquals(listOf(-1, 0, 1), recommendation.hdrBracketEvs)
        assertEquals(3, recommendation.hdrTargetFrameCount)
        assertEquals(SceneStillStrategy.FRAME_STACK, engine.chooseStillStrategy(false, false, recommendation))
    }

    @Test
    fun extremeDynamicRangeStableScene_requestsExtendedBracketAndFrameTarget() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.48f,
                estimatedLux = 130f,
                motionScore = 0.005f,
                edgeDensity = 0.21f,
                dynamicRangeScore = 0.88f,
                shadowRatio = 0.27f,
                highlightRatio = 0.16f
            )
        )

        assertEquals(listOf(-3, -2, -1, 0, 1, 2, 3), recommendation.hdrBracketEvs)
        assertTrue(recommendation.hdrTargetFrameCount >= 9)
        assertTrue(recommendation.skyProtectionStrength >= 0.90f)
    }

    @Test
    fun rawRequest_bypassesSmartJpegPipeline() {
        val recommendation = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.09f,
                estimatedLux = 6f,
                motionScore = 0.0f,
                edgeDensity = 0.10f,
                dynamicRangeScore = 0.65f,
                shadowRatio = 0.30f,
                highlightRatio = 0.08f
            )
        )

        assertEquals(SceneStillStrategy.RAW_SINGLE, engine.chooseStillStrategy(true, true, recommendation))
    }

    @Test
    fun analyze_usesStdDevNoiseMapForFlatSensorNoise() {
        val histogram = IntArray(256).apply { this[128] = 64 * 64 }
        val clean = frame(
            width = 64,
            height = 64
        ) { 128 }
        val noisy = frame(
            width = 64,
            height = 64
        ) { index ->
            128 + (((index * 7) % 11) - 5)
        }

        val cleanAnalysis = engine.analyze(clean, histogram, averageLuma = 0.50f, previousLumaFrame = null)
        val noisyAnalysis = engine.analyze(noisy, histogram, averageLuma = 0.50f, previousLumaFrame = null)

        assertTrue(noisyAnalysis.flatRegionStdDev > cleanAnalysis.flatRegionStdDev)
        assertTrue(noisyAnalysis.normalizedNoiseFloor > cleanAnalysis.normalizedNoiseFloor)
        assertTrue(noisyAnalysis.realIsoNoiseFloor > cleanAnalysis.realIsoNoiseFloor)
    }

    @Test
    fun noisyScene_recommendation_raisesDenoiseAndTrimsIsoCeiling() {
        val clean = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.38f,
                estimatedLux = 56f,
                motionScore = 0.02f,
                edgeDensity = 0.16f,
                dynamicRangeScore = 0.34f,
                shadowRatio = 0.14f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.04f,
                flatRegionStdDev = 1.6f,
                realIsoNoiseFloor = 130f,
                perceptualSharpness = 0.58f
            )
        )
        val noisy = engine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.38f,
                estimatedLux = 56f,
                motionScore = 0.02f,
                edgeDensity = 0.16f,
                dynamicRangeScore = 0.34f,
                shadowRatio = 0.14f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.82f,
                flatRegionStdDev = 8.9f,
                realIsoNoiseFloor = 1500f,
                perceptualSharpness = 0.26f
            )
        )

        assertTrue(noisy.shadowDenoiseStrength > clean.shadowDenoiseStrength)
        assertTrue(noisy.spatialDenoiseFloor > clean.spatialDenoiseFloor)
        assertTrue(noisy.isoCeiling < clean.isoCeiling)
    }

    @Test
    fun strictHighEndNoisyScene_pushesRecommendationNearZeroNoiseFloors() {
        val strictEngine = SceneDecisionEngine(strictHighEnd = true)
        val recommendation = strictEngine.buildCaptureRecommendation(
            scene(
                averageLuma = 0.30f,
                estimatedLux = 34f,
                motionScore = 0.03f,
                edgeDensity = 0.11f,
                dynamicRangeScore = 0.46f,
                shadowRatio = 0.24f,
                highlightRatio = 0.04f,
                normalizedNoiseFloor = 0.74f,
                flatRegionStdDev = 10.5f,
                realIsoNoiseFloor = 2_600f,
                perceptualSharpness = 0.22f
            )
        )

        assertTrue("shadowDenoiseStrength=${recommendation.shadowDenoiseStrength}", recommendation.shadowDenoiseStrength >= 0.9995f)
        assertTrue("spatialDenoiseFloor=${recommendation.spatialDenoiseFloor}", recommendation.spatialDenoiseFloor >= 0.992f)
        assertTrue(recommendation.temporalNoiseReduction)
        assertEquals(1.0f, recommendation.detailPreservationStrength, 0.0001f)
    }

    private fun scene(
        averageLuma: Float,
        estimatedLux: Float,
        motionScore: Float,
        edgeDensity: Float,
        dynamicRangeScore: Float,
        shadowRatio: Float,
        highlightRatio: Float,
        normalizedNoiseFloor: Float = 0f,
        flatRegionStdDev: Float = 0f,
        realIsoNoiseFloor: Float = 100f,
        perceptualSharpness: Float = 0f
    ): SceneAnalysis = SceneAnalysis(
        averageLuma = averageLuma,
        estimatedLux = estimatedLux,
        motionScore = motionScore,
        edgeDensity = edgeDensity,
        dynamicRangeScore = dynamicRangeScore,
        shadowRatio = shadowRatio,
        highlightRatio = highlightRatio,
        timestamp = 0L,
        normalizedNoiseFloor = normalizedNoiseFloor,
        flatRegionStdDev = flatRegionStdDev,
        realIsoNoiseFloor = realIsoNoiseFloor,
        perceptualSharpness = perceptualSharpness
    )

    private fun frame(
        width: Int,
        height: Int,
        lumaAt: (Int) -> Int
    ): RawYuvFrame {
        val yBytes = ByteArray(width * height) { index ->
            lumaAt(index).coerceIn(0, 255).toByte()
        }
        return RawYuvFrame(
            width = width,
            height = height,
            yBytes = yBytes,
            uBytes = ByteArray(0),
            vBytes = ByteArray(0),
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = 0,
            vRowStride = 0,
            uPixelStride = 0,
            vPixelStride = 0,
            timestamp = 0L
        )
    }
}
