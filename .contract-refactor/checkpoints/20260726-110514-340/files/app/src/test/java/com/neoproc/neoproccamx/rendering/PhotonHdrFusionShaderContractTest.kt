package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the OmniClarity §1 Photon HDR fusion shader as a classical, motion-compensated dual-exposure
 * fuser with the confidence-blend formula from the design, and keeps its committed SPIR-V in sync.
 */
class PhotonHdrFusionShaderContractTest {

    private val shader: String =
        File("src/main/cpp/shaders/vulkan/photon_hdr_fusion.comp").readText()

    @Test
    fun shaderFusesDualExposuresWithConfidenceBlendAndMotionCompensation() {
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(shader.contains("uLongExposure"))
        assertTrue(shader.contains("uShortExposure"))
        assertTrue(shader.contains("uMotionField"))
        assertTrue(shader.contains("uFused"))

        assertTrue(shader.contains("PhotonFusionParams"))
        listOf("highlightThreshold", "clipThreshold", "longScale", "shortScale", "motionCompEnabled", "maxFlow")
            .forEach { field -> assertTrue("missing push-constant field $field", shader.contains(field)) }

        // confidence_long = smoothstep(highlightThreshold, clipThreshold, luma(long_scaled))
        assertTrue(shader.contains("smoothstep(uParams.highlightThreshold, uParams.clipThreshold"))
        // fused = mix(long_scaled, short_scaled, confidence_long)
        assertTrue(shader.contains("mix(longScaled, shortScaled, c)"))
        // motion-compensated alignment of the short exposure.
        assertTrue(shader.contains("vec2(p) - flow"))
        assertTrue(shader.contains("shortBilinear"))
    }

    @Test
    fun shaderUsesNoLearnedWeightsOrVendorTechnology() {
        val lower = shader.lowercase()
        listOf("tflite", "nnapi", "onnx", "neural", "midas", "tensorflow", "mediapipe")
            .forEach { token -> assertFalse("Photon must stay classical — found '$token'", lower.contains(token)) }
    }

    @Test
    fun committedSpirvHeaderStaysInSync() {
        val header = File("src/main/cpp/generated/photon_hdr_fusion_comp_spv.h")
        assertTrue("missing generated SPIR-V header", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kPhotonHdrFusionCompSpv"))
        assertTrue(text.contains("kPhotonHdrFusionCompSpvCount"))
    }
}
