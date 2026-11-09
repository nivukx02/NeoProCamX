package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PreviewRenderTuningTest {

    private val neutralProfile = SceneRenderProfile(
        temporalBlend = 0f,
        spatialDenoise = 0f,
        chromaDenoise = 0f,
        shadowBoost = 0.15f,
        sharpening = 0f,
        warmth = 0.03f,
        contrast = 1f,
        saturation = 1f,
        highlightCompression = 0.12f,
        logStrength = 0f,
        ditherStrength = 0f,
        videoHdrFusionStrength = 0f
    )

    @Test
    fun summarizeLutReturnsStableBiases() {
        val values = FloatArray(3 * 8) { index ->
            when (index % 3) {
                0 -> 0.62f
                1 -> 0.48f
                else -> 0.32f
            }
        }

        val summary = PreviewRenderTuning.summarizeLut(values, 2)

        assertNotNull(summary)
        assertTrue(summary!!.redBias > 0f)
        assertTrue(summary.blueBias < 0f)
        assertTrue(summary.warmthBias > 0f)
        assertTrue(summary.lumaRange > 0f)
        assertTrue(summary.chromaEnergy > 0f)
    }

    @Test
    fun buildGradeAdjustmentsRespondsToLoadAndLut() {
        val lutSummary = PreviewRenderTuning.LutSummary(
            redBias = 0.12f,
            greenBias = 0.02f,
            blueBias = -0.08f,
            warmthBias = 0.06f
        )

        val standard = PreviewRenderTuning.buildGradeAdjustments(
            profile = neutralProfile,
            highLoadPreviewMode = false,
            lutEnabled = true,
            lutSummary = lutSummary
        )
        val highLoad = PreviewRenderTuning.buildGradeAdjustments(
            profile = neutralProfile,
            highLoadPreviewMode = true,
            lutEnabled = true,
            lutSummary = lutSummary
        )

        assertTrue(standard.saturation in 0.68f..1.25f)
        assertTrue(standard.redScale > 1f)
        assertTrue(highLoad.saturation in 0.68f..1.25f)
    }

    @Test
    fun lutHighlightRangeStillAccumulatesWhenLumaBiasIsPresent() {
        val lowRange = PreviewRenderTuning.buildGradeAdjustments(
            profile = neutralProfile,
            highLoadPreviewMode = false,
            lutEnabled = true,
            lutSummary = PreviewRenderTuning.LutSummary(
                redBias = 0f,
                greenBias = 0f,
                blueBias = 0f,
                warmthBias = 0f,
                lumaBias = 0.10f,
                lumaRange = 0.50f,
                chromaEnergy = 0.02f
            )
        )
        val highRange = PreviewRenderTuning.buildGradeAdjustments(
            profile = neutralProfile,
            highLoadPreviewMode = false,
            lutEnabled = true,
            lutSummary = PreviewRenderTuning.LutSummary(
                redBias = 0f,
                greenBias = 0f,
                blueBias = 0f,
                warmthBias = 0f,
                lumaBias = 0.10f,
                lumaRange = 0.90f,
                chromaEnergy = 0.02f
            )
        )

        assertTrue(
            "low=${lowRange.greenBias} high=${highRange.greenBias}",
            highRange.greenBias < lowRange.greenBias - 0.5f
        )
    }

    @Test
    fun highLoadRecordingPreviewLimitsChromaAndContrastAmplification() {
        val noisyHdrProfile = neutralProfile.copy(
            contrast = 2.85f,
            saturation = 3.10f,
            highlightCompression = 0.72f,
            videoHdrFusionStrength = 0.92f
        )

        val standard = PreviewRenderTuning.buildGradeAdjustments(
            profile = noisyHdrProfile,
            highLoadPreviewMode = false,
            lutEnabled = false,
            lutSummary = null
        )
        val highLoad = PreviewRenderTuning.buildGradeAdjustments(
            profile = noisyHdrProfile,
            highLoadPreviewMode = true,
            lutEnabled = false,
            lutSummary = null
        )

        assertTrue(highLoad.saturation < standard.saturation)
        assertTrue(highLoad.contrast < standard.contrast)
        assertTrue("saturation=${highLoad.saturation}", highLoad.saturation <= 2.20f)
        assertTrue("contrast=${highLoad.contrast}", highLoad.contrast <= 2.45f)
    }

    @Test
    fun hdrPreviewGradeDoesNotInjectRedBiasIntoNdrOrNivukxSkinTones() {
        val nivukxStyleProfile = SceneRenderProfile(
            temporalBlend = 0f,
            spatialDenoise = 0.42f,
            chromaDenoise = 0.78f,
            shadowBoost = 0.48f,
            sharpening = 0.24f,
            warmth = 0.04f,
            contrast = 1.01f,
            saturation = 0.93f,
            highlightCompression = 0.50f,
            logStrength = 0f,
            ditherStrength = 0f,
            videoHdrFusionStrength = 0.68f
        )

        val adjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = nivukxStyleProfile,
            highLoadPreviewMode = false,
            lutEnabled = false,
            lutSummary = null
        )

        assertTrue("redBias=${adjustments.redBias} greenBias=${adjustments.greenBias}",
            adjustments.redBias <= adjustments.greenBias + 2f)
        assertTrue("blueBias=${adjustments.blueBias} greenBias=${adjustments.greenBias}",
            adjustments.blueBias >= adjustments.greenBias - 2f)
        assertTrue("redScale=${adjustments.redScale}", adjustments.redScale <= 1.01f)
        assertTrue("blueScale=${adjustments.blueScale}", adjustments.blueScale >= 0.99f)
    }

    @Test
    fun buildGradeAdjustmentsCompensatesLutDynamicRange() {
        val lowRangeLut = PreviewRenderTuning.LutSummary(
            redBias = 0.04f,
            greenBias = 0.01f,
            blueBias = -0.02f,
            warmthBias = 0.02f,
            lumaBias = 0.01f,
            lumaRange = 0.18f,
            chromaEnergy = 0.16f
        )
        val highRangeLut = PreviewRenderTuning.LutSummary(
            redBias = 0.04f,
            greenBias = 0.01f,
            blueBias = -0.02f,
            warmthBias = 0.02f,
            lumaBias = 0.01f,
            lumaRange = 0.82f,
            chromaEnergy = 0.36f
        )

        val lowRangeAdjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = neutralProfile,
            highLoadPreviewMode = false,
            lutEnabled = true,
            lutSummary = lowRangeLut
        )
        val highRangeAdjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = neutralProfile,
            highLoadPreviewMode = false,
            lutEnabled = true,
            lutSummary = highRangeLut
        )

        assertTrue(lowRangeAdjustments.contrast > highRangeAdjustments.contrast)
        assertTrue(lowRangeAdjustments.saturation > highRangeAdjustments.saturation)
    }

    @Test
    fun logPreviewParityAvoidsDoubleFlatteningInPreviewPaint() {
        val logProfile = SceneRenderProfile(
            temporalBlend = 0f,
            spatialDenoise = 0f,
            chromaDenoise = 0f,
            shadowBoost = 0.24f,
            sharpening = 0f,
            warmth = 0.02f,
            contrast = 0.60f,
            saturation = 0.56f,
            highlightCompression = 0.42f,
            logStrength = 0.86f,
            ditherStrength = 0.013f,
            videoHdrFusionStrength = 0f,
            logPreviewParity = com.neoproc.neoproccamx.color.VideoLogPreviewParityPolicy
                .resolve("Sony S-Log3", logEnabled = true)
        )

        val adjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = logProfile,
            highLoadPreviewMode = false,
            lutEnabled = false,
            lutSummary = null
        )

        assertTrue(adjustments.contrast >= 0.60f)
        assertTrue(adjustments.saturation >= 0.56f)
    }

    @Test
    fun logHdrPreviewRetainsReadableHighlightBiasInBrightScenes() {
        val brightLogProfile = SceneRenderProfile(
            temporalBlend = 0f,
            spatialDenoise = 0f,
            chromaDenoise = 0f,
            shadowBoost = 0.22f,
            sharpening = 0f,
            warmth = 0.02f,
            contrast = 0.66f,
            saturation = 0.60f,
            highlightCompression = 0.46f,
            logStrength = 0.88f,
            ditherStrength = 0.012f,
            videoHdrFusionStrength = 0.64f,
            logPreviewParity = com.neoproc.neoproccamx.color.VideoLogPreviewParityPolicy
                .resolve("ARRI LogC4", logEnabled = true)
        )

        val adjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = brightLogProfile,
            highLoadPreviewMode = false,
            lutEnabled = false,
            lutSummary = null
        )

        assertTrue(adjustments.redBias > -12f)
        assertTrue(adjustments.greenBias > -12f)
        assertTrue(adjustments.blueBias > -14f)
    }

    @Test
    fun directPreviewFallbackCompensatesWarmGreenCast() {
        val tinted = PreviewRenderTuning.GradeAdjustments(
            contrast = 0.82f,
            saturation = 0.74f,
            redScale = 1.12f,
            greenScale = 0.93f,
            blueScale = 0.88f,
            redBias = 9f,
            greenBias = -4f,
            blueBias = -11f,
            shadowBiasScale = 0.72f,
            highlightBiasScale = 0.68f
        )

        val stabilized = PreviewRenderTuning.stabilizeForDirectPreviewFallback(tinted)

        assertTrue(stabilized.redScale in 0.98f..1.02f)
        assertTrue(stabilized.greenScale in 0.98f..1.02f)
        assertTrue(stabilized.blueScale in 0.98f..1.02f)
        assertTrue(abs(stabilized.redBias) <= 2f)
        assertTrue(abs(stabilized.greenBias) <= 2f)
        assertTrue(abs(stabilized.blueBias) <= 2f)
        assertTrue(stabilized.contrast in 0.98f..1.01f)
        assertTrue(stabilized.saturation in 0.97f..1.01f)
    }

    @Test
    fun directPreviewFallbackNeutralizesCastCompletelyForOriginalColor() {
        val tinted = PreviewRenderTuning.GradeAdjustments(
            contrast = 0.76f,
            saturation = 0.69f,
            redScale = 0.91f,
            greenScale = 1.08f,
            blueScale = 1.16f,
            redBias = -8f,
            greenBias = 6f,
            blueBias = 14f,
            shadowBiasScale = 0.58f,
            highlightBiasScale = 0.72f
        )

        val stabilized = PreviewRenderTuning.stabilizeForDirectPreviewFallback(tinted)

        assertEquals(1f, stabilized.contrast, 0.0001f)
        assertEquals(1f, stabilized.saturation, 0.0001f)
        assertEquals(1f, stabilized.redScale, 0.0001f)
        assertEquals(1f, stabilized.greenScale, 0.0001f)
        assertEquals(1f, stabilized.blueScale, 0.0001f)
        assertEquals(0f, stabilized.redBias, 0.0001f)
        assertEquals(0f, stabilized.greenBias, 0.0001f)
        assertEquals(0f, stabilized.blueBias, 0.0001f)
        assertEquals(1f, stabilized.shadowBiasScale, 0.0001f)
        assertEquals(1f, stabilized.highlightBiasScale, 0.0001f)
    }

    @Test
    fun neutralVideoPreviewUsesTheSameNeutralizationAsDirectFallback() {
        val tinted = PreviewRenderTuning.GradeAdjustments(
            contrast = 0.81f,
            saturation = 0.72f,
            redScale = 1.07f,
            greenScale = 0.95f,
            blueScale = 0.89f,
            redBias = 6f,
            greenBias = -5f,
            blueBias = -9f,
            shadowBiasScale = 0.66f,
            highlightBiasScale = 0.74f
        )

        val resolved = PreviewRenderTuning.stabilizeForNeutralVideoPreview(
            adjustments = tinted,
            neutralVideoPreview = true
        )

        assertEquals(1f, resolved.contrast, 0.0001f)
        assertEquals(1f, resolved.saturation, 0.0001f)
        assertEquals(1f, resolved.redScale, 0.0001f)
        assertEquals(1f, resolved.greenScale, 0.0001f)
        assertEquals(1f, resolved.blueScale, 0.0001f)
        assertEquals(0f, resolved.redBias, 0.0001f)
        assertEquals(0f, resolved.greenBias, 0.0001f)
        assertEquals(0f, resolved.blueBias, 0.0001f)
    }

    @Test
    fun nonNeutralVideoPreviewLeavesGradeUntouched() {
        val tinted = PreviewRenderTuning.GradeAdjustments(
            contrast = 0.84f,
            saturation = 0.79f,
            redScale = 1.04f,
            greenScale = 0.98f,
            blueScale = 0.94f,
            redBias = 3f,
            greenBias = -2f,
            blueBias = -4f,
            shadowBiasScale = 0.71f,
            highlightBiasScale = 0.69f
        )

        val resolved = PreviewRenderTuning.stabilizeForNeutralVideoPreview(
            adjustments = tinted,
            neutralVideoPreview = false
        )

        assertEquals(tinted, resolved)
    }

    @Test
    fun stabilizationTransformCentersAroundAnchor() {
        val transform = PreviewRenderTuning.computeStabilizationTransform(
            strength = 0.8f,
            anchorX = 0.2f,
            anchorY = 0.8f,
            contentWidth = 1000f,
            contentHeight = 500f
        )

        assertTrue(transform.zoom > 1f)
        assertTrue(transform.translationX > 0f)
        assertTrue(transform.translationY < 0f)

        val neutral = PreviewRenderTuning.computeStabilizationTransform(
            strength = 0f,
            anchorX = 0.5f,
            anchorY = 0.5f,
            contentWidth = 1000f,
            contentHeight = 500f
        )
        assertEquals(1f, neutral.zoom, 0.0001f)
        assertEquals(0f, neutral.translationX, 0.0001f)
        assertEquals(0f, neutral.translationY, 0.0001f)
    }

    @Test
    fun stabilizationTransformClampsTranslationInsideOverscanBudget() {
        val transform = PreviewRenderTuning.computeStabilizationTransform(
            strength = 1f,
            anchorX = 0f,
            anchorY = 1f,
            contentWidth = 1000f,
            contentHeight = 600f
        )

        val maxTranslationX = ((1000f * transform.zoom) - 1000f) * 0.5f
        val maxTranslationY = ((600f * transform.zoom) - 600f) * 0.5f
        assertTrue(transform.zoom in 1f..1.2f)
        assertTrue(abs(transform.translationX) <= maxTranslationX + 0.001f)
        assertTrue(abs(transform.translationY) <= maxTranslationY + 0.001f)
    }
}
