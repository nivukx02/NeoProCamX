package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the OmniClarity §2 Argus Stage-A block-matching shader as a classical, zero-ML optical-flow
 * producer whose output convention matches warp_reconstruct.comp, and that its committed SPIR-V
 * header stays in sync.
 */
class ArgusFlowShaderContractTest {

    private val shader: String =
        File("src/main/cpp/shaders/vulkan/argus_flow_blockmatch.comp").readText()

    @Test
    fun shaderIsClassicalBlockMatchingWithWarpCompatibleConvention() {
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(shader.contains("uCurrentFrame"))
        assertTrue(shader.contains("uPreviousFrame"))
        assertTrue(shader.contains("uMotionField"))
        // Output is the rg16f motion field warp_reconstruct.comp consumes.
        assertTrue(shader.contains("rg16f"))
        // Forward-flow convention: previous(p - f) ~= current(p) (matches warp's sourceCoord = coord - flow).
        assertTrue(shader.contains("previous(p - f(p))"))

        // Push-constant contract.
        assertTrue(shader.contains("ArgusBlockMatchParams"))
        listOf("searchRadius", "coarseStep", "patchRadius", "maxFlow", "previousValid", "minTexture")
            .forEach { field -> assertTrue("missing push-constant field $field", shader.contains(field)) }

        // Block-matching core: SAD patch, coarse+fine search, distinctiveness, parabola sub-pixel.
        assertTrue(shader.contains("patchSad"))
        assertTrue(shader.contains("secondBest"))
        assertTrue(shader.contains("denomX"))
        assertTrue(shader.contains("denomY"))
        // Confidence-gated hybrid selection: low texture / low distinctiveness -> static flow.
        assertTrue(shader.contains("textureGate"))
        assertTrue(shader.contains("reliability"))
        // No prior frame -> zero flow.
        assertTrue(shader.contains("uParams.previousValid < 0.5"))
    }

    @Test
    fun shaderUsesNoLearnedWeightsOrVendorTechnology() {
        val lower = shader.lowercase()
        listOf("tflite", "nnapi", "onnx", "neural", "midas", "mediapipe", "tensorflow", ".model")
            .forEach { token ->
                assertFalse("Argus must stay classical — found '$token'", lower.contains(token))
            }
    }

    @Test
    fun committedSpirvHeaderStaysInSync() {
        val header = File("src/main/cpp/generated/argus_flow_blockmatch_comp_spv.h")
        assertTrue("missing generated SPIR-V header", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kArgusFlowBlockmatchCompSpv"))
        assertTrue(text.contains("kArgusFlowBlockmatchCompSpvCount"))
        assertTrue(text.contains("static constexpr uint32_t kArgusFlowBlockmatchCompSpv[]"))
    }

    @Test
    fun refineShaderIsVariationalHornSchunckWithEdgeAwareSmoothness() {
        val src = File("src/main/cpp/shaders/vulkan/argus_flow_refine.comp").readText()
        assertTrue(src.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(src.contains("uFlowIn"))
        assertTrue(src.contains("uFlowOut"))
        assertTrue(src.contains("ArgusRefineParams"))
        listOf("regularization", "maxFlow", "previousValid", "edgeStopSigma")
            .forEach { field -> assertTrue("missing $field", src.contains(field)) }
        assertTrue(src.contains("previousLumaBilinear")) // warp-based data term
        assertTrue(src.contains("residual"))
        assertTrue(src.contains("fbar"))                 // smoothness neighbour average
        assertTrue(src.contains("edge"))                 // edge-aware (anisotropic) smoothing
        val lower = src.lowercase()
        listOf("tflite", "nnapi", "onnx", "neural", "midas", "tensorflow")
            .forEach { token -> assertFalse(lower.contains(token)) }
        val header = File("src/main/cpp/generated/argus_flow_refine_comp_spv.h")
        assertTrue("missing refine SPIR-V header", header.exists())
        assertTrue(header.readText().contains("kArgusFlowRefineCompSpv"))
    }

    @Test
    fun selectShaderDoesForwardBackwardOcclusionAndHybridSelection() {
        val src = File("src/main/cpp/shaders/vulkan/argus_flow_select.comp").readText()
        assertTrue(src.contains("uFlowForward"))
        assertTrue(src.contains("uFlowBackward"))
        assertTrue(src.contains("uFlowCoarse"))
        assertTrue(src.contains("ArgusSelectParams"))
        assertTrue(src.contains("occlusionThreshold"))
        assertTrue(src.contains("consistencyResidual"))          // fwd/bwd consistency check
        assertTrue(src.contains("mix(coarseFlow, forwardFlow"))  // hybrid selection
        assertTrue(src.contains("textureGate"))
        assertFalse(src.lowercase().contains("tflite"))
        assertFalse(src.lowercase().contains("neural"))
        val header = File("src/main/cpp/generated/argus_flow_select_comp_spv.h")
        assertTrue("missing select SPIR-V header", header.exists())
        assertTrue(header.readText().contains("kArgusFlowSelectCompSpv"))
    }
}
