package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Locks the invariants of the color-stability upgrade batch:
 *  - P2.9 lux/ISO-adaptive drift budget ([VideoColorStabilityPolicy.lowLightBudgetScale])
 *  - P1.6 device-true fallback white-point bridge ([WhiteBalanceChromaticity.uvToXy] →
 *    [DngWhiteBalanceMath.xyToKelvinTint])
 *  - P1.8 same-sensor white-point carry-over ([ColorDriftGovernor.seed])
 */
class ColorStabilityUpgradeTest {

    // ── P2.9: lux/ISO-adaptive budget scale ────────────────────────────────────────────────────

    @Test
    fun `budget scale is unity at and below the ISO floor (no downgrade in normal light)`() {
        assertEquals(1f, VideoColorStabilityPolicy.lowLightBudgetScale(-1), 0f)
        assertEquals(1f, VideoColorStabilityPolicy.lowLightBudgetScale(0), 0f)
        assertEquals(1f, VideoColorStabilityPolicy.lowLightBudgetScale(100), 0f)
        assertEquals(1f, VideoColorStabilityPolicy.lowLightBudgetScale(VideoColorStabilityPolicy.LOW_LIGHT_ISO_FLOOR), 0f)
    }

    @Test
    fun `budget scale reaches the floor at and above the ISO ceiling`() {
        val min = VideoColorStabilityPolicy.LOW_LIGHT_MIN_BUDGET_SCALE
        assertEquals(min, VideoColorStabilityPolicy.lowLightBudgetScale(VideoColorStabilityPolicy.LOW_LIGHT_ISO_CEIL), 1e-6f)
        assertEquals(min, VideoColorStabilityPolicy.lowLightBudgetScale(25_600), 1e-6f)
    }

    @Test
    fun `budget scale is monotonically non-increasing with ISO`() {
        val isos = listOf(400, 800, 1200, 1600, 3200, 4800, 6400, 12800)
        var previous = Float.MAX_VALUE
        for (iso in isos) {
            val s = VideoColorStabilityPolicy.lowLightBudgetScale(iso)
            assertTrue("scale should not increase with ISO at $iso", s <= previous + 1e-6f)
            assertTrue("scale stays within [min,1] at $iso",
                s in VideoColorStabilityPolicy.LOW_LIGHT_MIN_BUDGET_SCALE..1f)
            previous = s
        }
    }

    // ── P1.6: device-true fallback white-point bridge ──────────────────────────────────────────

    @Test
    fun `D65 u'v' bridges through xy back to a daylight color temperature`() {
        // Neutral D65 white point used across the stabilizer.
        val d65 = ColorChromaticity(0.1978f, 0.4683f)
        val xy = WhiteBalanceChromaticity.uvToXy(d65)
        assertNotNull(xy)
        xy!!
        // D65 chromaticity ≈ (0.3127, 0.3290).
        assertEquals(0.3127f, xy[0], 5e-3f)
        assertEquals(0.3290f, xy[1], 5e-3f)
        val (kelvin, tint) = DngWhiteBalanceMath.xyToKelvinTint(xy[0].toDouble(), xy[1].toDouble())
        assertEquals("D65 should map near 6500K", 6500.0, kelvin, 400.0)
        assertTrue("D65 sits essentially on the locus (small tint)", kotlin.math.abs(tint) < 20.0)
    }

    // ── P1.8: same-sensor white-point carry-over ───────────────────────────────────────────────

    @Test
    fun `seed sets the white point without marking a clamp`() {
        val g = ColorDriftGovernor()
        val seed = ColorChromaticity(0.2020f, 0.4670f)
        g.seed(seed)
        assertEquals(seed, g.stabilizedWhitePoint)
        // No prior result => the active-override path will not apply a seeded point pre-measurement.
        assertFalse(g.lastFrameClamped)
    }

    @Test
    fun `after seeding, a distant measurement is slew-limited toward the seed (never jumps)`() {
        val g = ColorDriftGovernor()
        val seed = ColorChromaticity(0.2000f, 0.4700f)
        g.seed(seed)
        val budget = 0.0006f
        // A far-away measured white point (strong red gain) with a tight budget.
        val result = g.accept(3.0f, 1.0f, 1.0f, 0.6f, budget)
        assertNotNull(result)
        result!!
        assertTrue("distant measurement must be clamped", result.clamped)
        val moved = hypot(
            (g.stabilizedWhitePoint!!.u - seed.u).toDouble(),
            (g.stabilizedWhitePoint!!.v - seed.v).toDouble()
        )
        assertTrue("step magnitude must respect the budget", moved <= budget + 1e-5)
        assertTrue("must actually move toward the target", moved > 0.0)
    }
}
