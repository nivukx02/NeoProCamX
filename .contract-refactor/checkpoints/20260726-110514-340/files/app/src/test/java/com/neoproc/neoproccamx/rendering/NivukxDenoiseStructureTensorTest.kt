package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-golden for the fixed structure-tensor detail estimator in nivukx_denoise.comp.
 *
 * The estimator previously accumulated s11 += (gx*lum)^2 per sample, i.e.
 * Sum((gx*lum)^2), which is NOT a structure tensor: a flat field produced a large
 * detailIntensity (Sum gx^2 = 12 over the 3x3), so DetailMask saturated everywhere
 * and temporal averaging was cut on flat regions — the opposite of correct (audit
 * rank 5). The fix accumulates the gradient first (Gx = Sum gx*lum) and builds the
 * tensor from the outer product, so a flat field reads ~0 and an edge reads large.
 */
class NivukxDenoiseStructureTensorTest {

    /** Port of the fixed 3x3 Sobel structure-tensor detailIntensity (= trace = Gx^2 + Gy^2). */
    private fun detailIntensity(patch: Array<DoubleArray>): Double {
        var gradX = 0.0
        var gradY = 0.0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = dx.toDouble() * (if (dy == 0) 2.0 else 1.0)
                val gy = dy.toDouble() * (if (dx == 0) 2.0 else 1.0)
                val lum = patch[dy + 1][dx + 1]
                gradX += gx * lum
                gradY += gy * lum
            }
        }
        val s11 = gradX * gradX
        val s22 = gradY * gradY
        return s11 + s22 // lambda1 + lambda2 = trace
    }

    private fun flat(v: Double) = Array(3) { DoubleArray(3) { v } }

    @Test
    fun flatFieldHasNearZeroDetail() {
        // The core rank-5 fix: constant patches must read ~0 (they did NOT before).
        for (v in listOf(0.0, 0.2, 0.5, 0.8, 1.0)) {
            assertTrue("flat field read as detail at v=$v: ${detailIntensity(flat(v))}", detailIntensity(flat(v)) < 1e-9)
        }
    }

    @Test
    fun verticalEdgeHasLargeAnisotropicDetail() {
        // Left column dark, right column bright -> strong horizontal gradient.
        val edge = arrayOf(
            doubleArrayOf(0.0, 0.5, 1.0),
            doubleArrayOf(0.0, 0.5, 1.0),
            doubleArrayOf(0.0, 0.5, 1.0)
        )
        // Gx = 4 (full 0..1 edge), Gy = 0 -> detailIntensity = 16.
        assertTrue(detailIntensity(edge) > 10.0)
    }

    @Test
    fun subtleTextureFarBelowFullEdge() {
        val subtle = arrayOf(
            doubleArrayOf(0.49, 0.5, 0.51),
            doubleArrayOf(0.49, 0.5, 0.51),
            doubleArrayOf(0.49, 0.5, 0.51)
        )
        val di = detailIntensity(subtle)
        // Gx = 4*0.01 = 0.04 -> ~0.0016; clearly distinguishable from flat(0) and a full edge.
        assertTrue(di > 0.0 && di < 0.05)
    }
}
