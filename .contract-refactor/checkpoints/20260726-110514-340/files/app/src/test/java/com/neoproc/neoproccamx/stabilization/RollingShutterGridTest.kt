package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingShutterGridTest {

    private fun stream(gx: Float, gy: Float, gz: Float, totalNs: Long, steps: Int): List<CameraMotionTelemetry> =
        (0..steps).map { i ->
            CameraMotionTelemetry(
                timestampNs = (i.toLong() * totalNs / steps),
                oisShiftXPixels = 0f,
                oisShiftYPixels = 0f,
                gyroXRadPerSec = gx,
                gyroYRadPerSec = gy,
                gyroZRadPerSec = gz,
            )
        }

    private val rows = 64
    private val rowReadoutNs = 200_000L // 0.2 ms/row -> ~12.8 ms frame readout
    private val frameStartNs = 1_000_000L

    @Test
    fun zeroMotionYieldsZeroGrid() {
        val samples = stream(0f, 0f, 0f, totalNs = 30_000_000L, steps = 150)
        val grid = RollingShutterGrid.perRowCorrections(samples, frameStartNs, rowReadoutNs, rows, referenceRow = rows / 2, focalLengthPx = 1500f)
        assertEquals(rows, grid.size)
        for (c in grid) {
            assertEquals(0f, c.dxPixels, 1e-3f)
            assertEquals(0f, c.dyPixels, 1e-3f)
        }
    }

    @Test
    fun constantYawProducesLinearHorizontalSkew() {
        // Constant yaw over a window covering the whole frame readout.
        val samples = stream(0f, 0.6f, 0f, totalNs = 30_000_000L, steps = 300)
        val center = rows / 2
        val grid = RollingShutterGrid.perRowCorrections(samples, frameStartNs, rowReadoutNs, rows, referenceRow = center, focalLengthPx = 1500f)

        // Centre row pivots about itself -> ~0 correction.
        assertEquals(0f, grid[center].dxPixels, 0.2f)
        // Top and bottom rows are corrected in opposite directions (skew), roughly symmetric.
        val top = grid[0].dxPixels
        val bottom = grid[rows - 1].dxPixels
        assertTrue("top and bottom must skew oppositely: top=$top bottom=$bottom", top * bottom < 0f)
        assertEquals("skew should be ~symmetric about centre", abs(top), abs(bottom), 0.5f)
        // Monotonic (non-decreasing) in row index: later rows are exposed later, accumulating more
        // +yaw relative to the centre, so dx runs from negative (top) through 0 (centre) to positive.
        for (y in 1 until rows) {
            assertTrue("dx must be monotonic non-decreasing in row", grid[y].dxPixels >= grid[y - 1].dxPixels - 1e-3f)
        }
        // Pure yaw -> negligible vertical correction.
        for (c in grid) assertEquals(0f, c.dyPixels, 1e-2f)
    }
}
