package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP02 — locks the Argus optical-flow push-constant ABI: the Kotlin packer's slot order and per-slot
 * int-bit/float typing MUST match the GLSL `layout(push_constant)` block field-for-field, in order.
 *
 * This is the byte-layout guard the audit marked mandatory before CP50 wires these blocks into the
 * native dispatch. A reordered field, an added/removed slot, or an int<->float reinterpret drift
 * (Kotlin side OR GLSL side) breaks this test before it can corrupt a live GPU pass.
 */
class ArgusPushConstantLayoutTest {

    private fun shader(name: String): String =
        File("src/main/cpp/shaders/vulkan/$name").readText()

    /** Assert [packed] matches [fields] in order: int-typed fields carry int-bits; float-typed carry floats. */
    private fun assertPackedMatchesLayout(
        fields: List<GpuPushConstantLayout.GlslField>,
        packed: FloatArray,
        ints: Map<String, Int>,
        floats: Map<String, Float>
    ) {
        assertEquals(
            "slot count must equal GLSL field count",
            GpuPushConstantLayout.totalFloatSlots(fields),
            packed.size
        )
        // All fields here are scalars (1 slot), so field index == slot index.
        fields.forEachIndexed { i, f ->
            assertEquals(1, GpuPushConstantLayout.floatSlots(f.type)) // guard: scalars only
            if (GpuPushConstantLayout.isIntType(f.type)) {
                val expected = ints[f.name] ?: error("no expected int for GLSL field '${f.name}'")
                assertEquals(
                    "slot $i (${f.name}) must carry int-bits $expected",
                    expected,
                    packed[i].toRawBits()
                )
            } else {
                val expected = floats[f.name] ?: error("no expected float for GLSL field '${f.name}'")
                assertEquals("slot $i (${f.name}) must carry float $expected", expected, packed[i], 0f)
            }
        }
    }

    @Test
    fun blockMatchPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(
            shader("argus_flow_blockmatch.comp"),
            "ArgusBlockMatchParams"
        )
        assertEquals(
            listOf("searchRadius", "coarseStep", "patchRadius", "maxFlow", "previousValid", "minTexture"),
            fields.map { it.name }
        )
        val packed = ArgusBlockMatchParams(
            searchRadius = 12,
            coarseStep = 3,
            patchRadius = 2,
            maxFlow = 7f,
            previousValid = true,
            minTexture = 0.02f
        ).toPushConstants()
        assertPackedMatchesLayout(
            fields,
            packed,
            ints = mapOf("searchRadius" to 12, "coarseStep" to 3, "patchRadius" to 2),
            floats = mapOf("maxFlow" to 7f, "previousValid" to 1f, "minTexture" to 0.02f)
        )
        // previousValid=false must pack a float 0, not an int-bit pattern.
        val off = ArgusBlockMatchParams(1, 1, 1, 1f, previousValid = false, minTexture = 0f).toPushConstants()
        assertEquals(0f, off[4], 0f)
    }

    @Test
    fun refinePushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(
            shader("argus_flow_refine.comp"),
            "ArgusRefineParams"
        )
        assertEquals(
            listOf("regularization", "maxFlow", "previousValid", "edgeStopSigma"),
            fields.map { it.name }
        )
        val packed = ArgusRefineParams(
            regularization = 0.5f,
            maxFlow = 9f,
            previousValid = true,
            edgeStopSigma = 0.01f
        ).toPushConstants()
        assertPackedMatchesLayout(
            fields,
            packed,
            ints = emptyMap(),
            floats = mapOf("regularization" to 0.5f, "maxFlow" to 9f, "previousValid" to 1f, "edgeStopSigma" to 0.01f)
        )
    }

    @Test
    fun selectPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(
            shader("argus_flow_select.comp"),
            "ArgusSelectParams"
        )
        assertEquals(
            listOf("occlusionThreshold", "minTexture", "maxFlow", "previousValid"),
            fields.map { it.name }
        )
        val packed = ArgusSelectParams(
            occlusionThreshold = 1f,
            minTexture = 0.02f,
            maxFlow = 9f,
            previousValid = true
        ).toPushConstants()
        assertPackedMatchesLayout(
            fields,
            packed,
            ints = emptyMap(),
            floats = mapOf("occlusionThreshold" to 1f, "minTexture" to 0.02f, "maxFlow" to 9f, "previousValid" to 1f)
        )
    }

    @Test
    fun intBitReinterpretRoundTrips() {
        // Sanity: the int-bit convention the GLSL relies on (floatBitsToInt) round-trips for real values.
        for (v in intArrayOf(0, 1, 3, 12, 16, 255, 1023)) {
            assertEquals(v, Float.fromBits(v).toRawBits())
        }
    }

    @Test
    fun parserRejectsMissingStruct() {
        val src = "layout(push_constant) uniform Foo { int a; } u;"
        assertTrue(GpuPushConstantLayout.parseBlock(src, "Foo").isNotEmpty())
        var threw = false
        try {
            GpuPushConstantLayout.parseBlock(src, "DoesNotExist")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("parser must reject a missing struct name", threw)
    }
}
