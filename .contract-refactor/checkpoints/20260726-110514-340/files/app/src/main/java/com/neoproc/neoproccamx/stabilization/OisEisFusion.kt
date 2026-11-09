package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * CP63 foundation — fuse optical (OIS) and electronic (gyro-EIS) stabilization so motion the lens
 * already physically absorbed is not corrected a second time electronically.
 *
 * The gyro-derived [GyroEisWarp.EisCorrection] assumes the sensor saw the full rotation. But when OIS
 * is active the lens has already shifted the image by `oisShift*Pixels` (reported per sample in
 * [CameraMotionTelemetry]); double-counting that would over-stabilize and swim. The residual EIS
 * correction is therefore the gyro correction minus the OIS-absorbed shift. Pure, JVM-unit-tested,
 * dormant. The sign/scale of the OIS shift relative to the EIS axes is device-specific and is the
 * device-gated calibration step; the fusion arithmetic is what is locked here.
 */
object OisEisFusion {

    /** Subtract the OIS-absorbed pixel shift from the gyro EIS correction (roll is OIS-independent). */
    fun fuse(
        gyroCorrection: GyroEisWarp.EisCorrection,
        oisShiftXPixels: Float,
        oisShiftYPixels: Float,
    ): GyroEisWarp.EisCorrection = GyroEisWarp.EisCorrection(
        dxPixels = gyroCorrection.dxPixels - oisShiftXPixels,
        dyPixels = gyroCorrection.dyPixels - oisShiftYPixels,
        rollRadians = gyroCorrection.rollRadians,
    )

    /** Convenience: fuse against the OIS shift carried by a telemetry sample. */
    fun fuse(gyroCorrection: GyroEisWarp.EisCorrection, sample: CameraMotionTelemetry): GyroEisWarp.EisCorrection =
        fuse(gyroCorrection, sample.oisShiftXPixels, sample.oisShiftYPixels)
}
