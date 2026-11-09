package com.neoproc.neoproccamx.stabilization

/**
 * s3-f06 / CP64 — the honest availability gate for the gyro-EIS stabilization modes.
 *
 * OFF / STANDARD / LOCKED are all selectable (they need only the integrated gyro path); the default is OFF
 * so today's pixels are unchanged until the owner enables EIS device-side. HORIZON-LEVEL is flagged
 * `NOT_AVAILABLE`: levelling the horizon needs ABSOLUTE roll from accelerometer / gravity fusion, which the
 * gyro-only [com.neoproc.neoproccamx.camera.CameraMotionTelemetry] stream does not carry — so it is never
 * synthesised from gyro-only data (the OmegaCapabilityGate honesty contract). It lands available only once a
 * real gravity-fusion source exists (DEVICE-GATED).
 */
object EisModeAvailability {

    enum class Availability { AVAILABLE, NOT_AVAILABLE }

    /** The no-downgrade default: stabilization is OFF until the owner enables it device-side. */
    val DEFAULT_MODE = StabilizationModePolicy.StabilizationMode.OFF

    /** The gyro-EIS modes selectable in the UI today (all driven by the integrated gyro path). */
    val SELECTABLE_MODES: List<StabilizationModePolicy.StabilizationMode> =
        StabilizationModePolicy.StabilizationMode.entries.toList()

    /**
     * Horizon-levelling availability. Always [Availability.NOT_AVAILABLE] on gyro-only telemetry: it requires
     * absolute roll from gravity fusion this stream lacks. Never synthesised — honest gate.
     */
    fun horizonLevel(hasGravityFusion: Boolean = false): Availability =
        if (hasGravityFusion) Availability.AVAILABLE else Availability.NOT_AVAILABLE
}
