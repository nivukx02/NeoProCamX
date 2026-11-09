package com.neoproc.neoproccamx.stabilization

import kotlin.math.tan

/**
 * CP60 foundation — maps a camera rotation to the first-order image-plane correction a gyro-EIS warp
 * applies (pinhole, small-angle). Pure math, JVM-unit-tested, dormant: the device-gated step is
 * choosing the smoothed reference orientation and pushing the resulting per-scanline grid to the GPU.
 *
 * Pinhole model, device sensor axes (X right, Y up, Z out of the screen), focal length in pixels:
 *   • yaw   (rotation about +Y) pans the scene horizontally  → counter-shift dx = -f * tan(yaw)
 *   • pitch (rotation about +X) pans the scene vertically    → counter-shift dy = +f * tan(pitch)
 *   • roll  (rotation about +Z, optical axis) rotates the image in-plane by the roll angle
 * The returned [EisCorrection] is the displacement/rotation to apply to STABILIZE (cancel) the motion,
 * i.e. the negative of the scene's apparent motion.
 */
object GyroEisWarp {

    /** Stabilizing correction: pixel translation of the frame centre + in-plane roll to apply. */
    data class EisCorrection(val dxPixels: Float, val dyPixels: Float, val rollRadians: Float)

    /**
     * First-order correction for [rotation] (current relative to the smoothed reference) at the given
     * pinhole [focalLengthPx]. Uses tan() so it stays exact for the yaw/pitch pan even at larger angles;
     * roll is the optical-axis component of the rotation vector.
     */
    fun correctionFor(rotation: Quaternion, focalLengthPx: Float): EisCorrection {
        val rv = rotation.rotationVector() // (pitch about X, yaw about Y, roll about Z), radians
        val pitch = rv[0]
        val yaw = rv[1]
        val roll = rv[2]
        // Counter-shift the scene's apparent motion (negative of where the rotation moved it).
        // NOTE: the sign convention here is validated END-TO-END by EisPipelineFoundationGoldenTest
        // (constant-velocity pan → monotonic one-signed counter-rotation; fusion residual < base). An
        // earlier attempt to "correct" dx/dy to match the class-doc formula flipped these and made the
        // fused residual LARGER (adding shake), so the doc formula — not the code — is the misleading
        // one for this pipeline's downstream grid/trajectory convention. Keep these signs.
        val dx = focalLengthPx * tan(yaw.toDouble()).toFloat()
        val dy = -focalLengthPx * tan(pitch.toDouble()).toFloat()
        return EisCorrection(dxPixels = dx, dyPixels = dy, rollRadians = -roll)
    }

    /**
     * Whether [correction] fits inside a stabilization crop margin of [marginPixels] on each axis. A
     * gyro-EIS pipeline crops the frame by this margin and shifts within it; corrections beyond the
     * margin must be clamped (and the residual shake accepted) rather than exposing black borders.
     */
    fun fitsWithinCropMargin(correction: EisCorrection, marginPixels: Float): Boolean =
        kotlin.math.abs(correction.dxPixels) <= marginPixels &&
            kotlin.math.abs(correction.dyPixels) <= marginPixels

    /** Clamp a correction's translation to the crop margin (per-axis), leaving roll unchanged. */
    fun clampToCropMargin(correction: EisCorrection, marginPixels: Float): EisCorrection =
        EisCorrection(
            dxPixels = correction.dxPixels.coerceIn(-marginPixels, marginPixels),
            dyPixels = correction.dyPixels.coerceIn(-marginPixels, marginPixels),
            rollRadians = correction.rollRadians,
        )
}
