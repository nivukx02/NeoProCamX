package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.color.VideoLogPreviewParityPolicy
import com.neoproc.neoproccamx.color.NeoTenBitHardwareSupport
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.ImaxStrictProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRenderProfileEngineTest {

    private val engine = SceneRenderProfileEngine()

    @Test
    fun lowLightScene_boostsNoiseReductionAndShadowProcessing() {
        val profile = engine.build(
            scene = scene(averageLuma = 0.11f, estimatedLux = 9f, motionScore = 0.01f, edgeDensity = 0.12f, dynamicRangeScore = 0.34f, shadowRatio = 0.32f, highlightRatio = 0.02f),
            recommendation = recommendation(sharpeningBoost = 0.05f, hdrBracketEvs = emptyList()),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Natural"
        )

        assertTrue(profile.temporalBlend > 0.2f)
        assertTrue(profile.spatialDenoise > 0.25f)
        assertTrue(profile.chromaDenoise > 0.35f)
        assertTrue(profile.shadowBoost > 0.4f)
    }

    @Test
    fun motionScene_reducesTemporalBlendAnd_preservesDetail() {
        val motionProfile = engine.build(
            scene = scene(averageLuma = 0.28f, estimatedLux = 35f, motionScore = 0.18f, edgeDensity = 0.22f, dynamicRangeScore = 0.28f, shadowRatio = 0.18f, highlightRatio = 0.02f),
            recommendation = recommendation(sharpeningBoost = 0.18f, hdrBracketEvs = emptyList()),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Natural"
        )
        val stableProfile = engine.build(
            scene = scene(averageLuma = 0.28f, estimatedLux = 35f, motionScore = 0.01f, edgeDensity = 0.22f, dynamicRangeScore = 0.28f, shadowRatio = 0.18f, highlightRatio = 0.02f),
            recommendation = recommendation(sharpeningBoost = 0.18f, hdrBracketEvs = emptyList()),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Natural"
        )

        assertTrue(motionProfile.temporalBlend < stableProfile.temporalBlend)
        assertTrue(motionProfile.sharpening >= stableProfile.sharpening)
    }

    @Test
    fun brightFlatScene_biasesTowardNoiseSuppressionInsteadOfOverSharpening() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val flat = engine.build(
                scene = scene(averageLuma = 0.58f, estimatedLux = 180f, motionScore = 0.02f, edgeDensity = 0.06f, dynamicRangeScore = 0.32f, shadowRatio = 0.10f, highlightRatio = 0.04f),
                recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = emptyList()),
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural"
            )
            val textured = engine.build(
                scene = scene(averageLuma = 0.58f, estimatedLux = 180f, motionScore = 0.02f, edgeDensity = 0.28f, dynamicRangeScore = 0.32f, shadowRatio = 0.10f, highlightRatio = 0.04f),
                recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = emptyList()),
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural"
            )

            assertTrue(flat.spatialDenoise >= textured.spatialDenoise)
            assertTrue(flat.chromaDenoise > textured.chromaDenoise)
            assertTrue(flat.sharpening < textured.sharpening)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun v13CleanDetailedSceneRaisesPreviewAndRecordingSharpnessTogether() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val cleanDetailed = scene(
                averageLuma = 0.56f,
                estimatedLux = 190f,
                motionScore = 0.02f,
                edgeDensity = 0.38f,
                dynamicRangeScore = 0.40f,
                shadowRatio = 0.08f,
                highlightRatio = 0.04f,
                normalizedNoiseFloor = 0.02f,
                flatRegionStdDev = 1.0f,
                realIsoNoiseFloor = 120f,
                perceptualSharpness = 0.84f
            )
            val cleanRecommendation = recommendation(
                sharpeningBoost = 0.10f,
                hdrBracketEvs = emptyList()
            ).copy(
                spatialDenoiseFloor = 0.22f,
                detailPreservationStrength = 0.90f
            )

            val preview = engine.build(
                scene = cleanDetailed,
                recommendation = cleanRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = cleanDetailed,
                recommendation = cleanRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            )

            assertTrue("preview.sharpening=${preview.sharpening}", preview.sharpening >= 0.48f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.48f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.09f
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun cleanDetailedNormalVideoKeepsPreviewAndRecordingOnEliteSdrDetailPath() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val cleanDetailed = scene(
                averageLuma = 0.56f,
                estimatedLux = 190f,
                motionScore = 0.02f,
                edgeDensity = 0.40f,
                dynamicRangeScore = 0.42f,
                shadowRatio = 0.08f,
                highlightRatio = 0.04f,
                normalizedNoiseFloor = 0.02f,
                flatRegionStdDev = 1.0f,
                realIsoNoiseFloor = 120f,
                perceptualSharpness = 0.88f
            )
            val cleanRecommendation = recommendation(
                sharpeningBoost = 0.14f,
                hdrBracketEvs = emptyList()
            ).copy(
                spatialDenoiseFloor = 0.22f,
                detailPreservationStrength = 0.96f
            )

            val preview = engine.build(
                scene = cleanDetailed,
                recommendation = cleanRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = cleanDetailed,
                recommendation = cleanRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            )

            assertTrue("preview.sharpening=${preview.sharpening}", preview.sharpening >= 0.74f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.78f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.08f
            )
            assertEquals(0f, preview.videoHdrFusionStrength, 0.0001f)
            assertEquals(0f, recording.videoHdrFusionStrength, 0.0001f)
            assertEquals(0f, preview.logStrength, 0.0001f)
            assertEquals(0f, recording.logStrength, 0.0001f)
            assertTrue("preview.contrast=${preview.contrast}", preview.contrast >= 0.94f)
            assertTrue("preview.saturation=${preview.saturation}", preview.saturation >= 0.94f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictSdrPreviewAndRecordingKeepMatchedToneColorAndFineDetail() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val detailedSdrScene = scene(
                averageLuma = 0.52f,
                estimatedLux = 160f,
                motionScore = 0.04f,
                edgeDensity = 0.34f,
                dynamicRangeScore = 0.34f,
                shadowRatio = 0.10f,
                highlightRatio = 0.04f,
                normalizedNoiseFloor = 0.04f,
                flatRegionStdDev = 1.8f,
                realIsoNoiseFloor = 180f,
                perceptualSharpness = 0.82f
            )
            val detailedRecommendation = recommendation(
                sharpeningBoost = 0.12f,
                hdrBracketEvs = emptyList()
            ).copy(
                spatialDenoiseFloor = 0.24f,
                detailPreservationStrength = 0.94f
            )

            val preview = engine.build(
                scene = detailedSdrScene,
                recommendation = detailedRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Rec.709",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = detailedSdrScene,
                recommendation = detailedRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Rec.709",
                recording = true,
                previewFps = 30f
            )

            assertEquals(0f, preview.videoHdrFusionStrength, 0.0001f)
            assertEquals(0f, recording.videoHdrFusionStrength, 0.0001f)
            assertEquals(0f, preview.logStrength, 0.0001f)
            assertEquals(0f, recording.logStrength, 0.0001f)
            assertEquals("contrast drift", preview.contrast, recording.contrast, 0.018f)
            assertEquals("saturation drift", preview.saturation, recording.saturation, 0.018f)
            assertEquals("warmth drift", preview.warmth, recording.warmth, 0.004f)
            assertEquals("highlight compression drift", preview.highlightCompression, recording.highlightCompression, 0.030f)
            assertTrue("preview.sharpening=${preview.sharpening}", preview.sharpening >= 0.84f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.84f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.035f
            )
            assertTrue("preview.spatialDenoise=${preview.spatialDenoise}", preview.spatialDenoise >= 0.72f)
            assertTrue("recording.spatialDenoise=${recording.spatialDenoise}", recording.spatialDenoise >= 0.72f)
            assertTrue("recording.chromaDenoise=${recording.chromaDenoise}", recording.chromaDenoise >= 0.82f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictSdrCameraMotionDoesNotRetintNormalVideoColor() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val stableScene = scene(
                averageLuma = 0.50f,
                estimatedLux = 145f,
                motionScore = 0.02f,
                edgeDensity = 0.30f,
                dynamicRangeScore = 0.32f,
                shadowRatio = 0.12f,
                highlightRatio = 0.04f,
                normalizedNoiseFloor = 0.05f,
                flatRegionStdDev = 2.0f,
                realIsoNoiseFloor = 220f,
                perceptualSharpness = 0.78f
            )
            val tiltedScene = stableScene.copy(motionScore = 0.42f)
            val cleanRecommendation = recommendation(
                sharpeningBoost = 0.10f,
                hdrBracketEvs = emptyList()
            ).copy(
                spatialDenoiseFloor = 0.24f,
                detailPreservationStrength = 0.92f
            )

            val stable = engine.build(
                scene = stableScene,
                recommendation = cleanRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = false,
                previewFps = 30f
            )
            val tilted = engine.build(
                scene = tiltedScene,
                recommendation = cleanRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = false,
                previewFps = 30f
            )

            assertEquals("contrast changed with motion", stable.contrast, tilted.contrast, 0.006f)
            assertEquals("saturation changed with motion", stable.saturation, tilted.saturation, 0.006f)
            assertEquals("warmth changed with motion", stable.warmth, tilted.warmth, 0.002f)
            assertEquals("highlight curve changed with motion", stable.highlightCompression, tilted.highlightCompression, 0.012f)
            assertTrue(
                "tilted.sharpening=${tilted.sharpening} stable.sharpening=${stable.sharpening}",
                tilted.sharpening >= stable.sharpening - 0.040f
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictSdrModerateNoiseRecordingKeepsPreviewToneAndReadableDetail() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val moderateNoiseScene = scene(
                averageLuma = 0.42f,
                estimatedLux = 48f,
                motionScore = 0.06f,
                edgeDensity = 0.24f,
                dynamicRangeScore = 0.44f,
                shadowRatio = 0.18f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.38f,
                flatRegionStdDev = 7.4f,
                realIsoNoiseFloor = 1_500f,
                perceptualSharpness = 0.64f
            )
            val recommendation = recommendation(
                sharpeningBoost = 0.09f,
                hdrBracketEvs = emptyList()
            ).copy(
                shadowDenoiseStrength = 0.72f,
                spatialDenoiseFloor = 0.42f,
                detailPreservationStrength = 0.84f
            )

            val preview = engine.build(
                scene = moderateNoiseScene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Rec.709",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = moderateNoiseScene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Rec.709",
                recording = true,
                previewFps = 30f
            )

            assertEquals("contrast drift", preview.contrast, recording.contrast, 0.010f)
            assertEquals("saturation drift", preview.saturation, recording.saturation, 0.010f)
            assertEquals("warmth drift", preview.warmth, recording.warmth, 0.002f)
            assertEquals("highlight compression drift", preview.highlightCompression, recording.highlightCompression, 0.018f)
            assertTrue("recording.spatialDenoise=${recording.spatialDenoise}", recording.spatialDenoise >= 0.92f)
            assertTrue("recording.chromaDenoise=${recording.chromaDenoise}", recording.chromaDenoise >= 0.94f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.72f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.065f
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictSdrModerateNoiseMotionDoesNotRetintRecordingColor() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val stableScene = scene(
                averageLuma = 0.44f,
                estimatedLux = 55f,
                motionScore = 0.03f,
                edgeDensity = 0.22f,
                dynamicRangeScore = 0.42f,
                shadowRatio = 0.16f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.36f,
                flatRegionStdDev = 7.0f,
                realIsoNoiseFloor = 1_350f,
                perceptualSharpness = 0.58f
            )
            val movingScene = stableScene.copy(motionScore = 0.46f)
            val recommendation = recommendation(
                sharpeningBoost = 0.08f,
                hdrBracketEvs = emptyList()
            ).copy(
                shadowDenoiseStrength = 0.70f,
                spatialDenoiseFloor = 0.42f,
                detailPreservationStrength = 0.80f
            )

            val stable = engine.build(
                scene = stableScene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            )
            val moving = engine.build(
                scene = movingScene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            )

            assertEquals("contrast changed with motion", stable.contrast, moving.contrast, 0.006f)
            assertEquals("saturation changed with motion", stable.saturation, moving.saturation, 0.006f)
            assertEquals("warmth changed with motion", stable.warmth, moving.warmth, 0.002f)
            assertEquals("highlight curve changed with motion", stable.highlightCompression, moving.highlightCompression, 0.012f)
            assertTrue("moving.sharpening=${moving.sharpening}", moving.sharpening >= 0.68f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictSdrTransientExposureShiftDoesNotRetintOrSoftenRecording() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val balancedScene = scene(
                averageLuma = 0.44f,
                estimatedLux = 80f,
                motionScore = 0.04f,
                edgeDensity = 0.26f,
                dynamicRangeScore = 0.36f,
                shadowRatio = 0.14f,
                highlightRatio = 0.06f,
                normalizedNoiseFloor = 0.18f,
                flatRegionStdDev = 4.0f,
                realIsoNoiseFloor = 700f,
                perceptualSharpness = 0.66f
            )
            val transientHighlightScene = balancedScene.copy(
                averageLuma = 0.68f,
                dynamicRangeScore = 0.82f,
                highlightRatio = 0.42f,
                shadowRatio = 0.06f
            )
            val recommendation = recommendation(
                sharpeningBoost = 0.10f,
                hdrBracketEvs = emptyList()
            ).copy(
                spatialDenoiseFloor = 0.40f,
                detailPreservationStrength = 0.88f
            )

            val balanced = engine.build(
                scene = balancedScene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Rec.2020",
                recording = true,
                previewFps = 30f
            )
            val shifted = engine.build(
                scene = transientHighlightScene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Rec.2020",
                recording = true,
                previewFps = 30f
            )

            assertEquals("contrast retinted by exposure shift", balanced.contrast, shifted.contrast, 0.008f)
            assertEquals("saturation retinted by exposure shift", balanced.saturation, shifted.saturation, 0.008f)
            assertEquals("warmth retinted by exposure shift", balanced.warmth, shifted.warmth, 0.002f)
            assertEquals("highlight curve drifted too far", balanced.highlightCompression, shifted.highlightCompression, 0.040f)
            assertTrue("shifted.spatialDenoise=${shifted.spatialDenoise}", shifted.spatialDenoise >= 0.90f)
            assertTrue("shifted.chromaDenoise=${shifted.chromaDenoise}", shifted.chromaDenoise >= 0.94f)
            assertTrue("shifted.sharpening=${shifted.sharpening}", shifted.sharpening >= 0.82f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun v13NoisyRecordingKeepsSharpnessHaloSafe() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val noisy = engine.build(
                scene = scene(
                    averageLuma = 0.25f,
                    estimatedLux = 5f,
                    motionScore = 0.12f,
                    edgeDensity = 0.10f,
                    dynamicRangeScore = 0.64f,
                    shadowRatio = 0.38f,
                    highlightRatio = 0.08f,
                    normalizedNoiseFloor = 0.86f,
                    flatRegionStdDev = 14.0f,
                    realIsoNoiseFloor = 18_000f,
                    perceptualSharpness = 0.26f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.16f,
                    hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
                ).copy(
                    spatialDenoiseFloor = 0.78f,
                    detailPreservationStrength = 0.82f
                ),
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HDR10",
                recording = true,
                previewFps = 30f
            )

            assertTrue("noisy.sharpening=${noisy.sharpening}", noisy.sharpening in 0.18f..0.88f)
            assertTrue("noisy.chromaDenoise=${noisy.chromaDenoise}", noisy.chromaDenoise >= 0.99f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun ndr10PlusCleanDetailedSceneKeepsPreviewAndRecordingOnEliteDetailPath() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val detailedScene = scene(
                averageLuma = 0.52f,
                estimatedLux = 150f,
                motionScore = 0.03f,
                edgeDensity = 0.34f,
                dynamicRangeScore = 0.86f,
                shadowRatio = 0.18f,
                highlightRatio = 0.16f,
                normalizedNoiseFloor = 0.08f,
                flatRegionStdDev = 1.8f,
                realIsoNoiseFloor = 240f,
                perceptualSharpness = 0.82f
            )
            val detailedRecommendation = recommendation(
                sharpeningBoost = 0.16f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                spatialDenoiseFloor = 0.34f,
                detailPreservationStrength = 0.94f
            )

            val preview = engine.build(
                scene = detailedScene,
                recommendation = detailedRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "NDR10+",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = detailedScene,
                recommendation = detailedRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "NDR10+",
                recording = true,
                previewFps = 30f
            )

            assertTrue("preview.sharpening=${preview.sharpening}", preview.sharpening >= 0.34f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.36f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.09f
            )
            assertTrue("preview fusion=${preview.videoHdrFusionStrength}", preview.videoHdrFusionStrength >= 0.74f)
            assertTrue("recording fusion=${recording.videoHdrFusionStrength}", recording.videoHdrFusionStrength >= 0.74f)
            assertTrue("preview chroma=${preview.chromaDenoise}", preview.chromaDenoise >= 0.76f)
            assertTrue("recording chroma=${recording.chromaDenoise}", recording.chromaDenoise >= 0.76f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun nivukxVisionCleanDetailedSceneKeepsPreviewRecordingOnStrongerEliteDetailPath() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val detailedScene = scene(
                averageLuma = 0.54f,
                estimatedLux = 170f,
                motionScore = 0.02f,
                edgeDensity = 0.38f,
                dynamicRangeScore = 0.90f,
                shadowRatio = 0.16f,
                highlightRatio = 0.18f,
                normalizedNoiseFloor = 0.06f,
                flatRegionStdDev = 1.4f,
                realIsoNoiseFloor = 200f,
                perceptualSharpness = 0.88f
            )
            val detailedRecommendation = recommendation(
                sharpeningBoost = 0.18f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                spatialDenoiseFloor = 0.32f,
                detailPreservationStrength = 0.96f
            )

            val preview = engine.build(
                scene = detailedScene,
                recommendation = detailedRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "NIVUKX Vision",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = detailedScene,
                recommendation = detailedRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "NIVUKX Vision",
                recording = true,
                previewFps = 30f
            )

            assertTrue("preview.sharpening=${preview.sharpening}", preview.sharpening >= 0.48f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.58f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.10f
            )
            assertTrue("preview fusion=${preview.videoHdrFusionStrength}", preview.videoHdrFusionStrength >= 0.76f)
            assertTrue("recording fusion=${recording.videoHdrFusionStrength}", recording.videoHdrFusionStrength >= 0.78f)
            assertTrue("preview chroma=${preview.chromaDenoise}", preview.chromaDenoise >= 0.78f)
            assertTrue("recording chroma=${recording.chromaDenoise}", recording.chromaDenoise >= 0.80f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun hdrLogProfile_softensContrastAnd_rollsHighlights() {
        val hdrScene = scene(averageLuma = 0.44f, estimatedLux = 105f, motionScore = 0.03f, edgeDensity = 0.18f, dynamicRangeScore = 0.72f, shadowRatio = 0.22f, highlightRatio = 0.10f)
        val hdrRecommendation = recommendation(sharpeningBoost = 0.09f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2))
        val profile = engine.build(
            scene = hdrScene,
            recommendation = hdrRecommendation,
            logEnabled = true,
            hdrEnabled = true,
            colorProfile = "Cinematic"
        )
        val baseline = engine.build(
            scene = hdrScene,
            recommendation = hdrRecommendation,
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Natural"
        )

        assertTrue(
            "Expected log contrast ${profile.contrast} < baseline ${baseline.contrast}",
            profile.contrast < baseline.contrast
        )
        assertTrue(
            "Expected log highlightCompression ${profile.highlightCompression} close to/above baseline ${baseline.highlightCompression}",
            profile.highlightCompression + 0.01f >= baseline.highlightCompression
        )
        assertTrue(
            "Expected log saturation ${profile.saturation} <= baseline ${baseline.saturation}",
            profile.saturation <= baseline.saturation
        )
        assertTrue(
            "Expected log warmth ${profile.warmth} >= baseline ${baseline.warmth}",
            profile.warmth >= baseline.warmth
        )
        assertTrue("Expected positive logStrength, got ${profile.logStrength}", profile.logStrength > 0f)
        assertTrue(
            "Expected non-negative videoHdrFusionStrength, got ${profile.videoHdrFusionStrength}",
            profile.videoHdrFusionStrength >= 0f
        )
    }

    @Test
    fun nLogCinemaProfile_usesControlledHighDetailCinemaRollOff() {
        val profile = engine.build(
            scene = scene(averageLuma = 0.41f, estimatedLux = 82f, motionScore = 0.02f, edgeDensity = 0.16f, dynamicRangeScore = 0.64f, shadowRatio = 0.18f, highlightRatio = 0.09f),
            recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
            logEnabled = true,
            hdrEnabled = true,
            colorProfile = "N-Log Cinema"
        )

        assertTrue(profile.logStrength >= 0.75f)
        assertTrue(profile.highlightCompression >= 0.26f)
        assertTrue("contrast=${profile.contrast}", profile.contrast <= 0.78f)
        assertTrue("saturation=${profile.saturation}", profile.saturation <= 0.84f)
        assertTrue(
            "Expected cinema-log sharpening in [0.40, 0.56], got ${profile.sharpening}",
            profile.sharpening in 0.40f..0.56f
        )
    }

    @Test
    fun genericLogToggle_onNaturalProfile_usesFlatPreviewTuning() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val profile = engine.build(
                scene = scene(averageLuma = 0.40f, estimatedLux = 80f, motionScore = 0.02f, edgeDensity = 0.18f, dynamicRangeScore = 0.40f, shadowRatio = 0.14f, highlightRatio = 0.05f),
                recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = emptyList()),
                logEnabled = true,
                hdrEnabled = false,
                colorProfile = "Natural"
            )

            assertTrue(profile.logStrength >= 0.76f)
            assertTrue(profile.contrast <= 0.76f)
            assertTrue(profile.saturation <= 0.80f)
            assertTrue(
                "Expected natural-log sharpening in [0.14, 0.22], got ${profile.sharpening}",
                profile.sharpening in 0.14f..0.22f
            )
            assertTrue(profile.logPreviewParity == VideoLogPreviewParityPolicy.resolve("Natural", true))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun cleanDetailedLogVideoKeepsPreviewAndRecordingOnEliteDetailPath() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val detailedScene = scene(
                averageLuma = 0.50f,
                estimatedLux = 165f,
                motionScore = 0.02f,
                edgeDensity = 0.38f,
                dynamicRangeScore = 0.48f,
                shadowRatio = 0.14f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.03f,
                flatRegionStdDev = 1.2f,
                realIsoNoiseFloor = 160f,
                perceptualSharpness = 0.86f
            )
            val detailedRecommendation = recommendation(
                sharpeningBoost = 0.16f,
                hdrBracketEvs = emptyList()
            ).copy(
                spatialDenoiseFloor = 0.28f,
                detailPreservationStrength = 0.96f
            )

            val preview = engine.build(
                scene = detailedScene,
                recommendation = detailedRecommendation,
                logEnabled = true,
                hdrEnabled = false,
                colorProfile = "N-Log Cinema",
                recording = false,
                previewFps = 30f
            )
            val recording = engine.build(
                scene = detailedScene,
                recommendation = detailedRecommendation,
                logEnabled = true,
                hdrEnabled = false,
                colorProfile = "N-Log Cinema",
                recording = true,
                previewFps = 30f
            )

            assertTrue("preview.sharpening=${preview.sharpening}", preview.sharpening >= 0.42f)
            assertTrue("recording.sharpening=${recording.sharpening}", recording.sharpening >= 0.46f)
            assertTrue(
                "preview=${preview.sharpening} recording=${recording.sharpening}",
                kotlin.math.abs(preview.sharpening - recording.sharpening) <= 0.10f
            )
            assertTrue("preview.logStrength=${preview.logStrength}", preview.logStrength >= 0.88f)
            assertTrue("recording.logStrength=${recording.logStrength}", recording.logStrength >= 0.88f)
            assertEquals(0f, preview.videoHdrFusionStrength, 0.0001f)
            assertEquals(0f, recording.videoHdrFusionStrength, 0.0001f)
            assertTrue("preview.contrast=${preview.contrast}", preview.contrast <= 0.78f)
            assertTrue("preview.saturation=${preview.saturation}", preview.saturation <= 0.84f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun neoCinemaProfile_keepsWarmthAndModerateContrast() {
        val profile = engine.build(
            scene = scene(averageLuma = 0.48f, estimatedLux = 120f, motionScore = 0.04f, edgeDensity = 0.15f, dynamicRangeScore = 0.44f, shadowRatio = 0.12f, highlightRatio = 0.05f),
            recommendation = recommendation(sharpeningBoost = 0.07f, hdrBracketEvs = emptyList()),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Neo Cinema"
        )

        assertTrue(profile.warmth > 0f)
        assertTrue(profile.contrast < 0.95f)
        assertTrue(profile.saturation <= 0.92f)
    }

    @Test
    fun simulatedTenBitFallback_addsPreviewDitherForHdrLogProfiles() {
        val scene = scene(averageLuma = 0.43f, estimatedLux = 85f, motionScore = 0.02f, edgeDensity = 0.17f, dynamicRangeScore = 0.68f, shadowRatio = 0.16f, highlightRatio = 0.08f)
        val recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2))
        val profile = engine.build(
            scene = scene,
            recommendation = recommendation,
            logEnabled = true,
            hdrEnabled = true,
            colorProfile = "N-Log Cinema",
            simulatedTenBitFallback = true
        )
        val baseline = engine.build(
            scene = scene,
            recommendation = recommendation,
            logEnabled = true,
            hdrEnabled = true,
            colorProfile = "N-Log Cinema",
            simulatedTenBitFallback = false
        )

        assertTrue(profile.ditherStrength > baseline.ditherStrength)
        assertTrue(profile.logStrength >= baseline.logStrength)
        assertTrue(profile.videoHdrFusionStrength >= baseline.videoHdrFusionStrength)
    }

    @Test
    fun nativeTenBitHardwarePath_disablesPreviewDitherForLogHdrProfiles() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = false
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.43f,
                    estimatedLux = 85f,
                    motionScore = 0.02f,
                    edgeDensity = 0.17f,
                    dynamicRangeScore = 0.68f,
                    shadowRatio = 0.16f,
                    highlightRatio = 0.08f,
                    normalizedNoiseFloor = 0.62f,
                    flatRegionStdDev = 7.2f,
                    realIsoNoiseFloor = 1200f,
                    perceptualSharpness = 0.32f
                ),
                recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "N-Log Cinema",
                tenBitHardwareSupport = NeoTenBitHardwareSupport(hdr10Supported = true)
            )

            assertEquals(0f, profile.ditherStrength, 0.0001f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun dynamicBlueNoiseFallback_scalesWithMeasuredNoiseFloor() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = false
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2))
            val cleanProfile = engine.build(
                scene = scene(
                    averageLuma = 0.43f,
                    estimatedLux = 85f,
                    motionScore = 0.02f,
                    edgeDensity = 0.17f,
                    dynamicRangeScore = 0.68f,
                    shadowRatio = 0.16f,
                    highlightRatio = 0.08f,
                    normalizedNoiseFloor = 0.05f,
                    flatRegionStdDev = 1.8f,
                    realIsoNoiseFloor = 140f,
                    perceptualSharpness = 0.48f
                ),
                recommendation = recommendation,
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "N-Log Cinema"
            )
            val noisyProfile = engine.build(
                scene = scene(
                    averageLuma = 0.43f,
                    estimatedLux = 85f,
                    motionScore = 0.24f,
                    edgeDensity = 0.17f,
                    dynamicRangeScore = 0.68f,
                    shadowRatio = 0.16f,
                    highlightRatio = 0.08f,
                    normalizedNoiseFloor = 0.78f,
                    flatRegionStdDev = 9.4f,
                    realIsoNoiseFloor = 1520f,
                    perceptualSharpness = 0.28f
                ),
                recommendation = recommendation,
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "N-Log Cinema"
            )

            assertTrue(noisyProfile.ditherStrength > cleanProfile.ditherStrength)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun warmCinemaLut_andProHdr_biasProfileTowardFilmicHighlightProtection() {
        val profile = engine.build(
            scene = scene(averageLuma = 0.46f, estimatedLux = 92f, motionScore = 0.02f, edgeDensity = 0.16f, dynamicRangeScore = 0.70f, shadowRatio = 0.18f, highlightRatio = 0.12f),
            recommendation = recommendation(sharpeningBoost = 0.07f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Neo Wide Gamut",
            lutEnabled = true,
            lutProfile = "Warm Cinema",
            hdrMode = "PRO_HDR"
        )

        assertTrue(profile.warmth > 0.03f)
        assertTrue(profile.highlightCompression >= 0.30f)
        assertTrue(profile.contrast < 0.9f)
    }

    @Test
    fun maximumComputationalCinemaPlanAppliesNaturalDenoiseAndSharpeningCaps() {
        val scene = scene(
            averageLuma = 0.36f,
            estimatedLux = 36f,
            motionScore = 0.03f,
            edgeDensity = 0.18f,
            dynamicRangeScore = 0.70f,
            shadowRatio = 0.22f,
            highlightRatio = 0.12f
        )
        val recommendation = recommendation(
            sharpeningBoost = 0.08f,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
        )
        val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                quality = ComputationalCinemaQuality.MAXIMUM,
                zoomRatio = 2.0f,
                recording = true
            ),
            hardware = ComputationalCinemaHardware(
                supportsRaw = true,
                supportsYuvReprocessing = true,
                supportsHdrVideo = true,
                supportsTenBit = true,
                supportsHevcMain10 = true,
                supportsOis = true,
                supportsVideoStabilization = true,
                supportsVulkan = true,
                supportsZeroCopyGpuImport = true,
                supportsAiSuperResolution = true,
                maxEncoderLongEdge = 7680,
                maxSensorLongEdge = 8192
            ),
            runtime = ComputationalCinemaRuntime(
                previewFps = 60f,
                targetFps = 60,
                thermalSeverity = 0,
                droppedFrames = 0,
                encoderPressure = 0.10f,
                estimatedLux = 36f,
                motionScore = 0.03f
            )
        )

        val baseline = engine.build(
            scene = scene,
            recommendation = recommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Natural",
            recording = true
        )
        val planned = engine.build(
            scene = scene,
            recommendation = recommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Natural",
            recording = true,
            computationalCinemaPlan = cinemaPlan
        )

        assertTrue(planned.spatialDenoise >= cinemaPlan.spatialDenoiseStrength)
        assertTrue(planned.chromaDenoise >= cinemaPlan.chromaDenoiseStrength)
        assertTrue(planned.temporalBlend >= cinemaPlan.temporalDenoiseStrength)
        assertTrue(planned.sharpening >= cinemaPlan.sharpeningStrength)
        assertTrue("planned.sharpening=${planned.sharpening}", planned.sharpening <= 0.82f)
        assertTrue(planned.highlightCompression >= baseline.highlightCompression)
        assertTrue(planned.contrast >= baseline.contrast)
    }

    @Test
    fun aiEnhancement_changesPreviewAndCaptureTuningGlobally() {
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val scene = scene(averageLuma = 0.33f, estimatedLux = 48f, motionScore = 0.03f, edgeDensity = 0.14f, dynamicRangeScore = 0.42f, shadowRatio = 0.20f, highlightRatio = 0.06f)
            val recommendation = recommendation(sharpeningBoost = 0.06f, hdrBracketEvs = listOf(-2, 0, 2))

            val baseline = engine.build(
                scene = scene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Natural",
                aiEnhancementEnabled = false
            )
            val enhanced = engine.build(
                scene = scene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Natural",
                aiEnhancementEnabled = true
            )

            assertTrue(enhanced.spatialDenoise >= baseline.spatialDenoise)
            assertTrue(enhanced.highlightCompression >= baseline.highlightCompression)
            assertTrue(enhanced.warmth != baseline.warmth || enhanced.saturation != baseline.saturation)
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictHighEnd_keepsAiEnhancementPathActiveEvenWhenToggleIsOff() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val scene = scene(averageLuma = 0.51f, estimatedLux = 190f, motionScore = 0.03f, edgeDensity = 0.10f, dynamicRangeScore = 0.40f, shadowRatio = 0.14f, highlightRatio = 0.06f)
            val recommendation = recommendation(sharpeningBoost = 0.06f, hdrBracketEvs = emptyList())
            val forced = engine.build(
                scene = scene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                aiEnhancementEnabled = false
            )
            val explicit = engine.build(
                scene = scene,
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                aiEnhancementEnabled = true
            )

            assertTrue(kotlin.math.abs(forced.temporalBlend - explicit.temporalBlend) < 0.0001f)
            assertTrue(kotlin.math.abs(forced.spatialDenoise - explicit.spatialDenoise) < 0.0001f)
            assertTrue(kotlin.math.abs(forced.chromaDenoise - explicit.chromaDenoise) < 0.0001f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictNoisyHighEndRecording_usesMaximumResolutionNoiseFloors() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            // A static, measured-noisy scene can use the high denoise target without
            // smearing motion or flattening a clean textured recording.
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.34f,
                    estimatedLux = 8f,
                    motionScore = 0.0f,
                    edgeDensity = 0.20f,
                    dynamicRangeScore = 0.62f,
                    shadowRatio = 0.20f,
                    highlightRatio = 0.08f,
                    normalizedNoiseFloor = 0.48f,
                    flatRegionStdDev = 8.0f,
                    realIsoNoiseFloor = 2_400f
                ),
                recommendation = recommendation(sharpeningBoost = 0.06f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)).copy(
                    spatialDenoiseFloor = 0.78f,
                    detailPreservationStrength = 0.98f
                ),
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "N-Log Cinema",
                recording = true,
                previewFps = 30f
            )

            assertTrue(profile.temporalBlend >= 0.70f)
            assertTrue(profile.spatialDenoise >= 0.78f)
            assertTrue(profile.chromaDenoise >= 0.99f)
            assertTrue(profile.sharpening >= 0.10f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    /**
     * Regression: strict-high-end recording previously forced
     * temporalBlend ≥ 0.70 unconditionally, which produced visible ghosting
     * trails on moving subjects (recursive blend preserves prior-frame energy
     * proportional to blend × inter-frame displacement). The fix gates the
     * floor by static-ness so high-motion recordings degrade smoothly.
     */
    @Test
    fun strictHighEndRecording_motionGatesTemporalBlendToPreventGhosting() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val highMotionRecording = engine.build(
                scene = scene(
                    averageLuma = 0.34f, estimatedLux = 42f,
                    motionScore = 0.55f,    // sustained motion
                    edgeDensity = 0.20f, dynamicRangeScore = 0.62f,
                    shadowRatio = 0.20f, highlightRatio = 0.08f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.06f,
                    hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
                ).copy(spatialDenoiseFloor = 0.78f, detailPreservationStrength = 0.98f),
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "N-Log Cinema",
                recording = true,
                previewFps = 30f
            )
            val staticRecording = engine.build(
                scene = scene(
                    averageLuma = 0.34f, estimatedLux = 42f,
                    motionScore = 0.0f,     // fully static
                    edgeDensity = 0.20f, dynamicRangeScore = 0.62f,
                    shadowRatio = 0.20f, highlightRatio = 0.08f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.06f,
                    hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
                ).copy(spatialDenoiseFloor = 0.78f, detailPreservationStrength = 0.98f),
                logEnabled = true,
                hdrEnabled = true,
                colorProfile = "N-Log Cinema",
                recording = true,
                previewFps = 30f
            )

            // High motion must NOT be pushed up to the static-only ceiling — that
            // would re-introduce the ghosting bug. The static recording should
            // legitimately reach a higher temporalBlend than the moving one.
            assertTrue(
                "Expected high-motion temporalBlend ${highMotionRecording.temporalBlend} < static ${staticRecording.temporalBlend}",
                highMotionRecording.temporalBlend < staticRecording.temporalBlend
            )
            assertTrue(
                "Expected high-motion temporalBlend ${highMotionRecording.temporalBlend} below ghosting threshold 0.55",
                highMotionRecording.temporalBlend < 0.55f
            )
            // Strict policy still demands a chroma-clean recording floor.
            assertTrue(highMotionRecording.chromaDenoise >= 0.99f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun videoHdrMode_strengthensFusionForHdrRecordingPath() {
        val profile = engine.build(
            scene = scene(averageLuma = 0.38f, estimatedLux = 60f, motionScore = 0.04f, edgeDensity = 0.19f, dynamicRangeScore = 0.61f, shadowRatio = 0.19f, highlightRatio = 0.09f),
            recommendation = recommendation(sharpeningBoost = 0.05f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HDR10"
        )

        assertTrue(profile.videoHdrFusionStrength > 0.35f)
        assertTrue(profile.highlightCompression > 0.25f)
    }

    @Test
    fun hdrVideoProfile_suppressesVisibleRgbDitherAndRaisesChromaDenoise() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = false
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.43f,
                    estimatedLux = 85f,
                    motionScore = 0.02f,
                    edgeDensity = 0.11f,
                    dynamicRangeScore = 0.72f,
                    shadowRatio = 0.18f,
                    highlightRatio = 0.10f,
                    normalizedNoiseFloor = 0.58f,
                    flatRegionStdDev = 8.6f,
                    realIsoNoiseFloor = 1350f,
                    perceptualSharpness = 0.30f
                ),
                recommendation = recommendation(sharpeningBoost = 0.06f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HLG",
                simulatedTenBitFallback = true
            )

            assertEquals(0f, profile.ditherStrength, 0.0001f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.60f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.40f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun extremeHighIsoScene_prioritizesRgbChromaNoiseSuppression() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.18f,
                    estimatedLux = 2f,
                    motionScore = 0.04f,
                    edgeDensity = 0.05f,
                    dynamicRangeScore = 0.46f,
                    shadowRatio = 0.40f,
                    highlightRatio = 0.03f,
                    normalizedNoiseFloor = 0.96f,
                    flatRegionStdDev = 18.0f,
                    realIsoNoiseFloor = 80_000f,
                    perceptualSharpness = 0.16f
                ),
                recommendation = recommendation(sharpeningBoost = 0.10f, hdrBracketEvs = emptyList()).copy(
                    shadowDenoiseStrength = 0.96f,
                    spatialDenoiseFloor = 0.88f,
                    detailPreservationStrength = 0.56f
                ),
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural"
            )

            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.92f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.74f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening <= 0.36f)
            assertTrue("saturation=${profile.saturation}", profile.saturation <= 1.02f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun highIsoHdrPreviewSuppressesRgbSpeckleWithoutDither() {
        val profile = engine.build(
            scene = scene(
                averageLuma = 0.24f,
                estimatedLux = 6f,
                motionScore = 0.03f,
                edgeDensity = 0.07f,
                dynamicRangeScore = 0.74f,
                shadowRatio = 0.34f,
                highlightRatio = 0.12f,
                normalizedNoiseFloor = 0.92f,
                flatRegionStdDev = 14.5f,
                realIsoNoiseFloor = 64_000f,
                perceptualSharpness = 0.20f
            ),
            recommendation = recommendation(sharpeningBoost = 0.09f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)).copy(
                shadowDenoiseStrength = 0.94f,
                spatialDenoiseFloor = 0.82f,
                detailPreservationStrength = 0.64f
            ),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HLG",
            simulatedTenBitFallback = true
        )

        assertEquals(0f, profile.ditherStrength, 0.0001f)
        assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.92f)
        assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.70f)
        assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.88f)
    }

    @Test
    fun recordingPreviewSuppressesSyntheticRgbDitherAcrossCinemaModes() {
        val profile = engine.build(
            scene = scene(
                averageLuma = 0.22f,
                estimatedLux = 5f,
                motionScore = 0.05f,
                edgeDensity = 0.08f,
                dynamicRangeScore = 0.70f,
                shadowRatio = 0.36f,
                highlightRatio = 0.10f,
                normalizedNoiseFloor = 0.90f,
                flatRegionStdDev = 13.5f,
                realIsoNoiseFloor = 48_000f,
                perceptualSharpness = 0.19f
            ),
            recommendation = recommendation(sharpeningBoost = 0.08f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)).copy(
                shadowDenoiseStrength = 0.88f,
                spatialDenoiseFloor = 0.80f,
                detailPreservationStrength = 0.62f
            ),
            logEnabled = true,
            hdrEnabled = true,
            colorProfile = "N-Log Cinema",
            simulatedTenBitFallback = true,
            recording = true,
            previewFps = 30f
        )

        assertEquals(0f, profile.ditherStrength, 0.0001f)
        assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.92f)
        assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.70f)
        assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.88f)
    }

    @Test
    fun lowLightRecordingUsesZeroNoiseFloorsAcrossSdrHdrAndLogModes() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val noisyLowLight = scene(
                averageLuma = 0.16f,
                estimatedLux = 3f,
                motionScore = 0.04f,
                edgeDensity = 0.07f,
                dynamicRangeScore = 0.70f,
                shadowRatio = 0.42f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.94f,
                flatRegionStdDev = 15.0f,
                realIsoNoiseFloor = 64_000f,
                perceptualSharpness = 0.18f
            )
            val denoiseRecommendation = recommendation(
                sharpeningBoost = 0.08f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.94f,
                spatialDenoiseFloor = 0.64f,
                detailPreservationStrength = 0.62f
            )
            val profiles = listOf(
                engine.build(
                    scene = noisyLowLight,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "Natural",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = noisyLowLight,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = true,
                    colorProfile = "Rec.2020",
                    hdrMode = "HLG",
                    simulatedTenBitFallback = true,
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = noisyLowLight,
                    recommendation = denoiseRecommendation,
                    logEnabled = true,
                    hdrEnabled = false,
                    colorProfile = "N-Log Cinema",
                    recording = true,
                    previewFps = 30f
                )
            )

            profiles.forEach { profile ->
                assertEquals(0f, profile.ditherStrength, 0.0001f)
                assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.86f)
                assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.99f)
                assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.88f)
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun recordingUsesZeroGrainFloorsForModerateRgbNoiseAcrossAllVideoLooks() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val moderateRgbGrain = scene(
                averageLuma = 0.32f,
                estimatedLux = 55f,
                motionScore = 0.06f,
                edgeDensity = 0.11f,
                dynamicRangeScore = 0.58f,
                shadowRatio = 0.26f,
                highlightRatio = 0.07f,
                normalizedNoiseFloor = 0.46f,
                flatRegionStdDev = 9.5f,
                realIsoNoiseFloor = 1_400f,
                perceptualSharpness = 0.24f
            )
            val denoiseRecommendation = recommendation(
                sharpeningBoost = 0.07f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.72f,
                spatialDenoiseFloor = 0.60f,
                detailPreservationStrength = 0.72f
            )
            val profiles = listOf(
                engine.build(
                    scene = moderateRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "Natural",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = moderateRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = true,
                    colorProfile = "Rec.2020",
                    hdrMode = "HDR10",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = moderateRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = true,
                    hdrEnabled = false,
                    colorProfile = "N-Log Shadow",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = moderateRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "RAW",
                    recording = true,
                    previewFps = 30f
                )
            )

            profiles.forEach { profile ->
                assertEquals(0f, profile.ditherStrength, 0.0001f)
                assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.92f)
                assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.991f)
                assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.88f)
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictRecordingNoiseOnlyPassPushesRgbGrainNearCeilingAcrossAllLooks() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val movingRgbGrain = scene(
                averageLuma = 0.30f,
                estimatedLux = 42f,
                motionScore = 0.08f,
                edgeDensity = 0.12f,
                dynamicRangeScore = 0.60f,
                shadowRatio = 0.28f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.52f,
                flatRegionStdDev = 10.8f,
                realIsoNoiseFloor = 1_850f,
                perceptualSharpness = 0.30f
            )
            val denoiseRecommendation = recommendation(
                sharpeningBoost = 0.07f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.76f,
                spatialDenoiseFloor = 0.60f,
                detailPreservationStrength = 0.78f
            )
            val profiles = listOf(
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "Natural",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = true,
                    colorProfile = "Rec.2020",
                    hdrMode = "HLG",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = true,
                    hdrEnabled = false,
                    colorProfile = "N-Log Cinema",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "RAW",
                    recording = true,
                    previewFps = 30f
                )
            )

            profiles.forEach { profile ->
                assertEquals(0f, profile.ditherStrength, 0.0001f)
                assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.965f)
                assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.997f)
                assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.88f)
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun recordingPipelineAppliesHighEndNoiseFloorToMovingRgbGrainAcrossLooks() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val movingRgbGrain = scene(
                averageLuma = 0.28f,
                estimatedLux = 34f,
                motionScore = 0.34f,
                edgeDensity = 0.14f,
                dynamicRangeScore = 0.64f,
                shadowRatio = 0.30f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.56f,
                flatRegionStdDev = 11.2f,
                realIsoNoiseFloor = 2_200f,
                perceptualSharpness = 0.34f
            )
            val denoiseRecommendation = recommendation(
                sharpeningBoost = 0.07f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.78f,
                spatialDenoiseFloor = 0.64f,
                detailPreservationStrength = 0.78f
            )
            val profiles = listOf(
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "Natural",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = false,
                    hdrEnabled = true,
                    colorProfile = "Rec.2020",
                    hdrMode = "HLG",
                    recording = true,
                    previewFps = 30f
                ),
                engine.build(
                    scene = movingRgbGrain,
                    recommendation = denoiseRecommendation,
                    logEnabled = true,
                    hdrEnabled = false,
                    colorProfile = "N-Log Cinema",
                    recording = true,
                    previewFps = 30f
                )
            )

            profiles.forEach { profile ->
                assertEquals(0f, profile.ditherStrength, 0.0001f)
                assertTrue("temporalBlend=${profile.temporalBlend}", profile.temporalBlend <= 0.68f)
                assertTrue("temporalBlend=${profile.temporalBlend}", profile.temporalBlend < ImaxStrictProfile.TNR_STRICT_CEILING)
                assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.985f)
                assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.999f)
                assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.88f)
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun computationalCinemaPlanCannotClampStrictZeroNoiseRecordingFloors() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val noisyLowLight = scene(
                averageLuma = 0.16f,
                estimatedLux = 3f,
                motionScore = 0.04f,
                edgeDensity = 0.07f,
                dynamicRangeScore = 0.70f,
                shadowRatio = 0.42f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.94f,
                flatRegionStdDev = 15.0f,
                realIsoNoiseFloor = 64_000f,
                perceptualSharpness = 0.18f
            )
            val denoiseRecommendation = recommendation(
                sharpeningBoost = 0.08f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.94f,
                spatialDenoiseFloor = 0.64f,
                detailPreservationStrength = 0.62f
            )
            val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
                request = ComputationalCinemaRequest(
                    mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                    quality = ComputationalCinemaQuality.MAXIMUM,
                    recording = true
                ),
                hardware = ComputationalCinemaHardware(
                    supportsRaw = true,
                    supportsYuvReprocessing = true,
                    supportsHdrVideo = true,
                    supportsTenBit = true,
                    supportsHevcMain10 = true,
                    supportsOis = true,
                    supportsVideoStabilization = true,
                    supportsVulkan = true,
                    supportsZeroCopyGpuImport = true,
                    supportsAiSuperResolution = true,
                    maxEncoderLongEdge = 7680,
                    maxSensorLongEdge = 8192
                ),
                runtime = ComputationalCinemaRuntime(
                    previewFps = 30f,
                    targetFps = 30,
                    thermalSeverity = 0,
                    droppedFrames = 0,
                    encoderPressure = 0.10f,
                    estimatedLux = 3f,
                    motionScore = 0.04f
                )
            )

            val profile = engine.build(
                scene = noisyLowLight,
                recommendation = denoiseRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HLG",
                recording = true,
                previewFps = 30f,
                computationalCinemaPlan = cinemaPlan
            )

            assertEquals(0f, profile.ditherStrength, 0.0001f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.985f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.999f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun computationalCinemaPlanCannotRaiseMovingRecordingTemporalBlendPastGhostingGuard() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val movingNoisyScene = scene(
                averageLuma = 0.18f,
                estimatedLux = 4f,
                motionScore = 0.62f,
                edgeDensity = 0.09f,
                dynamicRangeScore = 0.70f,
                shadowRatio = 0.40f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.92f,
                flatRegionStdDev = 14.0f,
                realIsoNoiseFloor = 56_000f,
                perceptualSharpness = 0.18f
            )
            val denoiseRecommendation = recommendation(
                sharpeningBoost = 0.08f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.92f,
                spatialDenoiseFloor = 0.64f,
                detailPreservationStrength = 0.62f
            )
            val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
                request = ComputationalCinemaRequest(
                    mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                    quality = ComputationalCinemaQuality.MAXIMUM,
                    recording = true
                ),
                hardware = ComputationalCinemaHardware(
                    supportsRaw = true,
                    supportsYuvReprocessing = true,
                    supportsHdrVideo = true,
                    supportsTenBit = true,
                    supportsHevcMain10 = true,
                    supportsOis = true,
                    supportsVideoStabilization = true,
                    supportsVulkan = true,
                    supportsZeroCopyGpuImport = true,
                    supportsAiSuperResolution = true,
                    maxEncoderLongEdge = 7680,
                    maxSensorLongEdge = 8192
                ),
                runtime = ComputationalCinemaRuntime(
                    previewFps = 30f,
                    targetFps = 30,
                    thermalSeverity = 0,
                    droppedFrames = 0,
                    encoderPressure = 0.10f,
                    estimatedLux = 4f,
                    motionScore = 0.62f
                )
            )

            val profile = engine.build(
                scene = movingNoisyScene,
                recommendation = denoiseRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HLG",
                recording = true,
                previewFps = 30f,
                computationalCinemaPlan = cinemaPlan
            )

            assertTrue("temporalBlend=${profile.temporalBlend}", profile.temporalBlend < 0.55f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.985f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.999f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun cleanDetailRecordingUsesControlledCinemaSharpeningAcrossVideoLooks() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val cleanDetailScene = scene(
                averageLuma = 0.42f,
                estimatedLux = 72f,
                motionScore = 0.03f,
                edgeDensity = 0.18f,
                dynamicRangeScore = 0.56f,
                shadowRatio = 0.16f,
                highlightRatio = 0.05f,
                normalizedNoiseFloor = 0.08f,
                flatRegionStdDev = 1.8f,
                realIsoNoiseFloor = 240f,
                perceptualSharpness = 0.52f
            )
            val detailRecommendation = recommendation(
                sharpeningBoost = 0.04f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.26f,
                spatialDenoiseFloor = 0.22f,
                detailPreservationStrength = 0.58f
            )

            val normal = engine.build(
                scene = cleanDetailScene,
                recommendation = detailRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            )
            val hdr = engine.build(
                scene = cleanDetailScene,
                recommendation = detailRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HDR10",
                recording = true,
                previewFps = 30f
            )
            val log = engine.build(
                scene = cleanDetailScene,
                recommendation = detailRecommendation,
                logEnabled = true,
                hdrEnabled = false,
                colorProfile = "N-Log Cinema",
                recording = true,
                previewFps = 30f
            )
            val raw = engine.build(
                scene = cleanDetailScene,
                recommendation = detailRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "RAW",
                recording = true,
                previewFps = 30f
            )

            assertTrue("normal.sharpening=${normal.sharpening}", normal.sharpening in 0.40f..0.88f)
            assertTrue("hdr.sharpening=${hdr.sharpening}", hdr.sharpening in 0.38f..0.82f)
            assertTrue("log.sharpening=${log.sharpening}", log.sharpening in 0.32f..0.56f)
            assertTrue("raw.sharpening=${raw.sharpening}", raw.sharpening in 0.34f..0.82f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun cleanFineTextureRecordingKeepsNeoStyleDetailWithoutPushingFlatScenes() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val recommendation = recommendation(
                sharpeningBoost = 0.03f,
                hdrBracketEvs = emptyList()
            ).copy(
                shadowDenoiseStrength = 0.22f,
                spatialDenoiseFloor = 0.20f,
                detailPreservationStrength = 0.72f
            )
            val fineTexture = engine.build(
                scene = scene(
                    averageLuma = 0.46f,
                    estimatedLux = 96f,
                    motionScore = 0.02f,
                    edgeDensity = 0.13f,
                    dynamicRangeScore = 0.46f,
                    shadowRatio = 0.12f,
                    highlightRatio = 0.04f,
                    normalizedNoiseFloor = 0.05f,
                    flatRegionStdDev = 1.3f,
                    realIsoNoiseFloor = 180f,
                    perceptualSharpness = 0.68f
                ),
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 60f
            )
            val flatClean = engine.build(
                scene = scene(
                    averageLuma = 0.46f,
                    estimatedLux = 96f,
                    motionScore = 0.02f,
                    edgeDensity = 0.03f,
                    dynamicRangeScore = 0.46f,
                    shadowRatio = 0.12f,
                    highlightRatio = 0.04f,
                    normalizedNoiseFloor = 0.05f,
                    flatRegionStdDev = 1.3f,
                    realIsoNoiseFloor = 180f,
                    perceptualSharpness = 0.12f
                ),
                recommendation = recommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 60f
            )

            assertTrue("fineTexture.sharpening=${fineTexture.sharpening}", fineTexture.sharpening >= 0.48f)
            assertTrue("flatClean.sharpening=${flatClean.sharpening}", flatClean.sharpening >= 0.48f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictHighEndRecordingAppliesAggressiveQualityFloorsAcrossAllModes() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val scene = scene(
                averageLuma = 0.38f,
                estimatedLux = 38f,
                motionScore = 0.04f,
                edgeDensity = 0.24f,
                dynamicRangeScore = 0.64f,
                shadowRatio = 0.24f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.24f,
                flatRegionStdDev = 5.2f,
                realIsoNoiseFloor = 1_250f,
                perceptualSharpness = 0.58f
            )
            val recommendation = recommendation(
                sharpeningBoost = 0.08f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.72f,
                spatialDenoiseFloor = 0.54f,
                detailPreservationStrength = 0.78f
            )

            val normal = engine.build(scene, recommendation, false, false, "Natural", recording = true, previewFps = 30f)
            val ndr = engine.build(scene, recommendation, false, true, "BT.2100", hdrMode = "NDR10+", recording = true, previewFps = 30f)
            val nivukx = engine.build(scene, recommendation, false, true, "Neo Wide Gamut", hdrMode = "NIVUKX Vision", recording = true, previewFps = 30f)
            val log = engine.build(scene, recommendation, true, false, "N-Log Cinema", recording = true, previewFps = 30f)
            val raw = engine.build(scene, recommendation, false, false, "RAW", recording = true, previewFps = 30f)

            listOf(
                "normal" to normal,
                "ndr" to ndr,
                "nivukx" to nivukx,
                "log" to log,
                "raw" to raw
            ).forEach { (name, profile) ->
                assertTrue("$name spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.88f)
                assertTrue("$name chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.992f)
                assertTrue("$name sharpening=${profile.sharpening}", profile.sharpening >= 0.50f)
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictHighEndRecordingKeepsDetailRecoveryAggressiveUnderRgbNoiseAcrossAllModes() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val rgbNoiseScene = scene(
                averageLuma = 0.32f,
                estimatedLux = 34f,
                motionScore = 0.10f,
                edgeDensity = 0.16f,
                dynamicRangeScore = 0.64f,
                shadowRatio = 0.30f,
                highlightRatio = 0.08f,
                normalizedNoiseFloor = 0.56f,
                flatRegionStdDev = 11.2f,
                realIsoNoiseFloor = 2_200f,
                perceptualSharpness = 0.34f
            )
            val recommendation = recommendation(
                sharpeningBoost = 0.08f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.78f,
                spatialDenoiseFloor = 0.64f,
                detailPreservationStrength = 0.78f
            )

            val normal = engine.build(rgbNoiseScene, recommendation, false, false, "Natural", recording = true, previewFps = 30f)
            val ndr = engine.build(rgbNoiseScene, recommendation, false, true, "BT.2100", hdrMode = "NDR10+", recording = true, previewFps = 30f)
            val nivukx = engine.build(rgbNoiseScene, recommendation, false, true, "Neo Wide Gamut", hdrMode = "NIVUKX Vision", recording = true, previewFps = 30f)
            val log = engine.build(rgbNoiseScene, recommendation, true, false, "N-Log Cinema", recording = true, previewFps = 30f)
            val raw = engine.build(rgbNoiseScene, recommendation, false, false, "RAW", recording = true, previewFps = 30f)

            listOf(
                "normal" to normal,
                "ndr" to ndr,
                "nivukx" to nivukx,
                "raw" to raw
            ).forEach { (name, profile) ->
                assertTrue("$name spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.94f)
                assertTrue("$name chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.996f)
                assertTrue("$name sharpening=${profile.sharpening}", profile.sharpening >= 0.64f)
            }
            assertTrue("log spatialDenoise=${log.spatialDenoise}", log.spatialDenoise >= 0.94f)
            assertTrue("log chromaDenoise=${log.chromaDenoise}", log.chromaDenoise >= 0.996f)
            assertTrue("log sharpening=${log.sharpening}", log.sharpening >= 0.56f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun neoCinemaProfilesDoNotClampStrictHighEndDetailToSoftPreviewValues() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.44f,
                    estimatedLux = 64f,
                    motionScore = 0.03f,
                    edgeDensity = 0.22f,
                    dynamicRangeScore = 0.68f,
                    shadowRatio = 0.18f,
                    highlightRatio = 0.08f,
                    normalizedNoiseFloor = 0.18f,
                    flatRegionStdDev = 4.0f,
                    realIsoNoiseFloor = 900f,
                    perceptualSharpness = 0.60f
                ),
                recommendation = recommendation(0.07f, listOf(-2, -1, 0, 1, 2)).copy(
                    shadowDenoiseStrength = 0.68f,
                    spatialDenoiseFloor = 0.50f,
                    detailPreservationStrength = 0.82f
                ),
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Neo Wide Gamut",
                hdrMode = "NIVUKX Vision",
                recording = true,
                previewFps = 30f
            )

            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.88f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.992f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening >= 0.56f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun defaultRecordingProfileUsesRecordingNoiseFloorsBeforeAnalysisArrives() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val profile = engine.build(
                scene = null,
                recommendation = null,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            )

            assertEquals(0f, profile.ditherStrength, 0.0001f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.94f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.996f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun lowLightHighMotionRecordingKeepsTemporalBlendBelowGhostingThreshold() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.18f,
                    estimatedLux = 4f,
                    motionScore = 0.76f,
                    edgeDensity = 0.09f,
                    dynamicRangeScore = 0.62f,
                    shadowRatio = 0.38f,
                    highlightRatio = 0.05f,
                    normalizedNoiseFloor = 0.91f,
                    flatRegionStdDev = 13.0f,
                    realIsoNoiseFloor = 48_000f,
                    perceptualSharpness = 0.19f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.07f,
                    hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
                ).copy(
                    shadowDenoiseStrength = 0.90f,
                    spatialDenoiseFloor = 0.62f,
                    detailPreservationStrength = 0.60f
                ),
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HDR10",
                recording = true,
                previewFps = 30f
            )

            assertTrue("temporalBlend=${profile.temporalBlend}", profile.temporalBlend < 0.55f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.82f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.98f)
            assertEquals(0f, profile.ditherStrength, 0.0001f)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun hlgHdrMode_activatesWideGamutFusionForNeoWideGamutProfiles() {
        val profile = engine.build(
            scene = scene(averageLuma = 0.42f, estimatedLux = 78f, motionScore = 0.03f, edgeDensity = 0.18f, dynamicRangeScore = 0.66f, shadowRatio = 0.20f, highlightRatio = 0.11f),
            recommendation = recommendation(sharpeningBoost = 0.06f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
            logEnabled = true,
            hdrEnabled = true,
            colorProfile = "Neo Wide Gamut",
            hdrMode = "HLG"
        )

        assertTrue(profile.videoHdrFusionStrength > 0.30f)
        assertTrue(profile.highlightCompression >= 0.27f)
    }

    @Test
    fun recommendationStrengths_driveHigherEndRenderTuning() {
        val scene = scene(
            averageLuma = 0.37f,
            estimatedLux = 62f,
            motionScore = 0.02f,
            edgeDensity = 0.20f,
            dynamicRangeScore = 0.74f,
            shadowRatio = 0.22f,
            highlightRatio = 0.13f
        )
        val baseRecommendation = recommendation(
            sharpeningBoost = 0.07f,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
        )
        val mild = engine.build(
            scene = scene,
            recommendation = baseRecommendation.copy(
                localToneMapStrength = 0.22f,
                shadowDenoiseStrength = 0.24f,
                detailPreservationStrength = 0.62f,
                skyProtectionStrength = 0.30f,
                hdrTargetFrameCount = 3
            ),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Natural"
        )
        val aggressive = engine.build(
            scene = scene,
            recommendation = baseRecommendation.copy(
                localToneMapStrength = 0.90f,
                shadowDenoiseStrength = 0.88f,
                detailPreservationStrength = 0.92f,
                skyProtectionStrength = 0.94f,
                hdrTargetFrameCount = 9,
                wbLockSuggested = true
            ),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Natural"
        )

        assertTrue(aggressive.highlightCompression > mild.highlightCompression)
        assertTrue(aggressive.spatialDenoise >= mild.spatialDenoise)
        assertTrue(aggressive.shadowBoost >= mild.shadowBoost)
    }

    @Test
    fun naturalAndRec2020Profiles_keepColorDensityAndNoiseSuppressionStable() {
        val scene = scene(
            averageLuma = 0.46f,
            estimatedLux = 118f,
            motionScore = 0.03f,
            edgeDensity = 0.17f,
            dynamicRangeScore = 0.68f,
            shadowRatio = 0.16f,
            highlightRatio = 0.09f
        )
        val recommendation = recommendation(
            sharpeningBoost = 0.07f,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
        ).copy(
            localToneMapStrength = 0.48f,
            shadowDenoiseStrength = 0.42f,
            detailPreservationStrength = 0.70f,
            skyProtectionStrength = 0.46f
        )

        val natural = engine.build(
            scene = scene,
            recommendation = recommendation,
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Natural"
        )
        val rec2020Hdr = engine.build(
            scene = scene,
            recommendation = recommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HLG"
        )

        assertTrue("natural.spatialDenoise=${natural.spatialDenoise}", natural.spatialDenoise >= 0.20f)
        assertTrue("natural.chromaDenoise=${natural.chromaDenoise}", natural.chromaDenoise >= 0.30f)
        assertTrue("natural.contrast=${natural.contrast}", natural.contrast >= 0.88f)
        assertTrue("natural.saturation=${natural.saturation}", natural.saturation >= 0.92f)
        assertTrue("rec2020Hdr.chromaDenoise=${rec2020Hdr.chromaDenoise}", rec2020Hdr.chromaDenoise >= 0.30f)
        assertTrue("rec2020Hdr.contrast=${rec2020Hdr.contrast}", rec2020Hdr.contrast >= 0.78f)
        assertTrue("rec2020Hdr.saturation=${rec2020Hdr.saturation}", rec2020Hdr.saturation >= 0.88f)
    }

    @Test
    fun hdrVideoProfile_usesNeutralBt2100TuningInsteadOfPunchyVividBoost() {
        val profile = engine.build(
            scene = scene(
                averageLuma = 0.44f,
                estimatedLux = 96f,
                motionScore = 0.03f,
                edgeDensity = 0.16f,
                dynamicRangeScore = 0.72f,
                shadowRatio = 0.18f,
                highlightRatio = 0.12f
            ),
            recommendation = recommendation(sharpeningBoost = 0.06f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)).copy(
                localToneMapStrength = 0.42f,
                shadowDenoiseStrength = 0.36f,
                detailPreservationStrength = 0.64f,
                skyProtectionStrength = 0.42f
            ),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HDR10"
        )

        assertTrue("contrast=${profile.contrast}", profile.contrast <= 1.02f)
        assertTrue("saturation=${profile.saturation}", profile.saturation <= 1.04f)
        assertTrue("shadowBoost=${profile.shadowBoost}", profile.shadowBoost <= 0.42f)
        assertTrue("highlightCompression=${profile.highlightCompression}", profile.highlightCompression >= 0.22f)
        assertTrue("videoHdrFusionStrength=${profile.videoHdrFusionStrength}", profile.videoHdrFusionStrength > 0.30f)
    }

    @Test
    fun hdrVideoVersions_applyVersionSpecificUltraHdrTuning() {
        val baseScene = scene(
            averageLuma = 0.40f,
            estimatedLux = 88f,
            motionScore = 0.04f,
            edgeDensity = 0.16f,
            dynamicRangeScore = 0.82f,
            shadowRatio = 0.20f,
            highlightRatio = 0.16f
        )
        val baseRecommendation = recommendation(
            sharpeningBoost = 0.06f,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
        ).copy(
            localToneMapStrength = 0.52f,
            shadowDenoiseStrength = 0.42f,
            detailPreservationStrength = 0.68f,
            skyProtectionStrength = 0.56f
        )

        val hlg = engine.build(
            scene = baseScene,
            recommendation = baseRecommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HLG"
        )
        val hdr10 = engine.build(
            scene = baseScene,
            recommendation = baseRecommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HDR10"
        )
        val hdr10Plus = engine.build(
            scene = baseScene,
            recommendation = baseRecommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "HDR10+"
        )
        val nivukx = engine.build(
            scene = baseScene,
            recommendation = baseRecommendation,
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "NIVUKXVISION"
        )

        assertTrue(hlg.videoHdrFusionStrength <= hdr10.videoHdrFusionStrength)
        assertTrue(hdr10.videoHdrFusionStrength <= hdr10Plus.videoHdrFusionStrength)
        assertTrue(hdr10Plus.videoHdrFusionStrength <= nivukx.videoHdrFusionStrength)
        assertTrue(hlg.highlightCompression <= hdr10.highlightCompression)
        assertTrue(hdr10.highlightCompression <= hdr10Plus.highlightCompression)
        assertTrue(hdr10Plus.highlightCompression <= nivukx.highlightCompression)
        listOf(hlg, hdr10, hdr10Plus, nivukx).forEach { profile ->
            assertTrue("contrast=${profile.contrast}", profile.contrast <= 1.04f)
            assertTrue("saturation=${profile.saturation}", profile.saturation <= 1.04f)
        }
    }

    @Test
    fun ndrAndNivukxModesAreRecognizedAsHdrEvenWhenHdrToggleStateLags() {
        val baseScene = scene(
            averageLuma = 0.40f,
            estimatedLux = 88f,
            motionScore = 0.04f,
            edgeDensity = 0.16f,
            dynamicRangeScore = 0.82f,
            shadowRatio = 0.20f,
            highlightRatio = 0.16f
        )
        val baseRecommendation = recommendation(
            sharpeningBoost = 0.06f,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
        )

        val ndr10Plus = engine.build(
            scene = baseScene,
            recommendation = baseRecommendation,
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Rec.2020",
            hdrMode = "NDR10+"
        )
        val nivukx = engine.build(
            scene = baseScene,
            recommendation = baseRecommendation,
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Rec.2020",
            hdrMode = "NIVUKX Vision"
        )

        assertTrue("ndr fusion=${ndr10Plus.videoHdrFusionStrength}", ndr10Plus.videoHdrFusionStrength >= 0.62f)
        assertTrue("nivukx fusion=${nivukx.videoHdrFusionStrength}", nivukx.videoHdrFusionStrength >= 0.68f)
        assertTrue("ndr saturation=${ndr10Plus.saturation}", ndr10Plus.saturation <= 0.94f)
        assertTrue("nivukx saturation=${nivukx.saturation}", nivukx.saturation <= 0.93f)
    }

    @Test
    fun profileCarriesNonCertifiedAcesCgColorManagementSignature() {
        val profile = engine.build(
            scene = scene(
                averageLuma = 0.44f,
                estimatedLux = 92f,
                motionScore = 0.02f,
                edgeDensity = 0.18f,
                dynamicRangeScore = 0.78f,
                shadowRatio = 0.18f,
                highlightRatio = 0.12f
            ),
            recommendation = recommendation(sharpeningBoost = 0.07f, hdrBracketEvs = listOf(-2, -1, 0, 1, 2)),
            logEnabled = false,
            hdrEnabled = true,
            colorProfile = "Rec.2020",
            hdrMode = "NIVUKX Vision",
            recording = true,
            previewFps = 30f
        )

        assertTrue(profile.acesColorSignature.contains("NON_CERTIFIED_INTERNAL_ACESCG"))
        assertTrue(profile.acesColorSignature.contains("input=REC2020"))
        assertTrue(profile.acesColorSignature.contains("output=REC2020_PQ"))
        listOf("Dolby", "HDR10+ certified", "IMAX", "ARRI", "Sony", "RED").forEach { forbidden ->
            assertFalse("Forbidden certified/vendor label leaked: $forbidden", profile.acesColorSignature.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun selectedNaturalDetailProfileKeepsBaselineSharpeningWithoutAdditiveBoost() {
        val profile = engine.build(
            scene = scene(0.46f, 90f, 0.02f, 0.38f, 0.42f, 0.10f, 0.03f),
            recommendation = recommendation(0.08f, emptyList()),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Rec.709",
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            subjectDetailPlan = SubjectDetailEnhancementPolicy.resolve(
                SubjectDetailEnhancementRequest(
                    profile = VideoDetailProfile.NATURAL_DETAIL,
                    maskAvailable = true,
                    maskTrackingId = 17,
                    maskConfidence = 0.95f,
                    motionScore = 0.02f,
                    noiseFloor = 0.03f,
                    skinToneConfidence = 0.1f
                )
            )
        )

        assertTrue("sharpening=${profile.sharpening}", profile.sharpening >= 0.18f)
        assertEquals("NATURAL_DETAIL", profile.detailSharpeningMetadata.getValue("selectedDetailProfile"))
        assertEquals("true", profile.detailSharpeningMetadata.getValue("naturalZeroSharpening"))
    }

    @Test
    fun selectedFocusedDetailProfileReportsLiveMaskGatedEnhancement() {
        val subjectPlan = SubjectDetailEnhancementPolicy.resolve(
            SubjectDetailEnhancementRequest(
                profile = VideoDetailProfile.FOCUSED_DETAIL,
                maskAvailable = true,
                maskTrackingId = 20,
                maskConfidence = 0.92f,
                motionScore = 0.02f,
                noiseFloor = 0.03f,
                skinToneConfidence = 0.1f
            )
        )
        val profile = engine.build(
            scene = scene(0.46f, 90f, 0.02f, 0.38f, 0.42f, 0.10f, 0.03f),
            recommendation = recommendation(0.08f, emptyList()),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Rec.709",
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            subjectDetailPlan = subjectPlan
        )

        assertEquals(subjectPlan.additiveDetailGain, profile.sharpening, 0.000001f)
        assertEquals("true", profile.detailSharpeningMetadata.getValue("subjectDetailApplied"))
        assertEquals("FOCUSED_DETAIL", profile.detailSharpeningMetadata.getValue("selectedDetailProfile"))
    }

    @Test
    fun strictHighEndRecordingKeepsAggressiveQualityFloorsAcrossAllModes() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val noisyDetailedScene = scene(
                averageLuma = 0.34f,
                estimatedLux = 18f,
                motionScore = 0.12f,
                edgeDensity = 0.24f,
                dynamicRangeScore = 0.76f,
                shadowRatio = 0.28f,
                highlightRatio = 0.11f,
                normalizedNoiseFloor = 0.44f,
                flatRegionStdDev = 8.5f,
                realIsoNoiseFloor = 6_400f,
                perceptualSharpness = 0.42f
            )
            val strongRecommendation = recommendation(
                sharpeningBoost = 0.12f,
                hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
            ).copy(
                shadowDenoiseStrength = 0.86f,
                spatialDenoiseFloor = 0.88f,
                detailPreservationStrength = 0.88f,
                skyProtectionStrength = 0.78f
            )
            val profiles = mapOf(
                "video" to engine.build(
                    scene = noisyDetailedScene,
                    recommendation = strongRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "Natural",
                    recording = true,
                    previewFps = 30f
                ),
                "ndr10+" to engine.build(
                    scene = noisyDetailedScene,
                    recommendation = strongRecommendation,
                    logEnabled = false,
                    hdrEnabled = true,
                    colorProfile = "Rec.2100 NDR10+",
                    hdrMode = "NDR10+",
                    recording = true,
                    previewFps = 30f
                ),
                "nivukx" to engine.build(
                    scene = noisyDetailedScene,
                    recommendation = strongRecommendation,
                    logEnabled = false,
                    hdrEnabled = true,
                    colorProfile = "Neo Wide Gamut",
                    hdrMode = "NIVUKX Vision",
                    recording = true,
                    previewFps = 30f
                ),
                "log" to engine.build(
                    scene = noisyDetailedScene,
                    recommendation = strongRecommendation,
                    logEnabled = true,
                    hdrEnabled = false,
                    colorProfile = "N-Log Cinema",
                    recording = true,
                    previewFps = 30f
                ),
                "raw" to engine.build(
                    scene = noisyDetailedScene,
                    recommendation = strongRecommendation,
                    logEnabled = false,
                    hdrEnabled = false,
                    colorProfile = "RAW",
                    recording = true,
                    previewFps = 30f
                )
            )

            profiles.forEach { (mode, profile) ->
                assertEquals("$mode ditherStrength", 0f, profile.ditherStrength, 0.0001f)
                assertTrue("$mode spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.94f)
                assertTrue("$mode chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.998f)
                val expectedSharpnessFloor = if (mode == "log") 0.62f else 0.72f
                assertTrue("$mode sharpening=${profile.sharpening}", profile.sharpening >= expectedSharpnessFloor)
            }
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictProSdrWideGamutRecordingUsesEliteSdrLockWithoutHdrOrLog() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            val profile = engine.build(
                scene = scene(
                    averageLuma = 0.36f,
                    estimatedLux = 26f,
                    motionScore = 0.08f,
                    edgeDensity = 0.22f,
                    dynamicRangeScore = 0.58f,
                    shadowRatio = 0.24f,
                    highlightRatio = 0.07f,
                    normalizedNoiseFloor = 0.48f,
                    flatRegionStdDev = 8.0f,
                    realIsoNoiseFloor = 2_400f,
                    perceptualSharpness = 0.52f
                ),
                recommendation = recommendation(
                    sharpeningBoost = 0.10f,
                    hdrBracketEvs = emptyList()
                ).copy(
                    shadowDenoiseStrength = 0.74f,
                    spatialDenoiseFloor = 0.62f,
                    detailPreservationStrength = 0.82f,
                    skyProtectionStrength = 0.72f
                ),
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Neo Wide Gamut",
                hdrMode = "OFF",
                recording = true,
                previewFps = 30f
            )

            assertEquals(0f, profile.videoHdrFusionStrength, 0.0001f)
            assertEquals(0f, profile.logStrength, 0.0001f)
            assertEquals(0f, profile.ditherStrength, 0.0001f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.98f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.999f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening >= 0.72f)
            assertTrue("contrast=${profile.contrast}", profile.contrast in 0.985f..1.055f)
            assertTrue("saturation=${profile.saturation}", profile.saturation in 0.985f..1.050f)
            assertFalse(profile.acesColorSignature.contains("output=REC2020_PQ"))
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
