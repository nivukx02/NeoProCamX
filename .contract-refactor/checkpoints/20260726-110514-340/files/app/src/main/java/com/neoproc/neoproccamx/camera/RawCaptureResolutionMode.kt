package com.neoproc.neoproccamx.camera

/**
 * User/auto selection for the RAW capture resolution tier.
 *
 * The RAW recorder can capture at two physical tiers:
 *  - [MAXIMUM_SENSOR]     — the sensor's full un-binned pixel count (48/50/108/200MP class),
 *                            reached only via SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION as a low-fps burst.
 *  - [FULL_ACTIVE_ARRAY]  — the full active pixel array streaming RAW path (the always-available tier).
 *
 * [AUTO] favours [MAXIMUM_SENSOR] and falls back to [FULL_ACTIVE_ARRAY] automatically when the device
 * does not expose the max-res sensor mode or when a max-res burst is not currently viable
 * (thermal / storage / memory). See [RawCaptureResolutionModePolicy].
 */
enum class RawCaptureResolutionMode {
    /** Pick the best viable tier automatically (default). */
    AUTO,

    /** Prefer the sensor's maximum un-binned resolution via a max-res burst. */
    MAXIMUM_SENSOR,

    /** Force the full active pixel array streaming RAW path. */
    FULL_ACTIVE_ARRAY;

    companion object {
        val DEFAULT: RawCaptureResolutionMode = AUTO

        fun fromName(name: String?): RawCaptureResolutionMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}
