package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SceneRenderProfileEnginePhase4Test {

    private val engine = SceneRenderProfileEngine()

    @Test
    fun sceneRenderEngineAppliesPhase4ImageQualityPolicy() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(
            "SceneRenderProfileEngine must apply the Phase 4 final image-quality contract.",
            source.contains("VideoImageQualityPipelinePolicy.resolve(")
        )
    }

    @Test
    fun selectedAndNativeDimensionsAreTruthfullyPropagatedToDetailContract() {
        withStrictHighEnd {
            val scene = scene(
                averageLuma = 0.42f,
                estimatedLux = 90f,
                motionScore = 0.02f,
                edgeDensity = 0.16f,
                dynamicRangeScore = 0.50f,
                shadowRatio = 0.12f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.05f,
                flatRegionStdDev = 1.2f,
                realIsoNoiseFloor = 160f,
                perceptualSharpness = 0.65f
            )
            val recommendation = recommendation(
                sharpeningBoost = 0.04f,
                hdrBracketEvs = emptyList()
            )
            val trueNative8k = engine.build(
                scene = scene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                outputWidth = 7680,
                outputHeight = 4320,
                sourceWidth = 7680,
                sourceHeight = 4320
            )
            val upscaled8k = engine.build(
                scene = scene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                outputWidth = 7680,
                outputHeight = 4320,
                sourceWidth = 3840,
                sourceHeight = 2160
            )

            assertEquals(
                "TRUE_NATIVE_7680x4320_NO_FAKE_SUPER_RESOLUTION",
                trueNative8k.detailSharpeningMetadata["detailTrueResolutionClaim"]
            )
            assertEquals(
                "PERCEPTUAL_DETAIL_ONLY_NATIVE_3840x2160_OUTPUT_7680x4320_NOT_TRUE_NATIVE_NO_PIXEL_DUPLICATION",
                upscaled8k.detailSharpeningMetadata["detailTrueResolutionClaim"]
            )
        }
    }

    @Test
    fun cleanTexturedRecordingDoesNotForceNearTotalSmoothing() {
        withStrictHighEnd {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.48f,
                    estimatedLux = 120f,
                    motionScore = 0.02f,
                    edgeDensity = 0.24f,
                    dynamicRangeScore = 0.46f,
                    shadowRatio = 0.10f,
                    highlightRatio = 0.04f,
                    normalizedNoiseFloor = 0.03f,
                    flatRegionStdDev = 1.0f,
                    realIsoNoiseFloor = 120f,
                    perceptualSharpness = 0.76f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.06f,
                    hdrBracketEvs = emptyList()
                ),
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true
            )

            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise < 0.86f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise < 0.94f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening >= 0.42f)
        }
    }

    @Test
    fun phase4HdrRecordingSuppressesRgbGrainAndKeepsHdrToneSafe() {
        withStrictHighEnd {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.24f,
                    estimatedLux = 3.2f,
                    motionScore = 0.18f,
                    edgeDensity = 0.08f,
                    dynamicRangeScore = 0.90f,
                    shadowRatio = 0.38f,
                    highlightRatio = 0.21f,
                    normalizedNoiseFloor = 0.93f,
                    flatRegionStdDev = 15.5f,
                    realIsoNoiseFloor = 42_000f,
                    perceptualSharpness = 0.20f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.07f,
                    hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
                ).copy(
                    localToneMapStrength = 0.62f,
                    shadowDenoiseStrength = 0.74f,
                    spatialDenoiseFloor = 0.60f,
                    detailPreservationStrength = 0.35f,
                    skyProtectionStrength = 0.62f
                ),
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HDR10",
                recording = true
            )

            assertEquals(0f, profile.logStrength, 0.0001f)
            assertTrue("videoHdrFusionStrength=${profile.videoHdrFusionStrength}", profile.videoHdrFusionStrength >= 0.70f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise in 0.70f..0.84f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise in 0.80f..0.94f)
            assertTrue("highlightCompression=${profile.highlightCompression}", profile.highlightCompression >= 0.38f)
            assertTrue("saturation=${profile.saturation}", profile.saturation <= 1.04f)
            assertTrue("contrast=${profile.contrast}", profile.contrast <= 1.03f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening <= 0.88f)
        }
    }

    @Test
    fun phase4LogRecordingCannotInheritHdrFusionOrHdrContrast() {
        withStrictHighEnd {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.35f,
                    estimatedLux = 24f,
                    motionScore = 0.04f,
                    edgeDensity = 0.18f,
                    dynamicRangeScore = 0.84f,
                    shadowRatio = 0.26f,
                    highlightRatio = 0.18f,
                    normalizedNoiseFloor = 0.32f,
                    flatRegionStdDev = 5.8f,
                    realIsoNoiseFloor = 1_800f,
                    perceptualSharpness = 0.40f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.08f,
                    hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
                ).copy(
                    localToneMapStrength = 0.50f,
                    shadowDenoiseStrength = 0.38f,
                    detailPreservationStrength = 0.64f,
                    skyProtectionStrength = 0.42f
                ),
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "Sony S-Log3",
                hdrMode = "HDR10",
                recording = true
            )

            assertEquals(0f, profile.videoHdrFusionStrength, 0.0001f)
            assertTrue("logStrength=${profile.logStrength}", profile.logStrength >= 0.68f)
            assertTrue("contrast=${profile.contrast}", profile.contrast <= 0.76f)
            assertTrue("saturation=${profile.saturation}", profile.saturation <= 0.82f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening <= 0.88f)
            assertEquals(0f, profile.ditherStrength, 0.0001f)
        }
    }

    private fun withStrictHighEnd(block: () -> Unit) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
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
    ) = SceneAnalysis(
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

    private fun recommendation(
        sharpeningBoost: Float,
        hdrBracketEvs: List<Int>
    ): CaptureRecommendation {
        val scene = scene(0.4f, 80f, 0.02f, 0.2f, 0.4f, 0.1f, 0.03f)
        return CaptureRecommendation(
            scene = scene,
            isoCeiling = 200,
            targetExposureNs = 16_666_667L,
            stackFrameCount = 1,
            temporalNoiseReduction = true,
            hdrBracketEvs = hdrBracketEvs,
            sharpeningBoost = sharpeningBoost
        )
    }
}
