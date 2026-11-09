package com.neoproc.neoproccamx.stabilization

import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroEisWarpTest {

    private val focal = 1000f
    private val tol = 1e-2f

    @Test
    fun identityRotationNeedsNoCorrection() {
        val c = GyroEisWarp.correctionFor(Quaternion.IDENTITY, focal)
        assertEquals(0f, c.dxPixels, tol)
        assertEquals(0f, c.dyPixels, tol)
        assertEquals(0f, c.rollRadians, tol)
    }

    @Test
    fun pureYawProducesHorizontalCounterShift() {
        val yaw = 0.02f
        val c = GyroEisWarp.correctionFor(Quaternion.fromAxisAngle(0f, 1f, 0f, yaw), focal)
        // Sign convention validated end-to-end by EisPipelineFoundationGoldenTest (do not flip).
        assertEquals(focal * tan(yaw.toDouble()).toFloat(), c.dxPixels, tol)
        assertEquals(0f, c.dyPixels, tol)
        assertEquals(0f, c.rollRadians, tol)
    }

    @Test
    fun purePitchProducesVerticalCounterShift() {
        val pitch = 0.02f
        val c = GyroEisWarp.correctionFor(Quaternion.fromAxisAngle(1f, 0f, 0f, pitch), focal)
        // Sign convention validated end-to-end by EisPipelineFoundationGoldenTest (do not flip).
        assertEquals(-focal * tan(pitch.toDouble()).toFloat(), c.dyPixels, tol)
        assertEquals(0f, c.dxPixels, tol)
    }

    @Test
    fun pureRollProducesCounterRoll() {
        val roll = 0.05f
        val c = GyroEisWarp.correctionFor(Quaternion.fromAxisAngle(0f, 0f, 1f, roll), focal)
        assertEquals(-roll, c.rollRadians, tol)
        assertEquals(0f, c.dxPixels, tol)
        assertEquals(0f, c.dyPixels, tol)
    }

    @Test
    fun cropMarginGatesAndClampsLargeCorrections() {
        val big = GyroEisWarp.EisCorrection(dxPixels = 40f, dyPixels = -8f, rollRadians = 0.01f)
        assertFalse(GyroEisWarp.fitsWithinCropMargin(big, marginPixels = 24f))
        val clamped = GyroEisWarp.clampToCropMargin(big, marginPixels = 24f)
        assertEquals(24f, clamped.dxPixels, 0f)
        assertEquals(-8f, clamped.dyPixels, 0f)
        assertEquals(0.01f, clamped.rollRadians, 0f)
        assertTrue(GyroEisWarp.fitsWithinCropMargin(clamped, marginPixels = 24f))
    }
}
