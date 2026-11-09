package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the two reduced-resolution helper shaders that let Argus optical flow run cheaply enough for
 * 4K/recording: a box downsample (full -> 1/divisor) and a flow upsample+rescale (1/divisor -> full,
 * displacement values scaled back to full-res pixel units). Both classical, no learned weights.
 */
class OmniReducedResShaderContractTest {

    @Test
    fun downsampleShaderBoxAveragesToReducedResolution() {
        val src = File("src/main/cpp/shaders/vulkan/omni_downsample.comp").readText()
        assertTrue(src.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(src.contains("uFull"))
        assertTrue(src.contains("uReduced"))
        assertTrue(src.contains("OmniDownsampleParams"))
        assertTrue(src.contains("divisor"))
        assertTrue(src.contains("acc / max(n, 1.0)"))
        val lower = src.lowercase()
        listOf("tflite", "nnapi", "neural", "onnx").forEach { assertFalse(lower.contains(it)) }
        val header = File("src/main/cpp/generated/omni_downsample_comp_spv.h")
        assertTrue("missing downsample SPIR-V header", header.exists())
        assertTrue(header.readText().contains("kOmniDownsampleCompSpv"))
    }

    @Test
    fun flowUpsampleRescalesReducedFlowToFullResolution() {
        val src = File("src/main/cpp/shaders/vulkan/omni_flow_upsample.comp").readText()
        assertTrue(src.contains("uReducedFlow"))
        assertTrue(src.contains("uFullFlow"))
        assertTrue(src.contains("OmniFlowUpsampleParams"))
        assertTrue(src.contains("rg16f"))
        assertTrue(src.contains("sampleReduced"))      // bilinear interpolation of the reduced flow
        assertTrue(src.contains("* float(dv)"))         // rescale reduced-px displacement to full-px
        assertFalse(src.lowercase().contains("tflite"))
        val header = File("src/main/cpp/generated/omni_flow_upsample_comp_spv.h")
        assertTrue("missing flow-upsample SPIR-V header", header.exists())
        assertTrue(header.readText().contains("kOmniFlowUpsampleCompSpv"))
    }
}
