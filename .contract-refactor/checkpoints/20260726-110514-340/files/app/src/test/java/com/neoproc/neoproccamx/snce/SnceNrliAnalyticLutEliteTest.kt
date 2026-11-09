package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * CP-QV21 — golden locks for the replaceable near-identity NRLI 3D-LUT.
 *
 * Proves the three safety properties the live wiring depends on:
 *  (a) the analytic look is a BOUNDED near-identity mapping (subtle, non-destructive) when sampled by the
 *      CPU trilinear reference [SnceNeuralEnhancement.trilinearSampleLut] that mirrors the live shader;
 *  (b) the PURE identity LUT is an EXACT passthrough (the strength-0 / default-behaviour anchor);
 *  (c) the analytic LUT array LENGTH matches exactly what the native `nativeUpdateNrliLut` path expects
 *      (size^3 * 3 floats), so the ABI can't silently drift.
 *
 * Layout note: the LUT is R-fastest (`idx = ((b*size + g)*size + r)*3 + channel`), identical to
 * [SnceAdaptiveLut], the native `createNrliIdentityLut`, and the shader's texel order.
 */
class SnceNrliAnalyticLutEliteTest {

    private val size = 33

    // Deterministic pseudo-random coords in [0,1] (no Math.random in this env).
    private fun coords(n: Int): List<FloatArray> = (0 until n).map { i ->
        floatArrayOf(((i * 47) % 100) / 100f, ((i * 31 + 7) % 100) / 100f, ((i * 89 + 13) % 100) / 100f)
    }

    // (a) BOUNDED near-identity: every sampled output stays within MAX_DEVIATION of the input.
    @Test
    fun `analytic LUT is a bounded near-identity look`() {
        val lut = SnceNrliAnalyticLut.buildNearIdentityLut(size)
        var maxErr = 0f
        coords(400).forEach { c ->
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) maxErr = maxOf(maxErr, abs(out[k] - c[k]))
        }
        assertTrue(
            "analytic look must stay within ${SnceNrliAnalyticLut.MAX_DEVIATION} of identity (got $maxErr)",
            maxErr <= SnceNrliAnalyticLut.MAX_DEVIATION
        )
    }

    // (a') NON-TRIVIAL: it is a real look, not a disguised identity — some voxel actually deviates.
    @Test
    fun `analytic LUT actually deviates from identity somewhere`() {
        val lut = SnceNrliAnalyticLut.buildNearIdentityLut(size)
        var maxErr = 0f
        // Mid-luma, saturated colours are where the grade bites hardest.
        listOf(
            floatArrayOf(0.8f, 0.2f, 0.2f),
            floatArrayOf(0.2f, 0.5f, 0.8f),
            floatArrayOf(0.6f, 0.4f, 0.2f)
        ).forEach { c ->
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) maxErr = maxOf(maxErr, abs(out[k] - c[k]))
        }
        assertTrue("analytic look must be a real (non-identity) grade (got $maxErr)", maxErr > 1e-4f)
    }

    // (a'') GREY-AXIS PRESERVING: neutral greys (r==g==b) stay neutral (no colour cast on greys).
    @Test
    fun `analytic LUT preserves the grey axis`() {
        val lut = SnceNrliAnalyticLut.buildNearIdentityLut(size)
        var x = 0f
        while (x <= 1f) {
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, floatArrayOf(x, x, x))
            // Channels stay equal (no cast) and near the input grey.
            assertEquals("grey stays neutral (r==g) at $x", out[0], out[1], SnceNrliAnalyticLut.GREY_AXIS_TOLERANCE)
            assertEquals("grey stays neutral (g==b) at $x", out[1], out[2], SnceNrliAnalyticLut.GREY_AXIS_TOLERANCE)
            x += 1f / (size - 1)
        }
    }

    // (b) PASSTHROUGH ANCHOR: the pure identity LUT reproduces the input exactly (the strength-0 default).
    @Test
    fun `identity LUT is exact passthrough`() {
        val lut = SnceNeuralEnhancement.identityLut(size)
        coords(200).forEach { c ->
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) assertEquals("identity passthrough", c[k], out[k], 1e-4f)
        }
    }

    // (c) ABI/LENGTH: the analytic LUT float count == what the native nativeUpdateNrliLut path expects.
    @Test
    fun `analytic LUT length matches the native NRLI ABI`() {
        val lut = SnceNrliAnalyticLut.buildNearIdentityLut(size)
        // Native: expectedLength = size^3 * 3 (rgb, R-fastest); matches SnceAdaptiveLut's own require().
        val expected = size * size * size * 3
        assertEquals("analytic LUT rgb length must be size^3*3", expected, lut.rgb.size)
        assertEquals("native NRLI LUT size is 33", 33, size)
        assertEquals("SnceAdaptiveLut.size round-trips", size, lut.size)
        // All voxels in [0,1] (native clamps, but the builder must already produce a valid gamut LUT).
        assertTrue("all voxels in [0,1]", lut.rgb.all { it in 0f..1f && it.isFinite() })
    }
}
