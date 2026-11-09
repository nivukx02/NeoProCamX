package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP31 / s1-f10 — locks the dual-gain fusion push-constant ABI ("ABI is sacred"):
 * [DualGainFusionParams.toPushConstants]'s slot order + per-slot int-bit/float typing MUST match
 * dual_gain_fusion.comp's `layout(push_constant)` block field-for-field, and that block must match the
 * native `DualGainFusionPushConstants` C++ struct (24 bytes). A reorder, an added/removed slot, or an
 * int↔float reinterpret drift on EITHER side breaks this test before it can corrupt the GPU dispatch.
 * Mirrors [MfsrPushConstantLayoutTest].
 */
class DualGainFusionPushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/dual_gain_fusion.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun dualGainPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "DualGainFusionParams")
        assertEquals(
            listOf("width", "height", "kGain", "noiseFloor", "saturationThreshold", "shadowStretch"),
            fields.map { it.name }
        )
        // width/height are the uint (int-bit-reinterpreted) slots; the rest are exactly float.
        assertEquals("uint", fields[0].type)
        assertEquals("uint", fields[1].type)
        listOf("kGain", "noiseFloor", "saturationThreshold", "shadowStretch").forEach { name ->
            assertEquals("$name must be a float slot", "float", fields.first { it.name == name }.type)
        }

        val packed = DualGainFusionParams(
            width = 1920,
            height = 1080,
            kGain = 8f,
            noiseFloor = 0.012f,
            saturationThreshold = 0.90f,
            shadowStretch = 0.10f
        ).toPushConstants()

        assertEquals("slot count must equal GLSL field count",
            GpuPushConstantLayout.totalFloatSlots(fields), packed.size)
        val expectedInts = mapOf("width" to 1920, "height" to 1080)
        val expectedFloats = mapOf("kGain" to 8f, "noiseFloor" to 0.012f, "saturationThreshold" to 0.90f, "shadowStretch" to 0.10f)
        fields.forEachIndexed { i, f ->
            assertEquals(1, GpuPushConstantLayout.floatSlots(f.type)) // scalars only
            if (GpuPushConstantLayout.isIntType(f.type)) {
                assertEquals("slot $i (${f.name}) must carry int-bits", expectedInts[f.name], packed[i].toRawBits())
            } else {
                assertEquals("slot $i (${f.name}) must carry float", expectedFloats[f.name]!!, packed[i], 0f)
            }
        }
    }

    @Test
    fun nativeStructLocksTheSameLayout() {
        // The C++ DualGainFusionPushConstants mirrors the GLSL block in declaration order and is size-locked.
        assertTrue("native struct must exist", bridge.contains("struct DualGainFusionPushConstants"))
        val start = bridge.indexOf("struct DualGainFusionPushConstants")
        val end = bridge.indexOf("};", start)
        assertTrue(end > start)
        val body = bridge.substring(start, end)
        val order = listOf("width", "height", "kGain", "noiseFloor", "saturationThreshold", "shadowStretch")
        var cursor = -1
        order.forEach { field ->
            val at = body.indexOf(field)
            assertTrue("native struct must declare '$field' in order", at > cursor)
            cursor = at
        }
        assertTrue("native struct must be size-locked to 24 bytes",
            bridge.contains("static_assert(sizeof(DualGainFusionPushConstants) == 24"))
    }
}
