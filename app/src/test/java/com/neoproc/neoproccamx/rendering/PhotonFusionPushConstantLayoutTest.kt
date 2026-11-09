package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP32 / s1-f10 — locks the photon HDR fusion push-constant ABI ("ABI is sacred") for the dormant
 * foundation: [PhotonFusionParams.toPushConstants]'s slot order MUST match photon_hdr_fusion.comp's
 * `layout(push_constant)` block (6 floats), and that block must match the native PhotonFusionPushConstants
 * (24 bytes). Even though the pipeline never dispatches yet, pinning the layout now means the day it is
 * wired on-device the bytes are already correct. Mirrors [DualGainFusionPushConstantLayoutTest].
 */
class PhotonFusionPushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/photon_hdr_fusion.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun photonPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "PhotonFusionParams")
        assertEquals(
            listOf("highlightThreshold", "clipThreshold", "longScale", "shortScale", "motionCompEnabled", "maxFlow"),
            fields.map { it.name }
        )
        fields.forEach { assertEquals("${it.name} must be a float slot", "float", it.type) }

        val packed = PhotonFusionParams(
            highlightThreshold = 0.85f,
            clipThreshold = 0.98f,
            longScale = 1.0f,
            shortScale = 8.0f,
            motionCompEnabled = true,
            maxFlow = 6.0f
        ).toPushConstants()

        assertEquals("slot count must equal GLSL field count",
            GpuPushConstantLayout.totalFloatSlots(fields), packed.size)
        val expected = listOf(0.85f, 0.98f, 1.0f, 8.0f, 1.0f, 6.0f) // motionCompEnabled true → 1.0f
        fields.forEachIndexed { i, f ->
            assertEquals(1, GpuPushConstantLayout.floatSlots(f.type))
            assertEquals("slot $i (${f.name})", expected[i], packed[i], 0f)
        }
    }

    @Test
    fun photonFoundationIsIncludedAndDormant() {
        // CP32: the SPIR-V header is in the native build, the pipeline is created, but NO dispatch exists.
        assertTrue("photon SPV header must be #included",
            bridge.contains("#include \"generated/photon_hdr_fusion_comp_spv.h\""))
        assertTrue("photon pipeline must be created (foundation)",
            bridge.contains("\"photon_hdr_fusion\""))
        assertTrue("native PhotonFusionPushConstants must be size-locked to 24 bytes",
            bridge.contains("static_assert(sizeof(PhotonFusionPushConstants) == 24"))
        // Dormant: the photon pipeline is never bound for a dispatch (foundation only).
        assertTrue("photon pipeline must NOT be dispatched yet (dormant foundation)",
            !bridge.contains("VK_PIPELINE_BIND_POINT_COMPUTE, state->photonPipeline"))
    }
}
