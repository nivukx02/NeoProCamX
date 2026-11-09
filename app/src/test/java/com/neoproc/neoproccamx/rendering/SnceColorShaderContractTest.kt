package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the SNCE v2.0 HVC GPU colour shaders as classical, zero-learned-weight passes whose
 * committed SPIR-V headers stay in sync with their GLSL sources:
 *  - occs_opponent.comp  : Stage 04 opponent-channel lateral inhibition (DoG).
 *  - nrli_lut_apply.comp : Stage 11 trilinear 3D-LUT apply.
 * Their CPU references live in SnceOpponentChannels / SnceNeuralEnhancement (Kotlin).
 */
class SnceColorShaderContractTest {

    private fun shader(name: String): String =
        File("src/main/cpp/shaders/vulkan/$name").readText()

    private fun assertNoLearnedRuntime(src: String) {
        val lower = src.lowercase()
        listOf("tflite", "nnapi", "onnx", "tensorflow", "midas", ".model")
            .forEach { token -> assertFalse("SNCE shader must stay classical — found '$token'", lower.contains(token)) }
    }

    @Test
    fun opponentShaderIsDifferenceOfGaussiansLateralInhibition() {
        val src = shader("occs_opponent.comp")
        assertTrue(src.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(src.contains("uIptInput"))
        assertTrue(src.contains("uIptOutput"))
        assertTrue(src.contains("OccsParams"))
        listOf("sigmaCenter", "sigmaSurround", "kWeight", "iGain", "chromaGain")
            .forEach { field -> assertTrue("missing push-constant field $field", src.contains(field)) }
        // Difference-of-Gaussians: center minus weighted surround.
        assertTrue(src.contains("center - uOccs.kWeight * surround"))
        // Mild achromatic boost, strong chromatic (s2-f07: chroma gain derives from uOccs.chromaGain,
        // optionally faded by the gated CP46 mesopic spec constant before being applied to dog.g/.b).
        assertTrue(src.contains("uOccs.iGain * dog.r"))
        assertTrue(src.contains("float chromaGain = uOccs.chromaGain"))
        assertTrue(src.contains("chromaGain * dog.g"))
        assertTrue("CP46 mesopic awareness must be gated", src.contains("MESOPIC_AWARE"))
        assertNoLearnedRuntime(src)

        val header = File("src/main/cpp/generated/occs_opponent_comp_spv.h")
        assertTrue("missing generated SPIR-V header", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kOccsOpponentCompSpv"))
        assertTrue(text.contains("kOccsOpponentCompSpvCount"))
        assertTrue(text.contains("static constexpr uint32_t kOccsOpponentCompSpv[]"))
    }

    @Test
    fun nrliShaderSupportsTetrahedralWithTrilinearFallback() {
        val src = shader("nrli_lut_apply.comp")
        assertTrue(src.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(src.contains("sampler3D uNrliLut"))
        assertTrue(src.contains("NrliParams"))
        assertTrue(src.contains("lutSize"))
        assertTrue(src.contains("strength"))
        // Trilinear lookup with half-texel inset, then strength blend.
        assertTrue(src.contains("texture(uNrliLut, coord)"))
        assertTrue(src.contains("layout(constant_id = 0) const bool USE_TETRAHEDRAL"))
        assertTrue(src.contains("vec3 tetrahedralSample"))
        assertTrue(src.contains("if (USE_TETRAHEDRAL)"))
        assertTrue(src.contains("scale"))
        assertTrue(src.contains("offset"))
        assertTrue(src.contains("mix(color.rgb, graded, clamp(uNrli.strength"))
        assertNoLearnedRuntime(src)

        val header = File("src/main/cpp/generated/nrli_lut_apply_comp_spv.h")
        assertTrue("missing generated SPIR-V header", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kNrliLutApplyCompSpv"))
        assertTrue(text.contains("kNrliLutApplyCompSpvCount"))
        assertTrue(text.contains("static constexpr uint32_t kNrliLutApplyCompSpv[]"))

        val bridge = File("src/main/cpp/vulkan_bridge.cpp").readText()
        assertTrue(bridge.contains("tetraInfo.mapEntryCount = 1u"))
        assertTrue(bridge.contains("st.pSpecializationInfo = &tetraInfo"))
        assertTrue(bridge.contains("nrli-trilinear-fallback"))
    }
}
