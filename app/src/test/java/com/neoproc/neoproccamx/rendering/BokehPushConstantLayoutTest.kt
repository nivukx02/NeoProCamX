package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s5-f06 / CP-F7 — locks the bokeh push-constant ABI ("ABI is sacred"):
 * [BokehFusionParams.toPushConstants]'s slot order + per-slot int-bit/float typing MUST match
 * bokeh_vogel_spiral.comp's `BokehUniforms` `layout(push_constant)` block field-for-field, and that
 * block must match the native `BokehPushConstants` C++ struct (36 bytes = 9 scalars). A reorder, an
 * added/removed slot, or an int<->float reinterpret drift on EITHER side breaks this test before it can
 * corrupt the GPU dispatch. Mirrors [DualGainFusionPushConstantLayoutTest].
 *
 * CRLF-safe: parseBlock is regex-based; the only contains() asserts are on newline-free substrings.
 */
class BokehPushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/bokeh_vogel_spiral.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    private val order = listOf(
        "width", "height", "sampleCount",
        "chromaticAberration", "apertureBladeCount", "apertureBladeStrength", "foregroundGuardStrength",
        "focusDepth", "blendStrength"
    )

    @Test
    fun bokehPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "BokehUniforms")
        assertEquals(order, fields.map { it.name })
        // width/height/sampleCount are the int (int-bit-reinterpreted) slots; the rest are exactly float.
        listOf("width", "height", "sampleCount").forEach { name ->
            assertEquals("$name must be an int slot", "int", fields.first { it.name == name }.type)
        }
        listOf("chromaticAberration", "apertureBladeCount", "apertureBladeStrength", "foregroundGuardStrength", "focusDepth", "blendStrength").forEach { name ->
            assertEquals("$name must be a float slot", "float", fields.first { it.name == name }.type)
        }

        val packed = BokehFusionParams(
            width = 1920,
            height = 1080,
            sampleCount = 32,
            chromaticAberration = 0.5f,
            apertureBladeCount = 7f,
            apertureBladeStrength = 0.25f,
            foregroundGuardStrength = 8f,
            focusDepth = 0.42f,
            blendStrength = 0.9f
        ).toPushConstants()

        assertEquals("slot count must equal GLSL field count",
            GpuPushConstantLayout.totalFloatSlots(fields), packed.size)
        assertEquals(9, packed.size)
        val expectedInts = mapOf("width" to 1920, "height" to 1080, "sampleCount" to 32)
        val expectedFloats = mapOf(
            "chromaticAberration" to 0.5f, "apertureBladeCount" to 7f, "apertureBladeStrength" to 0.25f,
            "foregroundGuardStrength" to 8f, "focusDepth" to 0.42f, "blendStrength" to 0.9f
        )
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
    fun passthroughDefaultPacksZeroBlendStrength() {
        // The value the native dispatch uploads while the gate is OFF: blendStrength 0 == exact passthrough.
        val packed = BokehFusionParams.passthroughDefault(width = 1280, height = 720).toPushConstants()
        assertEquals(9, packed.size)
        assertEquals(1280, packed[0].toRawBits())
        assertEquals(720, packed[1].toRawBits())
        assertEquals(16, packed[2].toRawBits()) // default sampleCount
        assertEquals("blendStrength default must be 0 (passthrough)", 0f, packed[8], 0f)
    }

    @Test
    fun nativeStructLocksTheSameLayout() {
        // The C++ BokehPushConstants mirrors the GLSL block in declaration order and is size-locked.
        assertTrue("native struct must exist", bridge.contains("struct BokehPushConstants"))
        val start = bridge.indexOf("struct BokehPushConstants")
        val end = bridge.indexOf("};", start)
        assertTrue(end > start)
        val body = bridge.substring(start, end)
        var cursor = -1
        order.forEach { field ->
            val at = body.indexOf(field)
            assertTrue("native struct must declare '$field' in order", at > cursor)
            cursor = at
        }
        assertTrue("native struct must be size-locked to 36 bytes",
            bridge.contains("static_assert(sizeof(BokehPushConstants) == 36"))
    }
}
