package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroGlobalMotionTermTest {

    private fun sample(tNs: Long, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f) =
        CameraMotionTelemetry(
            timestampNs = tNs,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
            gyroXRadPerSec = gx,
            gyroYRadPerSec = gy,
            gyroZRadPerSec = gz,
            source = CameraMotionTelemetry.Source.GYRO,
        )

    private fun constantStream(gx: Float, gy: Float, gz: Float, totalNs: Long, steps: Int) =
        (0..steps).map { i -> sample(i.toLong() * totalNs / steps, gx, gy, gz) }

    private val oneMs = 1_000_000L
    private val focal = 1500f
    // Constant yaw (about Y) so the global motion is a pure horizontal pan.
    private val yawStream = constantStream(0f, 0.3f, 0f, totalNs = 100 * oneMs, steps = 100)

    @Test
    fun motionProducesNonZeroScaledPixelTerm() {
        // refMid = 10 ms, frameMid = 30 ms ⇒ yaw = 0.3 × 20 ms = 0.006 rad; dx = f·tan(yaw) ≈ 9 px.
        val gm = GyroGlobalMotionTerm.between(yawStream, refMidNs = 10 * oneMs, frameMidNs = 30 * oneMs, focalLengthPx = focal)
        assertEquals(0.006f, gm.rotation.angleRad(), 1e-5f)
        assertEquals(9.0f, gm.pixel.dxPixels, 0.05f)
        assertEquals(0f, gm.pixel.dyPixels, 1e-3f)
        assertEquals(0f, gm.pixel.rollRadians, 1e-4f)
        assertTrue("global term must be non-zero under motion", abs(gm.pixel.dxPixels) > 1f)
    }

    @Test
    fun stillCameraYieldsZeroTerm() {
        val still = constantStream(0f, 0f, 0f, totalNs = 100 * oneMs, steps = 100)
        val gm = GyroGlobalMotionTerm.between(still, refMidNs = 10 * oneMs, frameMidNs = 30 * oneMs, focalLengthPx = focal)
        assertEquals(0f, gm.rotation.angleRad(), 1e-6f)
        assertEquals(0f, gm.pixel.dxPixels, 1e-4f)
        assertEquals(0f, gm.pixel.dyPixels, 1e-4f)
        assertEquals(0f, gm.pixel.rollRadians, 1e-4f)
    }

    @Test
    fun pixelTermScalesLinearlyWithFocalLength() {
        val at1500 = GyroGlobalMotionTerm.between(yawStream, 10 * oneMs, 30 * oneMs, focalLengthPx = 1500f)
        val at3000 = GyroGlobalMotionTerm.between(yawStream, 10 * oneMs, 30 * oneMs, focalLengthPx = 3000f)
        assertEquals(2f * at1500.pixel.dxPixels, at3000.pixel.dxPixels, 1e-3f)
    }

    @Test
    fun frameBeforeReferenceFlipsSign() {
        val forward = GyroGlobalMotionTerm.between(yawStream, refMidNs = 10 * oneMs, frameMidNs = 30 * oneMs, focalLengthPx = focal)
        val backward = GyroGlobalMotionTerm.between(yawStream, refMidNs = 30 * oneMs, frameMidNs = 10 * oneMs, focalLengthPx = focal)
        assertTrue(forward.pixel.dxPixels > 0f)
        assertTrue(backward.pixel.dxPixels < 0f)
        assertEquals(forward.pixel.dxPixels, -backward.pixel.dxPixels, 1e-3f)
    }

    @Test
    fun oisFusionReducesResidualUsingRealTelemetrySource() {
        val raw = GyroGlobalMotionTerm.between(yawStream, 10 * oneMs, 30 * oneMs, focalLengthPx = focal)
        // The OIS displacement comes from a real OIS-domain telemetry sample (linking the capture source).
        val oisSample = CameraMotionTelemetry(
            timestampNs = 30 * oneMs,
            oisShiftXPixels = 4f, // same sign as the pan ⇒ the lens already absorbed part of it
            oisShiftYPixels = 0f,
            gyroXRadPerSec = 0f,
            gyroYRadPerSec = 0f,
            gyroZRadPerSec = 0f,
            source = CameraMotionTelemetry.Source.OIS_RESULT,
        )
        val fused = GyroGlobalMotionTerm.betweenOisFused(
            yawStream, 10 * oneMs, 30 * oneMs, focalLengthPx = focal,
            oisShiftXPixels = oisSample.oisShiftXPixels,
            oisShiftYPixels = oisSample.oisShiftYPixels,
        )
        // Subtracting the OIS-absorbed shift reduces (never grows) the residual EIS must still apply.
        assertTrue(abs(fused.pixel.dxPixels) < abs(raw.pixel.dxPixels))
        assertEquals(raw.pixel.dxPixels - 4f, fused.pixel.dxPixels, 1e-4f)
        // Roll is OIS-independent and unchanged.
        assertEquals(raw.pixel.rollRadians, fused.pixel.rollRadians, 0f)
    }

    @Test
    fun fullyAbsorbingOisLeavesNoResidual() {
        val raw = GyroGlobalMotionTerm.between(yawStream, 10 * oneMs, 30 * oneMs, focalLengthPx = focal)
        val fused = GyroGlobalMotionTerm.betweenOisFused(
            yawStream, 10 * oneMs, 30 * oneMs, focalLengthPx = focal,
            oisShiftXPixels = raw.pixel.dxPixels, // lens absorbed the entire pan
            oisShiftYPixels = raw.pixel.dyPixels,
        )
        assertEquals(0f, fused.pixel.dxPixels, 1e-4f)
        assertEquals(0f, fused.pixel.dyPixels, 1e-4f)
    }
}
