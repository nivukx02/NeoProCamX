package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HdrMergeShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/hdr_merge.comp").readText()
    }

    @Test
    fun hdrMergePrefersShortExposureHighlightsAndLongExposureShadows() {
        assertTrue(shaderSource.contains("exposureAwareNdrMergeWeight"))
        assertTrue(shaderSource.contains("preferUnderExposedHighlights"))
        assertTrue(shaderSource.contains("preferLongExposureShadows"))
        assertTrue(shaderSource.contains("ghostRejectWeight"))
        assertTrue(shaderSource.contains("weight *= ghostRejectWeight"))
    }

    @Test
    fun hdrMergeContainsRawDomainHighlightReconstructionBeforeToneMap() {
        assertTrue(shaderSource.contains("rawDomainHighlightReconstruct"))
        assertTrue(shaderSource.contains("neighborHighlightStructure"))
        assertTrue(shaderSource.indexOf("merged = rawDomainHighlightReconstruct") <
            shaderSource.indexOf("vec3 exposed = merged * exposureComp"))
    }

    @Test
    fun hdrMergeNormalizesRgbAndUsesSkinMidExposureConfidence() {
        assertTrue(shaderSource.contains("rgbChannelNormalization"))
        assertTrue(shaderSource.contains("mediumExposureSkinWeight"))
        assertTrue(shaderSource.contains("perPixelLuminanceConfidence"))
        assertTrue(shaderSource.contains("linear = rgbChannelNormalization(linear)"))
        assertTrue(shaderSource.contains("weight *= mediumExposureSkinWeight"))
        assertTrue(shaderSource.contains("weight *= perPixelLuminanceConfidence"))
    }
}
