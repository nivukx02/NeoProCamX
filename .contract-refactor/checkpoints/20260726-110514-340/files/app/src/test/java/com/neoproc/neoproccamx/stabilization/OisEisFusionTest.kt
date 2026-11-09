package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class OisEisFusionTest {

    private val gyro = GyroEisWarp.EisCorrection(dxPixels = 10f, dyPixels = -6f, rollRadians = 0.02f)

    @Test
    fun oisFullyAbsorbingMotionLeavesNoResidual() {
        val fused = OisEisFusion.fuse(gyro, oisShiftXPixels = 10f, oisShiftYPixels = -6f)
        assertEquals(0f, fused.dxPixels, 1e-5f)
        assertEquals(0f, fused.dyPixels, 1e-5f)
        // Roll is rotational; OIS (translational) does not absorb it.
        assertEquals(0.02f, fused.rollRadians, 1e-5f)
    }

    @Test
    fun noOisLeavesCorrectionUnchanged() {
        val fused = OisEisFusion.fuse(gyro, oisShiftXPixels = 0f, oisShiftYPixels = 0f)
        assertEquals(gyro.dxPixels, fused.dxPixels, 0f)
        assertEquals(gyro.dyPixels, fused.dyPixels, 0f)
        assertEquals(gyro.rollRadians, fused.rollRadians, 0f)
    }

    @Test
    fun partialOisLeavesPartialResidual() {
        val fused = OisEisFusion.fuse(gyro, oisShiftXPixels = 4f, oisShiftYPixels = -2f)
        assertEquals(6f, fused.dxPixels, 1e-5f)
        assertEquals(-4f, fused.dyPixels, 1e-5f)
    }

    @Test
    fun fusesAgainstTelemetrySampleShift() {
        val sample = CameraMotionTelemetry(
            timestampNs = 0L,
            oisShiftXPixels = 10f,
            oisShiftYPixels = -6f,
            gyroXRadPerSec = 0f,
            gyroYRadPerSec = 0f,
            gyroZRadPerSec = 0f,
        )
        val fused = OisEisFusion.fuse(gyro, sample)
        assertEquals(0f, fused.dxPixels, 1e-5f)
        assertEquals(0f, fused.dyPixels, 1e-5f)
    }
}
