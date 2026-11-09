package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanLogDenoiseFilterShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/VulkanLogDenoiseFilter.comp").readText()
    }

    @Test
    fun logDenoiseShaderUsesExposureAdaptiveRangeWindow() {
        assertTrue(shaderSource.contains("layout(local_size_x = 16, local_size_y = 16"))
        assertTrue(shaderSource.contains("const float BASE_SIGMA_R = 0.045"))
        assertTrue(shaderSource.contains("const float ALPHA = 1.85"))
        assertTrue(shaderSource.contains("float dynamicSigmaR = BASE_SIGMA_R * (1.0 + ALPHA * (1.0 - centerGuide))"))
        assertTrue(shaderSource.contains("float twoSigmaRSq = 2.0 * dynamicSigmaR * dynamicSigmaR"))
    }

    @Test
    fun logDenoiseShaderUsesFiveByFiveJointBilateralStorageImages() {
        assertTrue(shaderSource.contains("rgb10_a2"))
        assertTrue(shaderSource.contains("r16f"))
        assertTrue(shaderSource.contains("uniform readonly image2D inputImage"))
        assertTrue(shaderSource.contains("uniform readonly image2D guideImage"))
        assertTrue(shaderSource.contains("uniform writeonly image2D outputImage"))
        assertTrue(shaderSource.contains("for (int dy = -2; dy <= 2; dy++)"))
        assertTrue(shaderSource.contains("for (int dx = -2; dx <= 2; dx++)"))
        assertTrue(shaderSource.contains("float rangeWeight = exp(-(diff * diff) / twoSigmaRSq)"))
        assertTrue(shaderSource.contains("imageStore(outputImage, gID, sumColor / max(sumWeight, 1e-6))"))
    }
}
