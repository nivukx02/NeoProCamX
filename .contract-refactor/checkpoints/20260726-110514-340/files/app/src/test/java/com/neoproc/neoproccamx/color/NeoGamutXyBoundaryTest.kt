package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * s2-f11 / CP40 (GPU half) locks. The export shader's xy gamut-compression boundary is mirrored
 * by [NeoGamutXyBoundary]; these tests (a) golden-lock today's analytic cosine boundary so the
 * device default stays bit-for-bit, (b) prove the exact branch lands on the true primary triangle,
 * (c) prove the exact boundary is a real improvement (the cosine deviates from the true gamut), and
 * (d) keep the Oklab cusp upload artifact healthy.
 */
class NeoGamutXyBoundaryTest {

    private fun distToSegment(px: Float, py: Float, a: FloatArray, b: FloatArray): Float {
        val abx = b[0] - a[0]; val aby = b[1] - a[1]
        val apx = px - a[0]; val apy = py - a[1]
        val denom = (abx * abx + aby * aby).coerceAtLeast(1e-12f)
        val t = ((apx * abx + apy * aby) / denom).coerceIn(0f, 1f)
        val cx = a[0] + t * abx; val cy = a[1] + t * aby
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    // (a) NO-DOWNGRADE: today's analytic cosine boundary, frozen.
    @Test
    fun `analytic soft boundary is frozen at the live shader values`() {
        assertEquals(0.4772371f, NeoGamutXyBoundary.analyticSoftBoundary(0f), 1e-4f)
        assertEquals(0.3172371f, NeoGamutXyBoundary.analyticSoftBoundary((Math.PI / 3).toFloat()), 1e-4f)
        assertEquals(0.3327629f, NeoGamutXyBoundary.analyticSoftBoundary((Math.PI / 2).toFloat()), 1e-4f)
        assertEquals(0.3172371f, NeoGamutXyBoundary.analyticSoftBoundary(Math.PI.toFloat()), 1e-4f)
        // The clamp ceiling holds for any angle.
        for (i in 0 until 360) {
            val v = NeoGamutXyBoundary.analyticSoftBoundary((i * Math.PI / 180).toFloat())
            assertTrue("analytic boundary stays in [0.18,0.48]", v in 0.18f..0.48f)
        }
    }

    // (b) The exact branch lands exactly on the NeoWideGamut primary triangle edge.
    @Test
    fun `exact boundary radius lands on the primary triangle edge for every hue`() {
        val edges = arrayOf(
            arrayOf(NeoGamutXyBoundary.PRIMARY_R, NeoGamutXyBoundary.PRIMARY_G),
            arrayOf(NeoGamutXyBoundary.PRIMARY_G, NeoGamutXyBoundary.PRIMARY_B),
            arrayOf(NeoGamutXyBoundary.PRIMARY_B, NeoGamutXyBoundary.PRIMARY_R)
        )
        for (i in 0 until 256) {
            val angle = (i.toFloat() / 256f) * (2f * Math.PI.toFloat())
            val r = NeoGamutXyBoundary.exactGamutBoundaryRadius(angle)
            assertTrue("radius must be positive at angle $angle", r > 0f)
            val px = NeoGamutXyBoundary.CENTER_X + r * cos(angle)
            val py = NeoGamutXyBoundary.CENTER_Y + r * sin(angle)
            val nearest = edges.minOf { distToSegment(px, py, it[0], it[1]) }
            assertTrue("boundary point must sit on a triangle edge (off by $nearest)", nearest <= 1e-3f)
        }
    }

    // (c) The exact boundary is a genuine improvement: the cosine guess deviates from the true gamut.
    @Test
    fun `the analytic cosine boundary deviates from the exact gamut boundary`() {
        var maxDev = 0f
        for (i in 0 until 720) {
            val angle = (i.toFloat() / 720f) * (2f * Math.PI.toFloat())
            val a = NeoGamutXyBoundary.analyticSoftBoundary(angle)
            val e = NeoGamutXyBoundary.exactGamutBoundaryRadius(angle)
            maxDev = maxOf(maxDev, kotlin.math.abs(a - e))
        }
        assertTrue("analytic guess must measurably differ from the exact boundary (max dev $maxDev)",
            maxDev > 0.03f)
    }

    // (d) The Oklab cusp upload artifact stays healthy (foundation for the sRGB-space path).
    @Test
    fun `cusp LUT bake is finite, positive, and boundary-correct`() {
        val bins = 64
        val lut = OklabGamutCusp.cuspLut(bins)
        assertEquals(bins * 2, lut.size)
        for (i in 0 until bins) {
            val lCusp = lut[i * 2]
            val cCusp = lut[i * 2 + 1]
            assertTrue("L_cusp finite & in (0,1]", lCusp.isFinite() && lCusp > 0f && lCusp <= 1f)
            assertTrue("C_cusp finite & positive", cCusp.isFinite() && cCusp > 0f)
            // The cusp must sit on the sRGB boundary: max linear channel == 1.0.
            val hue = (i.toFloat() / bins) * (2f * Math.PI.toFloat())
            val rgb = OklabGamutCusp.oklabToLinearSrgb(lCusp, cCusp * cos(hue), cCusp * sin(hue))
            val maxCh = maxOf(rgb[0], rgb[1], rgb[2])
            assertEquals("cusp on gamut boundary at bin $i", 1.0f, maxCh, 3e-3f)
        }
    }
}
