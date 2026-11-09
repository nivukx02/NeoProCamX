package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanSharpenComputeShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/sharpen.comp").readText()
    }

    @Test
    fun sharpenShaderContainsRichardsonLucyPsfDeconvolutionStage() {
        assertTrue(shaderSource.contains("RICHARDSON_LUCY_PSF_DECONVOLUTION"))
        assertTrue(shaderSource.contains("lensPointSpreadFunction"))
        assertTrue(shaderSource.contains("psfTranspose"))
        assertTrue(shaderSource.contains("richardsonLucyDeconvolution"))
        assertTrue(shaderSource.contains("for (int iteration = 0; iteration < 5; iteration++)"))
        assertTrue(shaderSource.contains("sharpened = richardsonLucyDeconvolution"))
    }

    @Test
    fun sharpenShaderContainsFienupHioWavefrontPhaseRetrievalStage() {
        assertTrue(shaderSource.contains("FIENUP_HIO_WAVEFRONT_PHASE_RETRIEVAL"))
        assertTrue(shaderSource.contains("zernikeWavefrontAberration"))
        assertTrue(shaderSource.contains("complexPupilFunction"))
        assertTrue(shaderSource.contains("bartenHvsDeconvolutionMask"))
        assertTrue(shaderSource.contains("fienupHioWavefrontPhaseRetrieval"))
        assertTrue(shaderSource.contains("for (int iteration = 0; iteration < 5; iteration++)"))
        assertTrue(shaderSource.contains("sharpened = fienupHioWavefrontPhaseRetrieval"))
    }

    @Test
    fun sharpenShaderContainsHermiteGaussianWavefrontPhaseRetrievalStage() {
        assertTrue(shaderSource.contains("HERMITE_GAUSSIAN_WAVEFRONT_PHASE_RETRIEVAL"))
        assertTrue(shaderSource.contains("zernike37WavefrontAberration"))
        assertTrue(shaderSource.contains("hermiteGaussianMode"))
        assertTrue(shaderSource.contains("hermiteGaussianModalExpansion"))
        assertTrue(shaderSource.contains("hermiteGaussianWavefrontPhaseRetrieval"))
        assertTrue(shaderSource.contains("sharpened = hermiteGaussianWavefrontPhaseRetrieval"))
    }

    @Test
    fun sharpenShaderDeclaresOmnipotentWavefrontIspOrder() {
        assertTrue(shaderSource.contains("OMNIPOTENT_WAVEFRONT_ISP"))
        assertTrue(shaderSource.indexOf("sharpened = richardsonLucyDeconvolution") <
            shaderSource.indexOf("sharpened = fienupHioWavefrontPhaseRetrieval"))
        assertTrue(shaderSource.indexOf("sharpened = fienupHioWavefrontPhaseRetrieval") <
            shaderSource.indexOf("sharpened = hermiteGaussianWavefrontPhaseRetrieval"))
    }

    @Test
    fun sharpenShaderDeclaresZenithCosmicPlanckWavefrontPipeline() {
        assertTrue(shaderSource.contains("ZENITH_COSMIC_PLANCK_WAVEFRONT_PIPELINE"))
        assertTrue(shaderSource.contains("zernike37WavefrontAberration"))
        assertTrue(shaderSource.indexOf("sharpened = fienupHioWavefrontPhaseRetrieval") <
            shaderSource.indexOf("sharpened = hermiteGaussianWavefrontPhaseRetrieval"))
        assertTrue(shaderSource.indexOf("modal *= 1.0 + zernike37WavefrontAberration") <
            shaderSource.indexOf("vec3 hermiteGaussianWavefrontPhaseRetrieval"))
    }
}
