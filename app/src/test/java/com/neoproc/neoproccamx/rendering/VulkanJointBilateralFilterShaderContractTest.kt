package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanJointBilateralFilterShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/VulkanJointBilateralFilter.comp").readText()
    }

    @Test
    fun jointBilateralFilterUsesComputeStorageImagesAndHighResolutionGuide() {
        assertTrue(shaderSource.contains("layout(local_size_x = 16, local_size_y = 16"))
        assertTrue(shaderSource.contains("uniform readonly image2D inputImage"))
        assertTrue(shaderSource.contains("uniform readonly image2D guideImage"))
        assertTrue(shaderSource.contains("uniform writeonly image2D outputImage"))
        assertTrue(shaderSource.contains("rgb10_a2"))
        assertTrue(shaderSource.contains("r16f"))
    }

    @Test
    fun jointBilateralFilterImplementsFiveByFiveSpatialRangeWeights() {
        assertTrue(shaderSource.contains("const float SIGMA_S = 2.4"))
        assertTrue(shaderSource.contains("const float SIGMA_R = 0.082"))
        assertTrue(shaderSource.contains("for (int dy = -2; dy <= 2; dy++)"))
        assertTrue(shaderSource.contains("for (int dx = -2; dx <= 2; dx++)"))
        assertTrue(shaderSource.contains("float spatialWeight = exp(-dSq / TWO_SIGMA_S_SQ)"))
        assertTrue(shaderSource.contains("float rangeWeight = exp(-(diff * diff) / TWO_SIGMA_R_SQ)"))
        assertTrue(shaderSource.contains("imageStore(outputImage, gID, sumColor / max(sumWeight, 1e-6))"))
    }
}
