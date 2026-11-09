package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * CP62 foundation — per-scanline rolling-shutter correction grid.
 *
 * A CMOS sensor exposes each row at a slightly different instant (line readout), so under camera
 * rotation each row sees a different orientation — producing the rolling-shutter skew/jello a single
 * global warp cannot fix. Given the gyro stream and the frame's per-row exposure timing, this computes
 * the orientation of each row relative to a reference row and maps it to a per-row [GyroEisWarp]
 * correction. Pure, deterministic, JVM-unit-tested; dormant (not GPU-wired) so no live pixel changes.
 *
 * Device-gated follow-up: the actual line-readout time and frame exposure timestamp come from the
 * camera (SENSOR_ROLLING_SHUTTER_SKEW / SENSOR_TIMESTAMP) and must be clock-reconciled with the gyro.
 */
object RollingShutterGrid {

    /**
     * One [GyroEisWarp.EisCorrection] per row [0, rows). Row exposure time = [frameStartNs] +
     * row * [rowReadoutNs]; corrections are relative to [referenceRow] (typically the centre row, so
     * the frame pivots about its middle). Zero camera motion ⇒ all-zero corrections.
     */
    fun perRowCorrections(
        samples: List<CameraMotionTelemetry>,
        frameStartNs: Long,
        rowReadoutNs: Long,
        rows: Int,
        referenceRow: Int,
        focalLengthPx: Float,
    ): List<GyroEisWarp.EisCorrection> {
        require(rows > 0) { "rows must be positive" }
        val tRef = frameStartNs + referenceRow.toLong() * rowReadoutNs
        return (0 until rows).map { y ->
            val tRow = frameStartNs + y.toLong() * rowReadoutNs
            val rowRelRef = when {
                tRow > tRef -> GyroOrientationIntegrator.rotationBetween(samples, tRef, tRow)
                tRow < tRef -> GyroOrientationIntegrator.rotationBetween(samples, tRow, tRef).conjugate()
                else -> Quaternion.IDENTITY
            }
            GyroEisWarp.correctionFor(rowRelRef, focalLengthPx)
        }
    }
}
