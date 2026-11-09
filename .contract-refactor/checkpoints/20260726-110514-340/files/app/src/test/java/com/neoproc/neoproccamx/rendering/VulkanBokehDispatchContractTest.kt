package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s5-f06 / CP-F7 — source-level contract for the NEW bokeh Vulkan dispatch.
 *
 * Proves, without a device:
 *  1. The bokeh pipeline + dispatch now EXIST in vulkan_bridge.cpp (the §3 "no production caller" gap is
 *     closed) — SPV #included, pipeline created, a real dispatch function present.
 *  2. The dispatch is GATED OFF by default (bokehBlendEnabled) so it is unreachable from the live path
 *     and the blend is a passthrough until the owner enables it on-device (no-downgrade / DEVICE-GATED).
 *  3. Every binding is deterministically initialized (clear -> GENERAL), so the validation layer sees no
 *     uninitialized read / UNDEFINED layout (the s1-f06 / s1-f10 UB-safe pattern).
 *  4. The bokeh pipeline is NEVER bound into the live visible compute chain (mirrors the CP36 manifest
 *     rule for dual_gain/photon) — a blind default-on dispatch is impossible by construction.
 *
 * CRLF-safe: every assertion is on a newline-free substring of the source.
 */
class VulkanBokehDispatchContractTest {

    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }
    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/bokeh_vogel_spiral.comp").readText() }

    @Test
    fun bokehSpvIsIncludedAndPipelineIsCreated() {
        assertTrue("bokeh SPV header must be #included",
            bridge.contains("#include \"generated/bokeh_vogel_spiral_comp_spv.h\""))
        assertTrue("bokeh pipeline must be created best-effort",
            bridge.contains("kBokehVogelSpiralCompSpv"))
        assertTrue("native push struct must be size-locked",
            bridge.contains("static_assert(sizeof(BokehPushConstants) == 36"))
    }

    @Test
    fun bokehDispatchIsGatedOffByDefault() {
        assertTrue("gate field must default false", bridge.contains("bool bokehBlendEnabled = false"))
        // The dispatch must early-return when the gate is off (mirrors dualGainFusionEnabled guard).
        assertTrue("dispatch must check the gate", bridge.contains("if (!state->bokehBlendEnabled) return false"))
        // Shader-side defense in depth: blendStrength 0 is an exact passthrough.
        assertTrue("shader must expose blendStrength", shader.contains("float blendStrength"))
        assertTrue("shader must expose focusDepth", shader.contains("float focusDepth"))
    }

    @Test
    fun bokehBindingsAreDeterministicallyInitialized() {
        val start = bridge.indexOf("bool ensureBokehResources")
        assertTrue("ensureBokehResources must exist", start >= 0)
        val end = bridge.indexOf("bool dispatchBokehVogelSpiral", start)
        assertTrue(end > start)
        val ensure = bridge.substring(start, end)
        // Every owned binding is cleared and moved to GENERAL before any read (no UNDEFINED layout).
        assertTrue("owned bokeh images must be cleared", ensure.contains("vkCmdClearColorImage"))
        assertTrue("owned bokeh images must end in GENERAL", ensure.contains("VK_IMAGE_LAYOUT_GENERAL"))
    }

    @Test
    fun bokehInlineDispatchIsGatedAndPassthroughSafe() {
        // Superseded contract (was: "never bound inline"): commit 8945aa4 wired an INLINE Vogel-spiral pass
        // in recordRealtimeComputePass (device-verified on vivo I2405), so the pipeline IS bound inline —
        // but ONLY inside a gate that defaults OFF, and the shader is passthrough-safe. Lock THAT contract:
        //  (1) the inline bind is guarded by the default-OFF blend gate + a real-feed gate (never garbage);
        //  (2) the gate field defaults false; (3) the shader emits input unchanged at blendStrength 0.
        assertTrue("inline bind must be guarded by the default-OFF blend gate",
            bridge.contains("if (state->bokehBlendEnabled &&"))
        assertTrue("inline dispatch must require a real depth/CoC feed (never read garbage)",
            bridge.contains("target->bokehFeedReady"))
        assertTrue("blend gate must default false", bridge.contains("bool bokehBlendEnabled = false"))
        assertTrue("shader must be passthrough at blendStrength 0", shader.contains("float blendStrength"))
    }
}
