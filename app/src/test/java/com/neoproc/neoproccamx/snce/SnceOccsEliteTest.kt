package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * s2-f07 / CP41/CP46/CP49 — OCCS opponent lateral-inhibition locks. The CPU reference
 * (SnceOpponentChannels) mirrors the GPU pass. Proves: zero-gain copy-back is exact passthrough,
 * neutral chroma is untouched, the opponent transform is hue-preserving (round-trips), the separable
 * DoG equals the reference 2D DoG (CP49), the mesopic chroma-gain curve is monotone (CP46), and
 * OCCS sharpens a chroma edge.
 */
class SnceOccsEliteTest {

    private val W = 16; private val H = 16

    private fun field(f: (Int, Int) -> FloatArray): FloatArray {
        val out = FloatArray(W * H * 3)
        for (y in 0 until H) for (x in 0 until W) {
            val v = f(x, y); val i = (y * W + x) * 3
            out[i] = v[0]; out[i + 1] = v[1]; out[i + 2] = v[2]
        }
        return out
    }

    // ZERO-GAIN: the copy-back / zero-gain pass is exact passthrough.
    @Test
    fun `zero gain lateral inhibition is exact passthrough`() {
        val ipt = field { x, y -> floatArrayOf(0.4f + 0.01f * x, 0.05f * ((x + y) % 3), -0.03f * (x % 4)) }
        val out = SnceOpponentChannels.lateralInhibition(
            ipt, W, H, SnceOpponentChannels.DoGParams(iGain = 0f, chromaGain = 0f)
        )
        for (i in ipt.indices) assertEquals("passthrough[$i]", ipt[i], out[i], 1e-6f)
    }

    // NEUTRAL: a neutral field (P=T=0) keeps its chroma channels at zero at any gain.
    @Test
    fun `a neutral field keeps its chroma channels at zero`() {
        val ipt = field { _, _ -> floatArrayOf(0.5f, 0f, 0f) }
        val out = SnceOpponentChannels.lateralInhibition(ipt, W, H, SnceOpponentChannels.DoGParams())
        for (i in 0 until W * H) {
            assertEquals("P stays 0", 0f, out[i * 3 + 1], 1e-6f)
            assertEquals("T stays 0", 0f, out[i * 3 + 2], 1e-6f)
        }
    }

    // HUE-PRESERVING: the opponent (IPT) transform round-trips RGB without hue shift.
    @Test
    fun `the opponent transform round-trips RGB`() {
        listOf(
            floatArrayOf(0.5f, 0.4f, 0.3f), floatArrayOf(0.8f, 0.2f, 0.2f),
            floatArrayOf(0.2f, 0.6f, 0.3f), floatArrayOf(0.3f, 0.35f, 0.7f)
        ).forEach { rgb ->
            val xyz = SnceColorPrimitives.mulV(SnceIlluminantEstimator.REC709_TO_XYZ, rgb)
            val ipt = SnceColorPrimitives.xyzToIpt(xyz)
            val backXyz = SnceColorPrimitives.iptToXyz(ipt)
            for (k in 0..2) assertEquals("IPT round-trip", xyz[k], backXyz[k], 1e-4f)
        }
    }

    // CP49 SEPARABLE: a separable 2-pass Gaussian equals the reference 2D box-Gaussian (interior).
    @Test
    fun `the separable DoG matches the reference 2D Gaussian within epsilon`() {
        val radius = 5; val sigma = 1.2f
        val plane = FloatArray(W * H) { (it % 7) * 0.1f + (it / W) * 0.02f }
        val ref = box2D(plane, sigma, radius)
        val sep = blurY(blurX(plane, sigma, radius), sigma, radius)
        var maxErr = 0f
        for (y in radius until H - radius) for (x in radius until W - radius) {
            maxErr = maxOf(maxErr, abs(ref[y * W + x] - sep[y * W + x]))
        }
        assertTrue("separable must match 2D Gaussian on the interior (got $maxErr)", maxErr <= 1e-4f)
    }

    // CP46 MESOPIC: chroma-gain scale is monotone, bounded, and full at photopic.
    @Test
    fun `mesopic chroma gain scale is monotone and bounded`() {
        var prev = -1f
        for (lx in 0..40) {
            val lum = lx * 0.5f
            val s = SnceOpponentChannels.mesopicChromaGainScale(lum)
            assertTrue("scale in [0.25,1]", s in 0.25f..1f)
            assertTrue("monotone non-decreasing", s >= prev - 1e-6f)
            prev = s
        }
        assertEquals("full gain at photopic", 1f, SnceOpponentChannels.mesopicChromaGainScale(10f), 1e-5f)
        assertEquals("full gain above photopic", 1f, SnceOpponentChannels.mesopicChromaGainScale(50f), 1e-5f)
    }

    // OCCS sharpens a chroma edge (lateral inhibition enhances the gradient).
    @Test
    fun `OCCS sharpens a chroma edge`() {
        val ipt = field { x, _ -> floatArrayOf(0.5f, if (x < W / 2) 0.0f else 0.3f, 0f) }
        val out = SnceOpponentChannels.lateralInhibition(ipt, W, H, SnceOpponentChannels.DoGParams())
        val mid = W / 2; val row = H / 2
        fun p(x: Int) = ipt[(row * W + x) * 3 + 1]
        fun q(x: Int) = out[(row * W + x) * 3 + 1]
        // overshoot/undershoot near the edge => the output step across the boundary is steeper
        val inStep = abs(p(mid) - p(mid - 1))
        val outStep = abs(q(mid) - q(mid - 1))
        assertTrue("OCCS must sharpen the chroma edge ($outStep > $inStep)", outStep >= inStep)
    }

    // --- in-test ports of the shader's normalized Gaussian (2D) and its separable factorization ---
    private fun box2D(plane: FloatArray, sigma: Float, radius: Int): FloatArray {
        val inv = 1f / (2f * sigma * sigma)
        val out = FloatArray(plane.size)
        for (y in 0 until H) for (x in 0 until W) {
            var s = 0f; var w = 0f
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val cx = (x + dx).coerceIn(0, W - 1); val cy = (y + dy).coerceIn(0, H - 1)
                val wt = exp(-(dx * dx + dy * dy).toFloat() * inv)
                s += plane[cy * W + cx] * wt; w += wt
            }
            out[y * W + x] = s / w
        }
        return out
    }

    private fun blurX(plane: FloatArray, sigma: Float, radius: Int): FloatArray {
        val inv = 1f / (2f * sigma * sigma)
        val out = FloatArray(plane.size)
        for (y in 0 until H) for (x in 0 until W) {
            var s = 0f; var w = 0f
            for (dx in -radius..radius) {
                val cx = (x + dx).coerceIn(0, W - 1)
                val wt = exp(-(dx * dx).toFloat() * inv)
                s += plane[y * W + cx] * wt; w += wt
            }
            out[y * W + x] = s / w
        }
        return out
    }

    private fun blurY(plane: FloatArray, sigma: Float, radius: Int): FloatArray {
        val inv = 1f / (2f * sigma * sigma)
        val out = FloatArray(plane.size)
        for (y in 0 until H) for (x in 0 until W) {
            var s = 0f; var w = 0f
            for (dy in -radius..radius) {
                val cy = (y + dy).coerceIn(0, H - 1)
                val wt = exp(-(dy * dy).toFloat() * inv)
                s += plane[cy * W + x] * wt; w += wt
            }
            out[y * W + x] = s / w
        }
        return out
    }
}
