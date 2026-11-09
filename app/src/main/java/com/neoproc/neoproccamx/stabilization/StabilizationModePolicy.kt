package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.stabilization.OrientationTrajectorySmoother.SmoothedFrame

/**
 * CP64 foundation — selects how the gyro-EIS stack turns a frame's orientation into the final, crop-
 * bounded image correction. Pure policy on top of CP60-63, JVM-unit-tested, dormant (not GPU-wired),
 * so no live pixel changes.
 *
 *  • OFF       — no stabilization (identity correction).
 *  • STANDARD  — follow the smoothed virtual-camera path; correction = raw⁻¹·smoothed (from CP61).
 *  • LOCKED    — hold a fixed reference orientation (tripod-like); correction = raw⁻¹·reference,
 *                clamped to the crop margin so large drift degrades gracefully instead of exposing
 *                black borders.
 *
 * Horizon-levelling is intentionally NOT here: it needs an ABSOLUTE roll reference (accelerometer /
 * gravity fusion) that the gyro-only [com.neoproc.neoproccamx.camera.CameraMotionTelemetry] stream
 * does not carry — that is a separate device-gated sensor-fusion step.
 */
object StabilizationModePolicy {

    enum class StabilizationMode { OFF, STANDARD, LOCKED }

    /** Rotation the warp must apply (real frame → virtual camera) for the chosen [mode]. */
    fun residualRotation(
        mode: StabilizationMode,
        frame: SmoothedFrame,
        lockedReference: Quaternion,
    ): Quaternion = when (mode) {
        StabilizationMode.OFF -> Quaternion.IDENTITY
        StabilizationMode.STANDARD -> frame.residual
        StabilizationMode.LOCKED -> (frame.raw.conjugate() * lockedReference).normalized()
    }

    /** Final crop-bounded pixel correction for the frame under [mode]. */
    fun correction(
        mode: StabilizationMode,
        frame: SmoothedFrame,
        lockedReference: Quaternion,
        focalLengthPx: Float,
        cropMarginPx: Float,
    ): GyroEisWarp.EisCorrection {
        if (mode == StabilizationMode.OFF) return GyroEisWarp.EisCorrection(0f, 0f, 0f)
        val raw = GyroEisWarp.correctionFor(residualRotation(mode, frame, lockedReference), focalLengthPx)
        return GyroEisWarp.clampToCropMargin(raw, cropMarginPx)
    }
}
