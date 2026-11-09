package com.neoproc.neoproccamx.rendering

import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoSceneLog10Test {
    @Test
    fun `curve preserves black endpoint gray and extended highlight headroom`() {
        assertEquals(0f, NeoSceneLog10.encode(0f), 0f)
        assertEquals(0f, NeoSceneLog10.decode(0f), 0f)
        assertEquals(1f, NeoSceneLog10.encode(NeoSceneLog10.MAX_LINEAR), 1.0e-6f)
        assertEquals(NeoSceneLog10.MAX_LINEAR, NeoSceneLog10.decode(1f), 0.001f)

        listOf(0.01f, 0.18f, 1f, 4f, 16f, 64f, 128f, 256f).forEach { linear ->
            assertEquals(linear, NeoSceneLog10.decode(NeoSceneLog10.encode(linear)), linear * 2.0e-5f + 1.0e-6f)
        }
    }

    @Test
    fun `ten bit quantization keeps diffuse and highlight relative error below one percent`() {
        var worstRelativeError = 0f
        repeat(10_001) { index ->
            val t = index / 10_000f
            val linear = 0.18f * (NeoSceneLog10.MAX_LINEAR / 0.18f).pow(t)
            val decoded = NeoSceneLog10.decodeCode(NeoSceneLog10.quantize(linear))
            worstRelativeError = maxOf(worstRelativeError, kotlin.math.abs(decoded - linear) / linear)
        }
        assertTrue("worst relative error=$worstRelativeError", worstRelativeError < 0.006f)
        assertTrue(kotlin.math.abs(NeoSceneLog10.decodeCode(NeoSceneLog10.quantize(0.01f)) - 0.01f) < 0.00025f)
    }

    @Test
    fun `vulkan pack and egl inverse use the same project owned constants`() {
        val pack = File("src/main/cpp/shaders/vulkan/raw_scene_log_pack.comp").readText()
        val encoder = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val bridge = File("src/main/cpp/vulkan_bridge.cpp").readText()

        listOf("32.0", "256.0", "13.000176099").forEach { constant ->
            assertTrue("pack must contain $constant", pack.contains(constant))
        }
        assertTrue(encoder.contains("NEO_SCENE_LOG_DENOMINATOR = 13.000176099"))
        assertTrue(encoder.contains("exp2(clamp(encoded, 0.0, 1.0)"))
        assertTrue(bridge.contains("recordRawSceneLogTransport"))
        assertTrue(bridge.contains("VK_FORMAT_A2B10G10R10_UNORM_PACK32"))
        assertFalse(pack.contains("HLG", ignoreCase = true))
        assertFalse(pack.contains("Dolby", ignoreCase = true))
        assertFalse(pack.contains("ST.2084", ignoreCase = true))
    }
}
