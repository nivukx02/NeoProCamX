package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanDenoiseComputeShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/denoise.comp").readText()
    }

    @Test
    fun computeDenoisePreservesLumaDetailWithoutReintroducingChromaGrain() {
        assertTrue(shaderSource.contains("vec3 lumaOnlyDetail"))
        assertTrue(shaderSource.contains("float chromaNoiseReject"))
        assertTrue(shaderSource.contains("float detailReturn"))
        assertTrue(shaderSource.contains("denoised += lumaOnlyDetail * detailReturn"))
        assertFalse(shaderSource.contains("denoised += originalDetail * params.detailPreserve"))
    }

    @Test
    fun computeDenoiseUsesNearCeilingBlendForExtremeNoise() {
        assertTrue(shaderSource.contains("float extremeNoise = smoothstep(0.62, 1.0, params.noiseLevel)"))
        assertTrue(shaderSource.contains("denoised = mix(denoised, wavelet, (0.18 + 0.20 * extremeNoise) * params.noiseLevel)"))
        assertTrue(shaderSource.contains("denoised = mix(denoised, center, edgeFactor * mix(0.32, 0.16, extremeNoise))"))
    }

    @Test
    fun computeDenoiseContainsPeronaMalikAndTwentyFourFrameMotionAccumulationHooks() {
        assertTrue(shaderSource.contains("ASYNC_COMPUTE_QUEUE_READY"))
        assertTrue(shaderSource.contains("peronaMalikConductance"))
        assertTrue(shaderSource.contains("peronaMalikAnisotropicDiffusion"))
        assertTrue(shaderSource.contains("motionCompensatedAccumulation9Frame"))
        assertTrue(shaderSource.contains("for (int frame = 0; frame < 9; frame++)"))
        assertTrue(shaderSource.contains("denoised = peronaMalikAnisotropicDiffusion"))
        assertTrue(shaderSource.contains("denoised = motionCompensatedAccumulation9Frame"))
    }

    @Test
    fun computeDenoiseContainsAnscombeOklabAndKalmanBucyPlanckPath() {
        assertTrue(shaderSource.contains("generalizedAnscombeVstForward"))
        assertTrue(shaderSource.contains("inverseGeneralizedAnscombeVst"))
        assertTrue(shaderSource.contains("sensorNoiseS"))
        assertTrue(shaderSource.contains("sensorNoiseO"))
        assertTrue(shaderSource.contains("0.0020 + params.noiseLevel * 0.0020"))
        assertTrue(shaderSource.contains("0.00038 + params.rangeSigma * 0.00038"))
        assertTrue(shaderSource.contains("0.0000004"))
        assertTrue(shaderSource.contains("rgbToOklabPlanck"))
        assertTrue(shaderSource.contains("oklabBayesShrinkVst"))
        assertTrue(shaderSource.contains("kalmanBucySpaceTimeFilter"))
        assertTrue(shaderSource.contains("denoised = anscombeOklabBayesianDenoise"))
        assertTrue(shaderSource.contains("denoised = kalmanBucySpaceTimeFilter"))
    }

    @Test
    fun computeDenoiseContainsSqueezedStateSchrodingerFilterPath() {
        assertTrue(shaderSource.contains("nonLinearSchrodingerPotential"))
        assertTrue(shaderSource.contains("schrodingerLaplacian"))
        assertTrue(shaderSource.contains("squeezedStateSchrodingerQuantumFilter"))
        assertTrue(shaderSource.contains("denoised = squeezedStateSchrodingerQuantumFilter"))
    }

    @Test
    fun computeDenoiseDeclaresOmnipotentQuantumNoisePipeline() {
        assertTrue(shaderSource.contains("OMNIPOTENT_QUANTUM_NOISE_PIPELINE"))
        assertTrue(shaderSource.indexOf("denoised = anscombeOklabBayesianDenoise") <
            shaderSource.indexOf("denoised = kalmanBucySpaceTimeFilter"))
        assertTrue(shaderSource.indexOf("denoised = kalmanBucySpaceTimeFilter") <
            shaderSource.indexOf("denoised = squeezedStateSchrodingerQuantumFilter"))
    }

    @Test
    fun computeDenoiseDeclaresZenithCosmicPlanckPipelineWithCorrectAnscombeMarker() {
        assertTrue(shaderSource.contains("ZENITH_COSMIC_PLANCK_DENOISE_PIPELINE"))
        assertTrue(shaderSource.contains("PLANCK_ANSCOMBE_KALMAN_BUCY_PATH"))
        assertFalse(shaderSource.contains("PLANCK_ANSCCOMBE_KALMAN_BUCY_PATH"))
        assertTrue(shaderSource.indexOf("denoised = anscombeOklabBayesianDenoise") <
            shaderSource.indexOf("denoised = kalmanBucySpaceTimeFilter"))
        assertTrue(shaderSource.indexOf("denoised = kalmanBucySpaceTimeFilter") <
            shaderSource.indexOf("denoised = squeezedStateSchrodingerQuantumFilter"))
    }
}
