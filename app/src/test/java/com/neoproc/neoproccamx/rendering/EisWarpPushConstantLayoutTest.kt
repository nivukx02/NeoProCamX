package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.stabilization.EisWarpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s3-f06 / CP60 — locks the gyro-EIS warp push-constant ABI ("ABI is sacred"):
 * [EisWarpParams.toPushConstants]'s slot order + per-slot int-bit/float typing MUST match eis_warp.comp's
 * `layout(push_constant)` block field-for-field, and that block must match the native `EisWarpPushConstants`
 * C++ struct (16 bytes). A reorder, an added/removed slot, or an int↔float reinterpret drift on EITHER side
 * breaks this test before it can corrupt the GPU dispatch. Mirrors [DualGainFusionPushConstantLayoutTest].
 */
class EisWarpPushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/eis_warp.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun eisWarpPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "EisWarpParams")
        assertEquals(
            listOf("cropMarginPixels", "centerX", "centerY", "gridRows"),
            fields.map { it.name }
        )
        // gridRows is the int (int-bit-reinterpreted) slot; the rest are exactly float.
        assertTrue("gridRows must be an int slot", GpuPushConstantLayout.isIntType(fields[3].type))
        listOf("cropMarginPixels", "centerX", "centerY").forEach { name ->
            assertEquals("$name must be a float slot", "float", fields.first { it.name == name }.type)
        }

        val packed = EisWarpParams(cropMarginPixels = 24f, centerX = 960f, centerY = 540f, gridRows = 1080).toPushConstants()
        assertEquals("slot count must equal GLSL field count",
            GpuPushConstantLayout.totalFloatSlots(fields), packed.size)
        val expectedInts = mapOf("gridRows" to 1080)
        val expectedFloats = mapOf("cropMarginPixels" to 24f, "centerX" to 960f, "centerY" to 540f)
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
        assertTrue("native struct must exist", bridge.contains("struct EisWarpPushConstants"))
        val start = bridge.indexOf("struct EisWarpPushConstants")
        val end = bridge.indexOf("};", start)
        assertTrue(end > start)
        val body = bridge.substring(start, end)
        val order = listOf("cropMarginPixels", "centerX", "centerY", "gridRows")
        var cursor = -1
        order.forEach { field ->
            val at = body.indexOf(field)
            assertTrue("native struct must declare '$field' in order", at > cursor)
            cursor = at
        }
        assertTrue("native struct must be size-locked to 16 bytes",
            bridge.contains("static_assert(sizeof(EisWarpPushConstants) == 16"))
    }
}
