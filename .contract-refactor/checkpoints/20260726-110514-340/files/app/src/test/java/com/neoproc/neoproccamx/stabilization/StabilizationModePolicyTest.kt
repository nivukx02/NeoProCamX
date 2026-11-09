package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.stabilization.OrientationTrajectorySmoother.SmoothedFrame
import com.neoproc.neoproccamx.stabilization.StabilizationModePolicy.StabilizationMode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizationModePolicyTest {

    private val focal = 1500f
    private val crop = 24f

    private fun frame(raw: Quaternion, smoothed: Quaternion) =
        SmoothedFrame(0L, raw, smoothed, (raw.conjugate() * smoothed).normalized())

    @Test
    fun offModeNeverCorrects() {
        val f = frame(Quaternion.fromAxisAngle(0f, 1f, 0f, 0.1f), Quaternion.IDENTITY)
        val c = StabilizationModePolicy.correction(StabilizationMode.OFF, f, Quaternion.IDENTITY, focal, crop)
        assertEquals(0f, c.dxPixels, 0f)
        assertEquals(0f, c.dyPixels, 0f)
        assertEquals(0f, c.rollRadians, 0f)
    }

    @Test
    fun standardModeIsZeroWhenAlreadyOnTheSmoothedPath() {
        val q = Quaternion.fromAxisAngle(0f, 1f, 0f, 0.07f)
        val c = StabilizationModePolicy.correction(StabilizationMode.STANDARD, frame(q, q), Quaternion.IDENTITY, focal, crop)
        assertEquals(0f, c.dxPixels, 1e-2f)
        assertEquals(0f, c.dyPixels, 1e-2f)
    }

    @Test
    fun lockedModeIsZeroAtTheReference() {
        val q = Quaternion.fromAxisAngle(0f, 1f, 0f, 0.12f)
        val c = StabilizationModePolicy.correction(StabilizationMode.LOCKED, frame(q, q), lockedReference = q, focal, crop)
        assertEquals(0f, c.dxPixels, 1e-2f)
        assertEquals(0f, c.dyPixels, 1e-2f)
    }

    @Test
    fun lockedModeCorrectsTowardReferenceAndStaysWithinCrop() {
        // Camera has yawed away from the locked reference (identity) by a large angle.
        val raw = Quaternion.fromAxisAngle(0f, 1f, 0f, 0.2f)
        val c = StabilizationModePolicy.correction(StabilizationMode.LOCKED, frame(raw, raw), lockedReference = Quaternion.IDENTITY, focal, crop)
        // A 0.2 rad yaw at f=1500 would demand ~300px; it must be clamped into the 24px crop margin.
        assertTrue("locked correction should be non-zero", abs(c.dxPixels) > 0f)
        assertTrue("|dx| must be clamped to crop margin", abs(c.dxPixels) <= crop + 1e-4f)
        assertTrue("|dy| must be clamped to crop margin", abs(c.dyPixels) <= crop + 1e-4f)
    }

    @Test
    fun lockedDriftsMoreThanStandardForADeviatingFrame() {
        // raw deviates from a near-identity smoothed path; LOCKED (to identity) and STANDARD differ:
        // for a small deviation both are sub-crop, but LOCKED tracks the absolute offset while STANDARD
        // only removes the deviation from the smoothed path (here equal), so STANDARD ~ 0 < LOCKED.
        val raw = Quaternion.fromAxisAngle(0f, 1f, 0f, 0.01f)
        val smoothed = raw // already on path => STANDARD residual ~ identity
        val std = StabilizationModePolicy.correction(StabilizationMode.STANDARD, frame(raw, smoothed), Quaternion.IDENTITY, focal, crop)
        val locked = StabilizationModePolicy.correction(StabilizationMode.LOCKED, frame(raw, smoothed), Quaternion.IDENTITY, focal, crop)
        assertTrue(abs(locked.dxPixels) > abs(std.dxPixels))
    }
}
