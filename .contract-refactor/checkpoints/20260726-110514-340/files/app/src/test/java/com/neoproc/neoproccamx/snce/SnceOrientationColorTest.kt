package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceOrientationColorTest {

    @Test
    fun `sky fraction rises looking up and collapses looking down`() {
        assertTrue(SnceOrientationColor.skyFraction(80f) > 0.8f)
        assertEquals(0.02f, SnceOrientationColor.skyFraction(-45f), 1e-4f)
        val level = SnceOrientationColor.skyFraction(0f)
        assertTrue(level in 0.0f..0.1f)
    }

    @Test
    fun `orientation CCT is neutral level, warm down, cool up`() {
        assertEquals("level is neutral daylight", 6500f, SnceOrientationColor.orientationCct(0f), 1f)
        assertTrue("looking up is cool", SnceOrientationColor.orientationCct(85f) > 10000f)
        assertEquals(
            "far down approaches the ground prior",
            SnceOrientationColor.CCT_GROUND, SnceOrientationColor.orientationCct(-88f), 300f
        )
        assertTrue("down is warmer than level", SnceOrientationColor.orientationCct(-60f) < 6000f)
    }

    @Test
    fun `illuminant prior is bluer looking up, neutral when level, confidence capped`() {
        val up = SnceOrientationColor.illuminantPrior(85f, imuQuality = 1f, cloudCover = 0f)
        assertTrue("sky prior leans blue", up.rgb[2] > up.rgb[0])
        assertTrue("confidence capped at 0.65", up.confidence <= 0.65f)
        val cloudy = SnceOrientationColor.illuminantPrior(85f, imuQuality = 1f, cloudCover = 1f)
        assertEquals("full cloud cover zeroes confidence", 0f, cloudy.confidence, 1e-4f)
        val level = SnceOrientationColor.illuminantPrior(0f, imuQuality = 1f, cloudCover = 0f)
        assertTrue("level orientation carries ~no illuminant cue", level.confidence < 0.05f)
    }

    @Test
    fun `roll compensation triggers past the threshold but not near upright`() {
        assertTrue(SnceOrientationColor.rollCompensationNeeded(25f))
        assertFalse(SnceOrientationColor.rollCompensationNeeded(10f))
        assertFalse("near-90 roll is effectively upright", SnceOrientationColor.rollCompensationNeeded(88f))
    }

    @Test
    fun `madgwick converges to level from a flat accelerometer`() {
        val ahrs = SnceMadgwickAhrs(beta = 0.2f)
        repeat(1500) { ahrs.update(0f, 0f, 0f, 0f, 0f, 1f, 0.02f) }
        val e = ahrs.eulerDegrees()
        assertTrue("roll level", kotlin.math.abs(e[0]) < 2f)
        assertTrue("pitch level", kotlin.math.abs(e[1]) < 2f)
        val qn = kotlin.math.sqrt(
            ahrs.q0 * ahrs.q0 + ahrs.q1 * ahrs.q1 + ahrs.q2 * ahrs.q2 + ahrs.q3 * ahrs.q3
        )
        assertEquals("unit quaternion", 1f, qn, 1e-3f)
    }

    @Test
    fun `madgwick reflects a tilted accelerometer`() {
        val ahrs = SnceMadgwickAhrs(beta = 0.3f)
        val norm = kotlin.math.sqrt(0.6f * 0.6f + 0.8f * 0.8f)
        repeat(3000) { ahrs.update(0f, 0f, 0f, 0f, 0.6f / norm, 0.8f / norm, 0.02f) }
        val e = ahrs.eulerDegrees()
        assertTrue("a tilt must register", maxOf(kotlin.math.abs(e[0]), kotlin.math.abs(e[1])) > 10f)
    }
}
