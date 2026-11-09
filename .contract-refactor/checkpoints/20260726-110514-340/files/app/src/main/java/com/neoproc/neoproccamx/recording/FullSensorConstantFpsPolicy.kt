package com.neoproc.neoproccamx.recording

/**
 * Pure decision math for [FullSensorConstantFpsFlag] — full-sensor recording pinned to a constant 25 fps.
 *
 * "Full-sensor" is the ≥12 MP tier (4096×3072 = 12,582,912 px); the 4K-class sizes 3840×2160 (8.29 MP),
 * 4096×2304 (9.44 MP) and 4096×2160 (8.85 MP) all sit below the gate and are never affected. Deterministic
 * and flag-free at the [isFullSensor] level so it is unit-testable independent of the process-global flag;
 * [engaged]/[useConstantMuxCadence] fold in the reversible flag for the live call sites.
 */
internal object FullSensorConstantFpsPolicy {

    /** The one true full-sensor cadence — constant, no 25↔30 wobble. */
    const val CONSTANT_FPS = 25

    /** Full-sensor pixel gate (matches the LIGHT-lane full-sensor threshold in the fps policies). */
    const val FULL_SENSOR_PIXEL_THRESHOLD = 12_000_000L

    fun isFullSensor(width: Int, height: Int): Boolean =
        width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong() >= FULL_SENSOR_PIXEL_THRESHOLD

    /** True when the reversible flag is ON and the encoder size is the full-sensor tier. */
    fun engaged(width: Int, height: Int): Boolean =
        FullSensorConstantFpsFlag.enabled && isFullSensor(width, height)

    /**
     * The full-sensor cadence a LIGHT-lane call site should use: the constant 25 when the flag is ON, or the
     * caller's own LIGHT-lane lift ([lightLaneFps], normally 30) when OFF so flag-OFF stays byte-identical.
     * The caller has already confirmed the frame IS full-sensor, so this only consults the flag.
     */
    fun lightLaneFullSensorFps(lightLaneFps: Int): Int =
        if (FullSensorConstantFpsFlag.enabled) CONSTANT_FPS else lightLaneFps

    /** True when the full-sensor CFR container cadence should be used for this encoder size. */
    fun useConstantMuxCadence(width: Int, height: Int): Boolean = engaged(width, height)
}
