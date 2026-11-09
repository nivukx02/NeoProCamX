package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * s1-f06 / CP12 — locks the MFSR fusion push-constant ABI: [MfsrFusionParams.toPushConstants]'s slot
 * order and per-slot int-bit/float typing MUST match `mfsr_fusion.comp`'s `layout(push_constant)`
 * block field-for-field, in order, and that block must match the native `MfsrFusionPushConstants`.
 *
 * This is the byte-layout guard the §0.5 "ABI is sacred" standard requires before MFSR is fed real
 * `(noiseS, noiseO)` / per-frame data across JNI. A reordered field, an added/removed slot, or an
 * int<->float reinterpret drift (Kotlin OR GLSL side) breaks this test before it can corrupt the pass.
 * Mirrors [ArgusPushConstantLayoutTest].
 */
class MfsrPushConstantLayoutTest {

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
    fun mfsrPushConstantsMatchGlslLayout() {
        val fields = GpuPushConstantLayout.parseBlock(
            shader("mfsr_fusion.comp"),
            "PushConstants"
        )
        assertEquals(
            listOf("activeFrameCount", "noiseS", "noiseO", "gyroOffsetScale"),
            fields.map { it.name }
        )
        // First field is the only int (bit-reinterpreted slot); the rest are exactly `float`.
        assertEquals("activeFrameCount must be the int slot", "int", fields[0].type)
        listOf("noiseS", "noiseO", "gyroOffsetScale").forEach { name ->
            val f = fields.first { it.name == name }
            assertEquals("$name must be a float slot", "float", f.type)
        }

        val packed = MfsrFusionParams(
            activeFrameCount = 6,
            noiseS = 0.0026f,
            noiseO = 0.00050f,
            gyroOffsetScale = 0.75f
        ).toPushConstants()
        assertPackedMatchesLayout(
            fields,
            packed,
            ints = mapOf("activeFrameCount" to 6),
            floats = mapOf("noiseS" to 0.0026f, "noiseO" to 0.00050f, "gyroOffsetScale" to 0.75f)
        )
    }

    @Test
    fun dormantDefaultPacksTheNativeDispatchValues() {
        // Mirrors the literal mfsrParams the native dispatcher uploads today (activeFrameCount=4,
        // noiseS=0, noiseO=0, gyroOffsetScale=1) so a drift between the two is caught here.
        val packed = MfsrFusionParams.DORMANT_DEFAULT.toPushConstants()
        assertEquals(4, packed.size)
        assertEquals(4, packed[0].toRawBits())   // activeFrameCount int-bits
        assertEquals(0f, packed[1], 0f)           // noiseS
        assertEquals(0f, packed[2], 0f)           // noiseO
        assertEquals(1f, packed[3], 0f)           // gyroOffsetScale
    }

    @Test
    fun activeFrameCountIntBitReinterpretRoundTrips() {
        // The int-bit convention the GLSL relies on (floatBitsToInt) round-trips for the burst depths.
        for (n in intArrayOf(1, 3, 4, 6, 8)) {
            assertEquals(n, Float.fromBits(n).toRawBits())
        }
    }
}
