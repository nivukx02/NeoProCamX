package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s7-f04 / CP4A — region-constancy aggregator. Sums per-region nudges and proves the SUMMED total
 * can never exceed the global ±0.05 WB budget (no stacking past the clamp).
 */
class SnceSemanticConstancyAggregatorTest {

    /** Whole-frame thumbnail with a uniform r/g,b/g cast. */
    private fun castThumb(rOverG: Float, bOverG: Float, w: Int = 8, h: Int = 8): SnceThumbnail {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            rgb[i * 3] = 0.4f * rOverG
            rgb[i * 3 + 1] = 0.4f
            rgb[i * 3 + 2] = 0.4f * bOverG
        }
        return SnceThumbnail(w, h, rgb)
    }

    @Test
    fun gatedOffIsNoOp() {
        val thumb = castThumb(2.0f, 0.5f)
        val mask = SnceSegmentationResult(8, 8, IntArray(64) { SnceSemanticColorConstancy.CLASS_BODY_SKIN }, FloatArray(64) { 1f })
        val c = SnceSemanticColorConstancy.regionConstancyNudge(mask, thumb, gateEnabled = false)
        assertEquals(0f, c.wbR, 0f)
        assertEquals(0f, c.wbB, 0f)
    }

    @Test
    fun nullMaskIsNoOp() {
        val c = SnceSemanticColorConstancy.regionConstancyNudge(null, castThumb(2.0f, 0.5f), gateEnabled = true)
        assertEquals(0f, c.wbR, 0f)
        assertEquals(0f, c.wbB, 0f)
    }

    @Test
    fun singleSkinRegionNudgesTowardAnchor() {
        // Red-cast skin region (r/g = 2.0 > skin anchor 1.18) => pull red DOWN (wbR < 0).
        val thumb = castThumb(2.0f, 0.5f)
        val mask = SnceSegmentationResult(8, 8, IntArray(64) { SnceSemanticColorConstancy.CLASS_BODY_SKIN }, FloatArray(64) { 1f })
        val c = SnceSemanticColorConstancy.regionConstancyNudge(mask, thumb, gateEnabled = true)
        assertTrue("red nudged down", c.wbR < 0f)
        assertTrue("blue nudged up (b/g 0.5 < anchor 0.85)", c.wbB > 0f)
    }

    @Test
    fun stackedRegionsCannotExceedTheGlobalBudget() {
        // Two skin sub-classes both heavily red-cast in the SAME direction: each saturates its own
        // ±0.05, summing to ±0.10 BEFORE the total clamp — which must pin them back to ±0.05.
        val thumb = castThumb(3.0f, 0.2f)
        val classMask = IntArray(64) { i ->
            if (i < 32) SnceSemanticColorConstancy.CLASS_BODY_SKIN else SnceSemanticColorConstancy.CLASS_FACE_SKIN
        }
        val mask = SnceSegmentationResult(8, 8, classMask, FloatArray(64) { 1f })
        val c = SnceSemanticColorConstancy.regionConstancyNudge(mask, thumb, gateEnabled = true)
        assertTrue("wbR within global budget", abs(c.wbR) <= SnceSemanticColorConstancy.MAX_WB_NUDGE + 1e-6f)
        assertTrue("wbB within global budget", abs(c.wbB) <= SnceSemanticColorConstancy.MAX_WB_NUDGE + 1e-6f)
        // Saturated (each region alone already hit the clamp, sum exceeds it -> total clamp).
        assertEquals(-SnceSemanticColorConstancy.MAX_WB_NUDGE, c.wbR, 1e-6f)
        assertEquals(SnceSemanticColorConstancy.MAX_WB_NUDGE, c.wbB, 1e-6f)
    }
}
