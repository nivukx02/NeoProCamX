package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s2-f09 / CP4A — classical semantic-region colour constancy. Gated off ⇒ no-op; gated on ⇒ a
 * bounded WB nudge toward the region's anchor that never exceeds the ±0.05 ceiling.
 */
class SnceSemanticColorConstancyTest {

    private val SKIN = 1

    /** A thumbnail whose whole frame is one region with a known cast, plus a matching full-frame mask. */
    private fun scene(light: FloatArray, w: Int = 8, h: Int = 8): Pair<SnceThumbnail, SnceSegmentationResult> {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            rgb[i * 3] = 0.4f * light[0]; rgb[i * 3 + 1] = 0.4f * light[1]; rgb[i * 3 + 2] = 0.4f * light[2]
        }
        val mask = SnceSegmentationResult(w, h, IntArray(w * h) { SKIN }, FloatArray(w * h) { 1f })
        return SnceThumbnail(w, h, rgb) to mask
    }

    @Test
    fun `gated off is a no-op`() {
        val (thumb, mask) = scene(floatArrayOf(1.3f, 1f, 0.7f))
        val c = SnceSemanticColorConstancy.regionKeyedCorrection(mask, thumb, SKIN, 1f, 1f, gateEnabled = false)
        assertEquals(0f, c.wbR, 0f)
        assertEquals(0f, c.wbB, 0f)
    }

    @Test
    fun `null or mismatched mask is a no-op`() {
        val (thumb, _) = scene(floatArrayOf(1.3f, 1f, 0.7f))
        val none = SnceSemanticColorConstancy.regionKeyedCorrection(null, thumb, SKIN, 1f, 1f, gateEnabled = true)
        assertEquals(0f, none.wbR, 0f)
        val wrong = SnceSegmentationResult(4, 4, IntArray(16) { SKIN }, FloatArray(16) { 1f })
        val mism = SnceSemanticColorConstancy.regionKeyedCorrection(wrong, thumb, SKIN, 1f, 1f, gateEnabled = true)
        assertEquals(0f, mism.wbR, 0f)
    }

    @Test
    fun `absent region is a no-op`() {
        val (thumb, mask) = scene(floatArrayOf(1.3f, 1f, 0.7f))
        val c = SnceSemanticColorConstancy.regionKeyedCorrection(mask, thumb, regionClassId = 99, 1f, 1f, gateEnabled = true)
        assertEquals(0f, c.wbR, 0f)
        assertEquals(0f, c.wbB, 0f)
    }

    @Test
    fun `gated on, a casted region is nudged toward the anchor within the ceiling`() {
        // Region is red-cast (r/g = 1.3); anchor is neutral (1,1). Correction must pull red DOWN.
        val (thumb, mask) = scene(floatArrayOf(1.3f, 1f, 0.7f))
        val c = SnceSemanticColorConstancy.regionKeyedCorrection(
            mask, thumb, SKIN, targetRoverG = 1f, targetBoverG = 1f, strength = 0.5f, gateEnabled = true
        )
        assertTrue("red must be nudged down", c.wbR < 0f)
        assertTrue("blue must be nudged up (b/g 0.7 < target 1)", c.wbB > 0f)
        assertTrue("wbR within ceiling", kotlin.math.abs(c.wbR) <= SnceSemanticColorConstancy.MAX_WB_NUDGE)
        assertTrue("wbB within ceiling", kotlin.math.abs(c.wbB) <= SnceSemanticColorConstancy.MAX_WB_NUDGE)
    }

    @Test
    fun `the correction is hard-clamped for an extreme cast`() {
        val (thumb, mask) = scene(floatArrayOf(3.0f, 1f, 0.2f))  // huge cast
        val c = SnceSemanticColorConstancy.regionKeyedCorrection(
            mask, thumb, SKIN, targetRoverG = 1f, targetBoverG = 1f, strength = 1f, gateEnabled = true
        )
        assertEquals("wbR saturates the ceiling", -SnceSemanticColorConstancy.MAX_WB_NUDGE, c.wbR, 0f)
        assertEquals("wbB saturates the ceiling", SnceSemanticColorConstancy.MAX_WB_NUDGE, c.wbB, 0f)
    }
}
