package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorDriftGovernorTest {

    private val budget = 0.0060f

    @Test
    fun `first usable frame adopts the measurement without clamping`() {
        val governor = ColorDriftGovernor()
        val result = governor.accept(1f, 1f, 1f, 1f, budget)

        assertNotNull(result)
        assertFalse(result!!.clamped)
        assertTrue(result.converged)
        assertEquals(1L, governor.stabilizedFrameCount)
        assertEquals(0L, governor.clampedFrameCount)
        assertNotNull(governor.stabilizedWhitePoint)
    }

    @Test
    fun `a steady illuminant never clamps and stays converged`() {
        val governor = ColorDriftGovernor()
        repeat(5) {
            val result = governor.accept(1.4f, 1f, 1f, 1.6f, budget)
            assertNotNull(result)
            assertFalse("steady scene must not clamp", result!!.clamped)
        }
        assertEquals(5L, governor.stabilizedFrameCount)
        assertEquals(0L, governor.clampedFrameCount)
        assertFalse(governor.lastFrameClamped)
    }

    @Test
    fun `a large illuminant jump is clamped to the budget`() {
        val governor = ColorDriftGovernor()
        governor.accept(1f, 1f, 1f, 1f, budget) // settle on D65
        val jumped = governor.accept(1f, 1f, 1f, 3f, budget) // big warm swing

        assertNotNull(jumped)
        assertTrue("a large jump must clamp", jumped!!.clamped)
        assertTrue(jumped.appliedDriftUv <= budget + 1e-6f)
        assertEquals(1L, governor.clampedFrameCount)
        assertTrue(governor.lastFrameClamped)
    }

    @Test
    fun `unusable gains are ignored and leave temporal state untouched`() {
        val governor = ColorDriftGovernor()
        governor.accept(1.4f, 1f, 1f, 1.6f, budget)
        val whitePointBefore = governor.stabilizedWhitePoint
        val frameCountBefore = governor.stabilizedFrameCount

        val rejected = governor.accept(0f, 1f, 1f, 1f, budget) // non-positive red gain

        assertNull(rejected)
        assertEquals(whitePointBefore, governor.stabilizedWhitePoint)
        assertEquals(frameCountBefore, governor.stabilizedFrameCount)
    }

    @Test
    fun `a zero budget hard-locks the white point after the first frame`() {
        val governor = ColorDriftGovernor()
        governor.accept(1f, 1f, 1f, 1f, 0f)
        val locked = governor.stabilizedWhitePoint
        val result = governor.accept(2f, 1f, 1f, 1f, 0f)

        assertNotNull(result)
        assertTrue(result!!.clamped)
        assertEquals(0f, result.appliedDriftUv, 1e-7f)
        assertEquals(locked, governor.stabilizedWhitePoint)
    }

    @Test
    fun `reset clears all temporal state`() {
        val governor = ColorDriftGovernor()
        governor.accept(1f, 1f, 1f, 1f, budget)
        governor.accept(1f, 1f, 1f, 3f, budget)

        governor.reset()

        assertNull(governor.stabilizedWhitePoint)
        assertNull(governor.lastResult)
        assertEquals(0L, governor.stabilizedFrameCount)
        assertEquals(0L, governor.clampedFrameCount)
        assertFalse(governor.lastFrameClamped)
    }

    @Test
    fun `stabilized gains are unavailable before the first frame and available after`() {
        val governor = ColorDriftGovernor()
        assertNull(governor.stabilizedGains())

        governor.accept(1.5f, 1f, 1f, 1.5f, budget)
        val gains = governor.stabilizedGains()

        assertNotNull(gains)
        assertEquals(4, gains!!.size)
        assertEquals(1f, gains[1], 1e-6f) // green-normalized
        assertEquals(1f, gains[2], 1e-6f)
    }
}
