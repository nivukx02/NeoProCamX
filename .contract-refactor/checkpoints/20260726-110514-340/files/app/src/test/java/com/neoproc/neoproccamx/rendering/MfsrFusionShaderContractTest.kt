package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s1-f06 / CP12 / CP13 — locks `mfsr_fusion.comp` as the classical, zero-ML multi-frame super-resolution
 * fusion kernel (anisotropic steering-kernel regression + heteroscedastic 3σ ghost veto, the Wronski et
 * al. 2019 bar), pins its binding/local-size/push-constant contract, and asserts its committed SPIR-V
 * header stays present. Mirrors [ArgusFlowShaderContractTest]; text-token asserts only (no Vulkan).
 */
class MfsrFusionShaderContractTest {

    private val shader: String =
        File("src/main/cpp/shaders/vulkan/mfsr_fusion.comp").readText()

    @Test
    fun shaderIsClassicalSteeringKernelMfsrWithHeteroscedasticVeto() {
        // Workgroup + bindings. Note: MFSR declares local_size 16x16 with NO local_size_z.
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16) in;"))
        assertTrue("binding 0 must be the RAW burst array", shader.contains("sampler2DArray inputRawFrames"))
        assertTrue("binding 1 must be the optical-flow field", shader.contains("sampler2D opticalFlowOffsets"))
        assertTrue("binding 2 must be the linear-RGB output", shader.contains("uniform writeonly image2D outputLinearRGB"))
        assertTrue("output must be rgba16f (linear scene domain)", shader.contains("rgba16f"))

        // Push-constant contract — block name + field order mirrored by MfsrFusionParams / the C++ ABI.
        assertTrue(shader.contains("PushConstants"))
        listOf("activeFrameCount", "noiseS", "noiseO", "gyroOffsetScale")
            .forEach { field -> assertTrue("missing push-constant field $field", shader.contains(field)) }

        // Steering-kernel regression: structure tensor, eigen-decomposition, anisotropic Mahalanobis metric.
        assertTrue(shader.contains("computeSteeringKernel"))
        assertTrue(shader.contains("Structure Tensor"))
        assertTrue(shader.contains("Eigenvalue decomposition"))
        assertTrue(shader.contains("Mahalanobis"))

        // Heteroscedastic noise model + 3σ ghost veto (signal-dependent variance, not a constant).
        assertTrue(shader.contains("noiseVariance"))
        assertTrue(shader.contains("pcs.noiseS * refRaw + pcs.noiseO"))
        assertTrue(shader.contains("sigmaThresh"))
        assertTrue(shader.contains("GHOST VETO"))
    }

    @Test
    fun shaderUsesNoLearnedWeightsOrVendorTechnology() {
        val lower = shader.lowercase()
        listOf("tflite", "nnapi", "onnx", "neural", "midas", "mediapipe", "tensorflow", ".model")
            .forEach { token ->
                assertFalse("MFSR must stay classical — found '$token'", lower.contains(token))
            }
    }

    @Test
    fun committedSpirvHeaderStaysInSync() {
        val header = File("src/main/cpp/generated/mfsr_fusion_comp_spv.h")
        assertTrue("missing generated SPIR-V header", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kMfsrFusionCompSpv"))
        assertTrue(text.contains("kMfsrFusionCompSpvCount"))
        // The MFSR header predates compile_shader_header.py's constexpr format; pin the actual declaration.
        assertTrue(text.contains("static const uint32_t kMfsrFusionCompSpv[]"))
    }

    @Test
    fun nativeBridgeBindsAndDispatchesTheShader() {
        val bridge = File("src/main/cpp/vulkan_bridge.cpp").readText()
        assertTrue(bridge.contains("#include \"generated/mfsr_fusion_comp_spv.h\""))
        assertTrue(bridge.contains("kMfsrFusionCompSpv"))
        // The deterministic UB-kill (CP12 STEP A) must remain: the dummy bindings are cleared at creation.
        assertTrue(bridge.contains("CP12 STEP A"))
        assertTrue(bridge.contains("vkCmdClearColorImage"))
        // The MFSR real-input control path (STEP B/C/D) must remain wired + default-on, with the visible
        // fusion gated by the burst-ring switch so the default push constants stay byte-identical.
        assertTrue(bridge.contains("mfsrRealInputsEnabled = true"))
        assertTrue(bridge.contains("mfsrBurstRingReady = false"))
        assertTrue(bridge.contains("state->mfsrRealInputsEnabled && state->mfsrBurstRingReady"))
        assertTrue(bridge.contains("nativeSetMfsrFusionParams"))
    }

    @Test
    fun bridgeKotlinExposesMfsrRealInputControlPath() {
        val kt = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
        listOf(
            "nativeSetMfsrRealInputsEnabled",
            "nativeSetMfsrBurstRingReady",
            "nativeSetMfsrFusionParams",
            "fun setMfsrFusionParams("
        ).forEach { token -> assertTrue("VulkanBridge must expose $token", kt.contains(token)) }
    }
}
