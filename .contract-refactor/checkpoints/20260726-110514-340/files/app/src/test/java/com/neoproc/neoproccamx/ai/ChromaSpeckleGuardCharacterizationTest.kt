package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Characterizes [PerceptualRefinementMath.applyOklabChromaSpeckleGuard] — the Kotlin-side chroma
 * outlier guard run after temporal/bilateral chroma denoise.
 *
 * The existing test (PerceptualRefinementMathTest) pins that an ISOLATED chroma spike is suppressed
 * and hue is held. The complementary, behavior-critical property — that genuine chroma detail is
 * PRESERVED, not flattened — was unprotected. Preservation is governed by two distinct mechanisms,
 * and this test pins BOTH separately:
 *
 *  - The `scale >= 0.995f` short-circuit (line ~299): a pixel equal to its neighborhood mean has
 *    deviation 0, so it is never altered. [preservesUniformSaturatedRegionInterior] pins this.
 *  - The `centerChroma <= mean * 1.10f` edge gate (line ~280): a pixel mildly MORE saturated than
 *    its neighbourhood (within 10%) is treated as real detail and skipped. Without this gate it
 *    would be suppressed. [edgeGatePreservesPixelWithinTenPercentOfNeighborhoodMean] pins it with a
 *    pixel that would be scaled to ~0.40x if the gate were removed.
 *
 * Plus selectivity, idempotence on clean frames, and the disable/too-small passthroughs.
 * Test-only: no production code is changed.
 */
class ChromaSpeckleGuardCharacterizationTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun angleDistance(a: Float, b: Float): Float {
        var delta = a - b
        while (delta > Math.PI.toFloat()) delta -= (2f * Math.PI.toFloat())
        while (delta < -Math.PI.toFloat()) delta += (2f * Math.PI.toFloat())
        return delta
    }

    private val neutral = argb(96, 104, 100)
    private val saturated = argb(40, 170, 150) // mid-saturation teal, valid through the Oklab round-trip

    /** Builds a frame split left=neutral / right=saturated, forming one genuine vertical chroma edge. */
    private fun edgeFrame(width: Int, height: Int, edgeX: Int): IntArray =
        IntArray(width * height) { i -> if ((i % width) >= edgeX) saturated else neutral }

    @Test
    fun preservesUniformSaturatedRegionInterior() {
        val w = 16
        val h = 16
        val pixels = edgeFrame(w, h, edgeX = 8)
        val refined = PerceptualRefinementMath.applyOklabChromaSpeckleGuard(pixels, w, h, strength = 0.95f)

        // Deep-interior pixel of the uniform saturated block: deviation == 0, so the scale>=0.995
        // short-circuit leaves it byte-identical (a flat saturated region must never be flattened).
        val idx = 8 * w + 11
        assertEquals(pixels[idx], refined[idx])
    }

    @Test
    fun edgeGatePreservesPixelWithinTenPercentOfNeighborhoodMean() {
        val w = 16
        val h = 16
        val pixels = edgeFrame(w, h, edgeX = 8)
        // A single pixel deep inside the saturated block, ~8% MORE saturated than its uniform
        // neighbourhood. Its chroma sits within mean*1.10 (gate fires -> preserved), but it is enough
        // of an outlier that WITHOUT the line-280 gate it would be suppressed to ~0.40x. So this
        // assertion fails if the edge gate is weakened or removed — the load-bearing pin for genuine
        // chroma-detail preservation.
        val idx = 8 * w + 11
        pixels[idx] = argb(36, 176, 146)

        val refined = PerceptualRefinementMath.applyOklabChromaSpeckleGuard(pixels, w, h, strength = 0.95f)
        assertEquals("a within-gate chroma detail pixel must be preserved", pixels[idx], refined[idx])
    }

    @Test
    fun suppressesIsolatedSpikeWhilePreservingAdjacentEdge() {
        val w = 16
        val h = 16
        val pixels = edgeFrame(w, h, edgeX = 8)
        // Isolated chroma spike planted in the neutral (left) half.
        val spikeIdx = 4 * w + 3
        pixels[spikeIdx] = argb(34, 198, 180)

        val spikeBefore = PerceptualRefinementMath.oklabChroma(pixels[spikeIdx])
        val spikeHueBefore = PerceptualRefinementMath.oklabHueRadians(pixels[spikeIdx])
        val refined = PerceptualRefinementMath.applyOklabChromaSpeckleGuard(pixels, w, h, strength = 0.95f)
        val spikeAfter = PerceptualRefinementMath.oklabChroma(refined[spikeIdx])
        val spikeHueAfter = PerceptualRefinementMath.oklabHueRadians(refined[spikeIdx])

        // Spike strongly suppressed (mirrors the existing 0.78x threshold)...
        assertTrue("isolated spike must be suppressed", spikeAfter < spikeBefore * 0.78f)
        // ...without rotating hue...
        assertTrue("hue must be held", abs(angleDistance(spikeHueBefore, spikeHueAfter)) < 0.10f)
        // ...while a real edge-region interior pixel is left intact (selectivity).
        val edgeIdx = 8 * w + 11
        assertEquals(pixels[edgeIdx], refined[edgeIdx])
    }

    @Test
    fun isIdempotentOnLowChromaFrame() {
        val w = 9
        val h = 9
        val pixels = IntArray(w * h) { argb(100, 100, 100) } // near-neutral, below the 0.006 chroma gate
        val refined = PerceptualRefinementMath.applyOklabChromaSpeckleGuard(pixels, w, h, strength = 0.95f)
        assertArrayEquals("a clean low-chroma frame must pass through untouched", pixels, refined)
    }

    @Test
    fun passesThroughWhenDisabledOrTooSmall() {
        val w = 16
        val h = 16
        val frame = edgeFrame(w, h, edgeX = 8)
        // strength below the 0.001 floor -> early return of the same array reference.
        assertSame(frame, PerceptualRefinementMath.applyOklabChromaSpeckleGuard(frame, w, h, strength = 0f))

        // Image smaller than the 3x3 minimum -> early return of the same array reference.
        val tiny = IntArray(2 * 2) { saturated }
        assertSame(tiny, PerceptualRefinementMath.applyOklabChromaSpeckleGuard(tiny, 2, 2, strength = 0.95f))
    }
}
