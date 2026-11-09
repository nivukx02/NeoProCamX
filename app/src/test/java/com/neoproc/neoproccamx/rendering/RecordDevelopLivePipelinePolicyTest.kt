package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDevelopLivePipelinePolicyTest {

    @After
    fun resetFlag() {
        RecordDevelopModeFlag.registerOnChanged(null)
        RecordDevelopModeFlag.set(false)
    }

    @Test
    fun `light master is active only for video surfaces while record-develop is enabled`() {
        RecordDevelopModeFlag.set(false)
        assertFalse(RecordDevelopLivePipelinePolicy.active(videoSurface = true))

        RecordDevelopModeFlag.set(true)
        assertTrue(RecordDevelopLivePipelinePolicy.active(videoSurface = true))
        assertFalse(RecordDevelopLivePipelinePolicy.active(videoSurface = false))
    }

    @Test
    fun `active light master replaces encoder grade with neutral packet`() {
        RecordDevelopModeFlag.set(true)
        val candidate = FloatArray(16) { index -> index + 0.25f }

        val resolved = RecordDevelopLivePipelinePolicy.encoderGrade(videoSurface = true, candidate = candidate)

        assertArrayEquals(RecordDevelopLivePipelinePolicy.NEUTRAL_ENCODER_GRADE, resolved, 0f)
        assertFalse("must return a defensive copy", resolved === RecordDevelopLivePipelinePolicy.NEUTRAL_ENCODER_GRADE)
    }

    @Test
    fun `active light master disables encoder color science and live enhance`() {
        RecordDevelopModeFlag.set(true)
        val colorCandidate = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { 0.75f }
        val enhanceCandidate = FloatArray(44) { 0.5f }

        assertArrayEquals(
            VulkanColorScienceUniforms.disabled().pack(),
            RecordDevelopLivePipelinePolicy.encoderColorScience(videoSurface = true, candidate = colorCandidate),
            0f
        )
        assertNull(RecordDevelopLivePipelinePolicy.liveEnhanceUniforms(videoSurface = true, candidate = enhanceCandidate))
    }

    @Test
    fun `active light master flattens live gpu enhancement uniforms`() {
        RecordDevelopModeFlag.set(true)

        val flat = RecordDevelopLivePipelinePolicy.enhancementUniforms(
            videoSurface = true,
            candidate = candidateUniforms()
        )

        assertEquals(0f, flat.temporalBlend, 0f)
        assertEquals(0f, flat.spatialDenoise, 0f)
        assertEquals(0f, flat.chromaDenoise, 0f)
        assertEquals(0f, flat.sharpening, 0f)
        assertEquals(1f, flat.contrast, 0f)
        assertEquals(1f, flat.saturation, 0f)
        assertEquals(0f, flat.subjectDetailGain, 0f)
        assertEquals(0f, flat.aiMotionTemporalBlend, 0f)
        assertFalse(flat.dualGainFusionEnabled)
        assertFalse(flat.dualGainMotionMaskEnabled)
        assertTrue(flat.signature.contains("recordDevelopLightMaster=true"))
    }

    @Test
    fun `active light master strips record creative lut`() {
        RecordDevelopModeFlag.set(true)
        val candidate = FloatArray(27) { 1f } to 3

        assertNull(RecordDevelopLivePipelinePolicy.recordCreativeLut(videoSurface = true, candidate = candidate))
    }

    @Test
    fun `flag off preserves live candidates`() {
        RecordDevelopModeFlag.set(false)
        val grade = FloatArray(16) { 0.25f }
        val color = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { 0.75f }
        val enhance = FloatArray(44) { 0.5f }
        val uniforms = candidateUniforms()
        val lut = FloatArray(27) { 1f } to 3

        assertTrue(RecordDevelopLivePipelinePolicy.encoderGrade(videoSurface = true, candidate = grade) === grade)
        assertTrue(RecordDevelopLivePipelinePolicy.encoderColorScience(videoSurface = true, candidate = color) === color)
        assertTrue(RecordDevelopLivePipelinePolicy.enhancementUniforms(videoSurface = true, candidate = uniforms) === uniforms)
        assertTrue(RecordDevelopLivePipelinePolicy.liveEnhanceUniforms(videoSurface = true, candidate = enhance) === enhance)
        assertTrue(RecordDevelopLivePipelinePolicy.recordCreativeLut(videoSurface = true, candidate = lut) === lut)
    }

    private fun candidateUniforms(): GpuImageEnhancementUniforms =
        GpuImageEnhancementUniforms(
            signalFamily = GpuSignalFamily.SDR,
            colorSpace = GpuColorSpace.REC709,
            colorScience = GpuColorScience.NATURAL,
            activeTransport = GpuActiveTransport.REC709_SDR,
            temporalBlend = 0.4f,
            spatialDenoise = 0.7f,
            chromaDenoise = 0.8f,
            shadowBoost = 0.2f,
            sharpening = 0.9f,
            warmth = 0.1f,
            contrast = 1.2f,
            saturation = 1.3f,
            highlightCompression = 0.4f,
            logStrength = 0.2f,
            videoHdrFusionStrength = 0.3f,
            shadowChromaDeadband = 0.01f,
            aliasSafeMaxDetailDelta = 0.02f,
            fineTextureThreshold = 0.03f,
            mediumEdgeThreshold = 0.04f,
            strongEdgeThreshold = 0.05f,
            fineTextureDetailGain = 0.2f,
            mediumDetailGain = 0.3f,
            strongDetailGain = 0.4f,
            temporalLumaBlend = 0.5f,
            temporalChromaBlend = 0.6f,
            iso = 400f,
            noiseMetric = 0.7f,
            localChromaSpikeSmoothing = 0.8f,
            sharpeningFloor = 0.1f,
            sharpeningCeiling = 0.9f,
            skinDetailProtection = 0.5f,
            migrationApplied = true,
            dualGainFusionEnabled = true,
            dualGainMotionMaskEnabled = true,
            dualGainKGain = 8f,
            dualGainNoiseFloor = 0.02f,
            dualGainSaturationThreshold = 0.9f,
            dualGainTimestampSkew = 0.1f,
            sensorShotNoiseCoefficient = 0.003f,
            sensorReadNoiseCoefficient = 0.002f,
            detailProfileId = 3f,
            subjectMaskAvailable = 1f,
            subjectDetailGain = 0.75f,
            subjectMaskFeather = 0.25f,
            v20ZeroGrainClarityActive = true,
            v20ZeroGrainLock = 0.3f,
            v20RgbGrainQuarantine = 0.4f,
            v20MotionBlurRejection = 0.5f,
            v20MicroDetailHonesty = 0.6f,
            v20ZeroGrainClaritySignature = "candidate",
            aiMotionTemporalBlend = 0.45f,
            signature = "candidate"
        )
}
