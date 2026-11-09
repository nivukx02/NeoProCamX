package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DualGainFusionShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/dual_gain_fusion.comp").readText()
    }
    private val denoiseSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/denoise.comp").readText()
    }
    private val nativeCameraBridgeSource: String by lazy {
        File("src/main/cpp/native_camera_bridge.cpp").readText()
    }
    private val kotlinBridgeSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/NativeCameraBridge.kt").readText()
    }

    @Test
    fun dualGainShaderFusesLcgAndHcgInAcesCgWithMotionMask() {
        assertTrue(shaderSource.contains("NEO_ACESCG_NON_CERTIFIED"))
        assertTrue(shaderSource.contains("ACESCG_LINEAR_WORKING_SPACE"))
        assertTrue(shaderSource.contains("readonly uniform image2D uLcgAcesCg"))
        assertTrue(shaderSource.contains("readonly uniform image2D uHcgAcesCg"))
        assertTrue(shaderSource.contains("writeonly uniform image2D uFusedAcesCg"))
        assertTrue(shaderSource.contains("writeonly uniform image2D uMotionConfidence"))
        assertTrue(shaderSource.contains("kGain"))
        assertTrue(shaderSource.contains("subPixelLumaDifference"))
        assertTrue(shaderSource.contains("motionMask"))
        assertTrue(shaderSource.contains("hcgSaturation"))
        assertTrue(shaderSource.contains("shadowStretch"))
        assertTrue(shaderSource.contains("mix(hcgSignal, equalizedLcg, motionMask)"))
        assertFalse(shaderSource.contains("Sony"))
        assertFalse(shaderSource.contains("OmniVision"))
        assertFalse(shaderSource.contains("Dolby"))
    }

    @Test
    fun dualGainShaderHasGeneratedSpirvArtifactForNativePipelineUse() {
        val generatedHeader = File("src/main/cpp/generated/dual_gain_fusion_comp_spv.h")

        assertTrue("dual_gain_fusion.comp must be compiled into generated SPIR-V header", generatedHeader.exists())
        val headerSource = generatedHeader.readText()
        assertTrue(headerSource.contains("kDualGainFusionCompSpv"))
        assertTrue(headerSource.contains("kDualGainFusionCompSpvCount"))
    }

    @Test
    fun denoiseShaderAcceptsDualGainMotionConfidenceContract() {
        assertTrue(denoiseSource.contains("dualGainMotionConfidence"))
        assertTrue(denoiseSource.contains("motionAwareTemporalDenoiseScale"))
    }

    @Test
    fun dualGainShaderContainsWavefrontChromaticAlignmentAndFloatExposureBlend() {
        assertTrue(shaderSource.contains("coherentWavefrontChromaticAberrationCorrection"))
        assertTrue(shaderSource.contains("localizedLuminanceWeight"))
        assertTrue(shaderSource.contains("acescgExposureBlend32f"))
        assertTrue(shaderSource.contains("equalizedLcg = coherentWavefrontChromaticAberrationCorrection"))
        assertTrue(shaderSource.contains("hcgSignal = coherentWavefrontChromaticAberrationCorrection"))
        assertTrue(shaderSource.contains("staticBlend = acescgExposureBlend32f"))
    }

    @Test
    fun dualGainShaderContainsCosmicSubPixelWavefrontAlignmentAndExposureFusion() {
        assertTrue(shaderSource.contains("subPixelWavefrontAlignment"))
        assertTrue(shaderSource.contains("linearAcesCgExposureFusionCosmic"))
        assertTrue(shaderSource.contains("equalizedLcg = subPixelWavefrontAlignment"))
        assertTrue(shaderSource.contains("hcgSignal = subPixelWavefrontAlignment"))
        assertTrue(shaderSource.contains("staticBlend = linearAcesCgExposureFusionCosmic"))
    }

    @Test
    fun dualGainShaderDeclaresOmnipotentFusionPipeline() {
        assertTrue(shaderSource.contains("OMNIPOTENT_DUAL_GAIN_FUSION"))
        assertTrue(shaderSource.indexOf("equalizedLcg = coherentWavefrontChromaticAberrationCorrection") <
            shaderSource.indexOf("equalizedLcg = subPixelWavefrontAlignment"))
        assertTrue(shaderSource.indexOf("vec3 staticBlend = acescgExposureBlend32f") <
            shaderSource.indexOf("staticBlend = linearAcesCgExposureFusionCosmic"))
    }

    @Test
    fun dualGainShaderDeclaresZenithCosmicPlanckFusionAndMicrosecondGuard() {
        assertTrue(shaderSource.contains("ZENITH_COSMIC_PLANCK_DUAL_GAIN_PIPELINE"))
        assertTrue(shaderSource.contains("MICROSECOND_DUAL_GAIN_SYNC_GUARD"))
        assertTrue(shaderSource.indexOf("equalizedLcg = coherentWavefrontChromaticAberrationCorrection") <
            shaderSource.indexOf("equalizedLcg = subPixelWavefrontAlignment"))
        assertTrue(shaderSource.indexOf("vec3 staticBlend = acescgExposureBlend32f") <
            shaderSource.indexOf("staticBlend = linearAcesCgExposureFusionCosmic"))
    }

    @Test
    fun nativeBridgeExposesDualGainValidationWithoutPrivateSensorExtensions() {
        assertTrue(kotlinBridgeSource.contains("validateDualGainHardwareBuffers"))
        assertTrue(kotlinBridgeSource.contains("nativeValidateDualGainHardwareBuffers"))
        assertTrue(nativeCameraBridgeSource.contains("nativeValidateDualGainHardwareBuffers"))
        assertTrue(nativeCameraBridgeSource.contains("PRIVATE_EXTENSION_REJECTED_PUBLIC_CAMERA2_ONLY"))
        assertTrue(nativeCameraBridgeSource.contains("pixelCopy=false"))
        assertFalse(nativeCameraBridgeSource.contains("Sony"))
        assertFalse(nativeCameraBridgeSource.contains("OmniVision"))
    }
}
