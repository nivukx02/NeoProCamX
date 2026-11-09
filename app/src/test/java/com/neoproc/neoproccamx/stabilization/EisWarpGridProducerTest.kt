package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EisWarpGridProducerTest {

    private val rows = 8
    private val rowReadoutNs = 200_000L // 200 µs/row
    private val focalLengthPx = 1000f
    private val cropMargin = 24f
    private val centerX = 960f
    private val centerY = 540f

    private fun yawStream(rateRadPerSec: Float): List<CameraMotionTelemetry> =
        (0..20).map { i ->
            CameraMotionTelemetry(
                timestampNs = i * 100_000L, // every 100 µs, spans [0, 2 ms] (covers the 1.6 ms row window)
                oisShiftXPixels = 0f,
                oisShiftYPixels = 0f,
                gyroXRadPerSec = 0f,
                gyroYRadPerSec = rateRadPerSec, // yaw about +Y -> horizontal skew
                gyroZRadPerSec = 0f,
            )
        }

    private fun smoothedFrameWithResidualYaw(yawRad: Float): OrientationTrajectorySmoother.SmoothedFrame =
        OrientationTrajectorySmoother.SmoothedFrame(
            timestampNs = 0L,
            raw = Quaternion.IDENTITY,
            smoothed = Quaternion.IDENTITY,
            residual = Quaternion.fromAxisAngle(0f, 1f, 0f, yawRad),
        )

    private fun produce(
        mode: StabilizationModePolicy.StabilizationMode,
        yawResidualRad: Float = 0.02f,
        margin: Float = cropMargin,
        yawRate: Float = 0.5f,
    ): EisWarpGrid = EisWarpGridProducer.produce(
        mode = mode,
        smoothedFrame = smoothedFrameWithResidualYaw(yawResidualRad),
        lockedReference = Quaternion.IDENTITY,
        focalLengthPx = focalLengthPx,
        cropMarginPixels = margin,
        samples = yawStream(yawRate),
        frameStartNs = 0L,
        rowReadoutNs = rowReadoutNs,
        rows = rows,
        referenceRow = rows / 2,
        oisShiftXPixels = 0f,
        oisShiftYPixels = 0f,
        centerX = centerX,
        centerY = centerY,
    )

    @Test
    fun offModeProducesIdentityGrid() {
        // No-downgrade default: EIS OFF -> an all-zero grid -> the shader resamples to identity.
        val grid = produce(StabilizationModePolicy.StabilizationMode.OFF)
        assertEquals(rows, grid.rows)
        assertEquals(rows, grid.params.gridRows)
        assertTrue("OFF mode must produce an identity grid", grid.isIdentity)
        grid.packGridRgba().forEachIndexed { i, v -> if (i % 4 != 3) assertEquals(0f, v, 0f) }
    }

    @Test
    fun everyRowStaysWithinCropMargin() {
        // A large yaw drives the global correction well past the crop margin; every row must still clamp
        // within +-margin so the shader can never reveal a black border (impossible-by-construction).
        val grid = produce(StabilizationModePolicy.StabilizationMode.STANDARD, yawResidualRad = 0.2f, yawRate = 5f)
        assertEquals(rows, grid.perRow.size)
        grid.perRow.forEachIndexed { i, c ->
            assertTrue("row $i dx exceeds crop margin: ${c.dxPixels}", abs(c.dxPixels) <= cropMargin + 1e-3f)
            assertTrue("row $i dy exceeds crop margin: ${c.dyPixels}", abs(c.dyPixels) <= cropMargin + 1e-3f)
        }
    }

    @Test
    fun motionProducesNonIdentityGridThroughTheFullChain() {
        // STANDARD with real motion must drive a non-zero grid (proves smoother->RS->OIS->mode wiring runs).
        val grid = produce(StabilizationModePolicy.StabilizationMode.STANDARD, yawResidualRad = 0.02f, margin = 200f, yawRate = 1f)
        assertFalse("a moving STANDARD frame must not be the identity grid", grid.isIdentity)
    }

    @Test
    fun packGridRgbaLayoutMatchesPerRow() {
        val grid = produce(StabilizationModePolicy.StabilizationMode.STANDARD, margin = 200f)
        val packed = grid.packGridRgba()
        assertEquals(grid.rows * 4, packed.size)
        grid.perRow.forEachIndexed { i, c ->
            assertEquals(c.dxPixels, packed[i * 4], 0f)
            assertEquals(c.dyPixels, packed[i * 4 + 1], 0f)
            assertEquals(c.rollRadians, packed[i * 4 + 2], 0f)
            assertEquals(1f, packed[i * 4 + 3], 0f)
        }
    }
}
