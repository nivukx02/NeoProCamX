package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceSpatialCoherenceTest {

    @Test
    fun `box mean of a constant field is constant`() {
        val w = 10; val h = 8
        val src = FloatArray(w * h) { 0.37f }
        val out = SnceSpatialCoherence.boxMean(src, w, h, 3)
        out.forEach { assertEquals(0.37f, it, 1e-4f) }
    }

    @Test
    fun `guided filter smooths a noisy flat region`() {
        val w = 16; val h = 16; val n = w * h
        // deterministic pseudo-noise around a flat mean
        val noisy = FloatArray(n) { 0.5f + (((it * 1103515245 + 12345) ushr 16) % 100 - 50) / 1000f }
        val filtered = SnceSpatialCoherence.guidedFilter(noisy, noisy, w, h, radius = 4, epsilon = 0.01f)
        fun variance(a: FloatArray): Float {
            val m = a.average().toFloat()
            return a.map { (it - m) * (it - m) }.average().toFloat()
        }
        assertTrue("guided filter reduces variance", variance(filtered) < variance(noisy))
    }

    @Test
    fun `guided filter preserves a strong edge`() {
        val w = 16; val h = 4; val n = w * h
        val step = FloatArray(n) { if ((it % w) < w / 2) 0.1f else 0.9f }
        val filtered = SnceSpatialCoherence.guidedFilter(step, step, w, h, radius = 3, epsilon = 1e-4f)
        // far from the edge the values are preserved
        assertEquals(0.1f, filtered[0], 0.05f)
        assertEquals(0.9f, filtered[w - 1], 0.05f)
    }

    @Test
    fun `banding elimination smooths an offset flat row`() {
        val w = 8; val h = 8; val n = w * h
        val hue = FloatArray(n) { 200f }
        // row 4 is a flat band offset by +6 deg from its neighbours
        for (x in 0 until w) hue[4 * w + x] = 206f
        val fixed = SnceSpatialCoherence.eliminateBanding(hue, w, h)
        assertEquals("offset band pulled back toward neighbours", 200f, fixed[4 * w + 0], 0.5f)
    }

    @Test
    fun `sky gradient moves toward the natural zenith-horizon profile`() {
        val (hZenith, cZenith) = SnceSpatialCoherence.skyGradient(
            hue = 210f, chroma = 30f, elevationNorm = 1f, skyConfidence = 1f, blend = 1f
        )
        assertEquals(220f, hZenith, 1e-3f)
        assertEquals(48f, cZenith, 1e-3f)
        val (hHorizon, _) = SnceSpatialCoherence.skyGradient(
            hue = 210f, chroma = 30f, elevationNorm = 0f, skyConfidence = 1f, blend = 1f
        )
        assertEquals(195f, hHorizon, 1e-3f)
    }
}
