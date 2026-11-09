package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s2-f08 / CP43 — NRLI 3D-LUT applicator locks. The tetrahedral CPU reference mirrors the shader's
 * gated tetrahedral branch (the trilinear path stays the default). Proves: identity passthrough,
 * node-exactness, affine-exactness (the correctness property of barycentric interpolation), tetra≈
 * trilinear on a smooth LUT, and extended-range (>1) clamp-to-edge monotonicity.
 */
class SnceNrliApplicatorEliteTest {

    private fun buildLut(size: Int, f: (Float, Float, Float) -> FloatArray): SnceAdaptiveLut {
        val maxIdx = size - 1
        val rgb = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val o = f(r.toFloat() / maxIdx, g.toFloat() / maxIdx, b.toFloat() / maxIdx)
            rgb[idx++] = o[0]; rgb[idx++] = o[1]; rgb[idx++] = o[2]
        }
        return SnceAdaptiveLut(size, rgb)
    }

    private fun affine(r: Float, g: Float, b: Float) = floatArrayOf(
        0.70f * r + 0.10f * g + 0.05f * b + 0.05f,
        0.05f * r + 0.80f * g + 0.05f * b + 0.05f,
        0.10f * r + 0.05f * g + 0.75f * b + 0.05f
    )

    // Deterministic pseudo-random coords (no Math.random in this env).
    private fun coords(n: Int): List<FloatArray> = (0 until n).map { i ->
        floatArrayOf(((i * 47) % 100) / 100f, ((i * 31 + 7) % 100) / 100f, ((i * 89 + 13) % 100) / 100f)
    }

    // PASSTHROUGH LOCK: an identity LUT reproduces the input (tetrahedral and trilinear).
    @Test
    fun `identity LUT is exact passthrough`() {
        val lut = SnceNeuralEnhancement.identityLut(33)
        coords(60).forEach { c ->
            val tet = SnceNeuralEnhancement.tetrahedralSampleLut(lut, c)
            val tri = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) {
                assertEquals("tetra passthrough", c[k], tet[k], 1e-4f)
                assertEquals("trilinear passthrough", c[k], tri[k], 1e-4f)
            }
        }
    }

    // NODE-EXACT: at exact lattice nodes both samplers return the stored node.
    @Test
    fun `tetrahedral is exact at lattice nodes`() {
        val size = 17
        val lut = buildLut(size) { r, g, b -> affine(r, g, b) }
        val maxIdx = size - 1
        for (ri in intArrayOf(0, 4, 8, maxIdx)) for (gi in intArrayOf(0, 8, maxIdx)) for (bi in intArrayOf(0, 12, maxIdx)) {
            val c = floatArrayOf(ri.toFloat() / maxIdx, gi.toFloat() / maxIdx, bi.toFloat() / maxIdx)
            val tet = SnceNeuralEnhancement.tetrahedralSampleLut(lut, c)
            val exact = affine(c[0], c[1], c[2])
            for (k in 0..2) assertEquals("node exact", exact[k], tet[k], 1e-4f)
        }
    }

    // AFFINE-EXACT: tetrahedral (barycentric) interpolation reproduces an affine transform exactly.
    @Test
    fun `tetrahedral reproduces an affine LUT exactly between nodes`() {
        val lut = buildLut(9) { r, g, b -> affine(r, g, b) }
        var maxErr = 0f
        coords(120).forEach { c ->
            val tet = SnceNeuralEnhancement.tetrahedralSampleLut(lut, c)
            val exact = affine(c[0], c[1], c[2])
            for (k in 0..2) maxErr = maxOf(maxErr, abs(tet[k] - exact[k]))
        }
        assertTrue("tetrahedral must reproduce affine within 1e-4 (got $maxErr)", maxErr <= 1e-4f)
    }

    // TETRA ≈ TRILINEAR: on a smooth LUT the tetrahedral sampler is a valid, close alternative.
    @Test
    fun `tetrahedral and trilinear agree closely on a smooth LUT`() {
        val lut = buildLut(33) { r, g, b ->
            floatArrayOf(
                Math.pow(r.toDouble(), 0.85).toFloat(),
                Math.pow(g.toDouble(), 0.9).toFloat(),
                Math.pow(b.toDouble(), 0.95).toFloat()
            )
        }
        var maxDiff = 0f
        coords(200).forEach { c ->
            val tet = SnceNeuralEnhancement.tetrahedralSampleLut(lut, c)
            val tri = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) maxDiff = maxOf(maxDiff, abs(tet[k] - tri[k]))
        }
        assertTrue("tetra vs trilinear must stay close on a smooth LUT (got $maxDiff)", maxDiff <= 0.02f)
    }

    // EXTENDED RANGE: input > 1 clamps to the top node — monotonic, no overshoot or banding.
    @Test
    fun `extended-range input clamps to the boundary monotonically`() {
        val lut = SnceNeuralEnhancement.identityLut(33)
        var prev = -1f
        var x = 0.8f
        while (x <= 1.6f) {
            val out = SnceNeuralEnhancement.tetrahedralSampleLut(lut, floatArrayOf(x, 0.5f, 0.5f))
            assertTrue("red monotone non-decreasing at x=$x", out[0] >= prev - 1e-5f)
            assertTrue("red never overshoots 1.0", out[0] <= 1f + 1e-5f)
            prev = out[0]
            x += 0.05f
        }
        // beyond 1.0 it holds the top node (1.0)
        assertEquals(1f, SnceNeuralEnhancement.tetrahedralSampleLut(lut, floatArrayOf(1.4f, 0.5f, 0.5f))[0], 1e-4f)
    }
}
