package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s4-f03 / CP30 — locks the knee-curve push-constant ABI ("ABI is sacred"). The GLSL
 * `KneeCurveParams` block in knee_curve.comp must match the native `KneeCurvePushConstants` C++
 * struct field-for-field and the struct is size-locked to 20 bytes. A reorder, an added/removed
 * field, or an int↔float drift on either side breaks this test before it can corrupt the GPU push.
 * Mirrors [DualGainFusionPushConstantLayoutTest]. (CP30: grown +peakNitsGate, 16→20 bytes, when the
 * BT.2390 EETF gate moved from a bake-time spec constant to a per-dispatch push constant.)
 */
class KneeCurvePushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/knee_curve.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    private val order = listOf("maxCLL", "kneePoint", "kneeHardness", "targetPeakNits", "peakNitsGate")

    @Test
    fun kneePushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "KneeCurveParams")
        assertEquals(order, fields.map { it.name })
        fields.forEach { assertEquals("${it.name} must be a float slot", "float", it.type) }
        // Five tightly-packed float slots = 20 bytes (std140 push-constant scalar packing).
        assertEquals(5, GpuPushConstantLayout.totalFloatSlots(fields))
    }

    @Test
    fun nativeStructLocksTheSameLayout() {
        assertTrue("native struct must exist", bridge.contains("struct KneeCurvePushConstants"))
        val start = bridge.indexOf("struct KneeCurvePushConstants")
        val end = bridge.indexOf("};", start)
        assertTrue(end > start)
        val body = bridge.substring(start, end)
        var cursor = -1
        order.forEach { field ->
            val at = body.indexOf(field)
            assertTrue("native struct must declare '$field' in order", at > cursor)
            cursor = at
        }
        assertTrue("native struct must be size-locked to 20 bytes",
            bridge.contains("static_assert(sizeof(KneeCurvePushConstants) == 20"))
    }
}
