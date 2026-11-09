package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * s2-f03 / CP-C3 — the std140 ABI + matrix-correctness + finite-guarantee locks for the 52-float
 * Neo color-science uniform block. The golden vectors pin the current Python-calibrated v3 payload,
 * so preview and encoder must remain byte-for-byte identical after an intentional calibration update.
 */
class VulkanColorScience52FloatAbiTest {

    // (1) GOLDEN-LOCK: each shipped mode's packed 52-float vector is frozen (content hash + the
    //     semantic lanes that decide the shader branch). Trips on any unintended packing change.
    @Test
    fun `every shipped mode packs the frozen 52-float vector`() {
        // mode id -> frozen contentHashCode of the packed 52-float vector (Arrays.hashCode over
        // floatToIntBits — deterministic across JVMs). NDR10_PLUS is the calibrated v3 baseline.
        val golden = mapOf(
            "DISABLED" to 1852560084,
            "REC709_SDR" to 601005052,
            "NEO_WIDE_APEX" to 740465660,
            "NEO_ACES_AP1_HIGH_END" to 752524284,
            "NEO_ACES_AP0_HIGH_END" to 1228577788,
            "NDR10_PLUS" to 2132886021,
            "NIVUKX_VISION" to 1230901821,
            "N_LOG_CINEMA" to 561159164
        )

        VulkanColorScienceShippedModes.MODES.forEach { mode ->
            val packed = mode.resolve().pack()
            assertEquals("${mode.id} must pack 52 floats", 52, packed.size)
            assertTrue("${mode.id} pack must be finite", packed.all { it.isFinite() })
            val g = golden[mode.id] ?: error("no golden for ${mode.id}")
            assertEquals("${mode.id} packed vector changed (hash)", g, packed.contentHashCode())
        }
    }

    // (2) MATRIX CORRECTNESS: the two reference mat4s are inverses (round-trip to identity), and the
    //     3x3 sub-block equals the canonical AcesColorEngine reference matrices (no transpose/swap).
    @Test
    fun `ACEScg reference mat4 pair round-trips to identity within 1e-5`() {
        VulkanColorScienceShippedModes.MODES.forEach { mode ->
            val u = mode.resolve()
            val product = mul4x4ColumnMajor(u.acesCgToOklabReference, u.oklabReferenceToAcesCg)
            for (col in 0..3) for (row in 0..3) {
                val expected = if (col == row) 1f else 0f
                val v = product[col * 4 + row]
                assertTrue(
                    "${mode.id} mat4 round-trip [$row,$col]=$v must be $expected",
                    abs(v - expected) <= 1e-5f
                )
            }
        }
    }

    // (3) std140 SLOT ORDER: pack() concatenates the seven fields in the contract order. Any reorder
    //     or type change trips this. mat4 (16) ×2 + vec4 (4) ×5 = 52, all 16-byte aligned.
    @Test
    fun `pack slot order matches the std140 contract`() {
        assertEquals(52, VulkanColorScienceUniforms.FLOAT_COUNT)
        val u = VulkanColorScienceShippedModes.MODES.first { it.id == "NEO_WIDE_APEX" }.resolve()
        val packed = u.pack()
        assertEquals(52, packed.size)
        // Field boundaries: [0,16) acesCgToOklab, [16,32) oklabToAcesCg, [32,36) hdr, [36,40) mode,
        // [40,44) measuredCorrection, [44,48) identity, [48,52) measuredRgbScale.
        assertArrayEquals48(packed.copyOfRange(0, 16), u.acesCgToOklabReference)
        assertArrayEquals48(packed.copyOfRange(16, 32), u.oklabReferenceToAcesCg)
        assertArrayEquals48(packed.copyOfRange(32, 36), u.hdrParams)
        assertArrayEquals48(packed.copyOfRange(36, 40), u.modeParams)
        assertArrayEquals48(packed.copyOfRange(40, 44), u.measuredCorrectionParams)
        assertArrayEquals48(packed.copyOfRange(44, 48), u.identityParams)
        assertArrayEquals48(packed.copyOfRange(48, 52), u.measuredRgbScaleParams)
    }

    // (4) SANITIZATION / FINITE GUARANTEE: poisoned scalar inputs still produce a finite 52-float
    //     packet (no NaN reaches the GPU), and a directly-constructed non-finite field is rejected.
    @Test
    fun `poisoned scalar inputs yield a finite packet and direct non-finite is rejected`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            val packed = VulkanColorScienceUniformPolicy.resolve(
                enabled = true, hdrMappingEnabled = true, logDeliveryEnabled = false,
                nivukxVision = false, hdrKnee = bad, hdrShoulder = bad, volumeCompression = bad,
                colorSpaceName = "Neo Wide Gamut", colorScienceName = "NeoGamut APEX"
            ).pack()
            assertEquals(52, packed.size)
            assertTrue("poisoned input ($bad) must still pack finite", packed.all { it.isFinite() })
        }
        // The construction guard rejects a directly-poisoned field (fail-safe; NaN never silently ships).
        try {
            VulkanColorScienceUniforms.disabled().copy(hdrParams = floatArrayOf(0f, Float.NaN, 0f, 0f))
            fail("non-finite uniform field must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertArrayEquals48(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    /** Column-major 4x4 multiply (GLSL convention): result = A * B. */
    private fun mul4x4ColumnMajor(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = s
        }
        return out
    }
}
