package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale

/**
 * ManualControlUtils — High-precision utility functions for cinema-oriented manual controls.
 *
 * Provides validated, range-checked clamping and conversion utilities for all
 * NeoProCamX manual camera parameters. All functions are pure (no side effects)
 * and safe to call from any thread.
 *
 * Parameters covered:
 *  - ISO / Gain (50–102400 range for cinema cameras)
 *  - Shutter speed / Exposure time (nanoseconds)
 *  - White balance temperature (Kelvin, 2000K–15000K)
 *  - White balance tint (Green–Magenta axis)
 *  - Focus distance (diopters)
 *  - Exposure compensation (EV stops)
 *  - Shutter angle (degrees)
 *  - ND filter factor (f-stops)
 */
object ManualControlUtils {

    // ── ISO / Gain ─────────────────────────────────────────────────────────────

    /** Standard cinema ISO minimum. */
    const val ISO_MIN_CINEMA = 160
    /** Consumer camera minimum. */
    const val ISO_MIN_CONSUMER = 50
    /** Standard ISO maximum. */
    const val ISO_MAX_CINEMA = 6400
    /** Extended ISO maximum for high-sensitivity modes. */
    const val ISO_MAX_EXTENDED = 102400

    /**
     * Clamps an ISO value to the specified range.
     *
     * @param value The requested ISO value.
     * @param min   Minimum allowed ISO (default: 50).
     * @param max   Maximum allowed ISO (default: 6400 or 102400 in high-end mode).
     */
    fun clampIso(
        value: Int,
        min: Int = ISO_MIN_CONSUMER,
        max: Int = if (HighEndQualityPolicy.enabled) ISO_MAX_EXTENDED else ISO_MAX_CINEMA
    ): Int = value.coerceIn(min, max)

    /**
     * Converts an ISO value to a gain multiplier relative to a reference ISO.
     *
     * @param iso           Target ISO.
     * @param referenceIso  Reference ISO (e.g. base ISO of the camera).
     * @return Linear gain multiplier (e.g. ISO 800 / ISO 100 = 8.0×).
     */
    fun isoToLinearGain(iso: Int, referenceIso: Int = 100): Double =
        iso.toDouble() / referenceIso.coerceAtLeast(1).toDouble()

    /**
     * Converts a linear gain multiplier to the nearest integer ISO value.
     */
    fun linearGainToIso(gain: Double, referenceIso: Int = 100): Int =
        (gain * referenceIso).toInt().coerceAtLeast(ISO_MIN_CONSUMER)

    // ── Shutter Speed / Exposure Time ─────────────────────────────────────────

    /** Minimum exposure time: 1 microsecond (1000 ns) — maximum motion freeze. */
    const val EXPOSURE_NS_MIN = 1_000L              // 1 µs

    /** Maximum exposure time: 30 seconds in nanoseconds. */
    const val EXPOSURE_NS_MAX = 30_000_000_000L     // 30 s

    /** Standard 180° rule shutter for 24fps. */
    const val EXPOSURE_NS_24FPS_180DEG = 1_000_000_000L / 48L  // ~20.8 ms

    /** Standard 180° rule shutter for 30fps. */
    const val EXPOSURE_NS_30FPS_180DEG = 1_000_000_000L / 60L  // ~16.7 ms

    /**
     * Clamps an exposure time (nanoseconds) to the specified range.
     *
     * @param value The requested exposure time in nanoseconds.
     * @param min   Minimum exposure in nanoseconds.
     * @param max   Maximum exposure in nanoseconds.
     */
    fun clampExposureNs(
        value: Long,
        min: Long = EXPOSURE_NS_MIN,
        max: Long = EXPOSURE_NS_MAX
    ): Long = value.coerceIn(min, max)

    /**
     * Converts a shutter speed fraction string (e.g. "1/250") to nanoseconds.
     *
     * @param numerator   Numerator (typically 1).
     * @param denominator Denominator (e.g. 250 for 1/250s).
     */
    fun fractionToExposureNs(numerator: Int = 1, denominator: Int): Long {
        if (denominator <= 0) return EXPOSURE_NS_MIN
        return (numerator.toLong() * 1_000_000_000L / denominator.toLong()).coerceAtLeast(EXPOSURE_NS_MIN)
    }

    /**
     * Converts exposure nanoseconds to a display-friendly shutter string.
     *
     * Examples:
     *  - 16,666,667 ns → "1/60"
     *  - 500,000,000 ns → "1/2"
     *  - 1,000,000,000 ns → "1"
     *  - 2,000,000,000 ns → "2""
     */
    fun formatExposureNs(exposureNs: Long): String {
        if (exposureNs <= 0L) return "Auto"
        val seconds = exposureNs / 1_000_000_000.0
        return when {
            seconds >= 1.0 -> if (seconds % 1.0 == 0.0) {
                "${seconds.toInt()}\""
            } else {
                "${String.format(Locale.US, "%.1f", seconds)}\""
            }
            else -> {
                val denominator = (1.0 / seconds + 0.5).toInt().coerceAtLeast(1)
                "1/$denominator"
            }
        }
    }

    /**
     * Converts a shutter angle (degrees) to exposure time in nanoseconds.
     *
     * Implements the cinematographer's 180° rule:
     *   exposure = angle / (360 × fps)
     *
     * @param angleDegrees Shutter angle (1°–360°).
     * @param fps          Frame rate in frames per second.
     */
    fun shutterAngleToExposureNs(angleDegrees: Double, fps: Double): Long {
        if (fps <= 0.0 || angleDegrees <= 0.0) return EXPOSURE_NS_MIN
        return ((angleDegrees / 360.0) / fps.coerceAtLeast(1.0) * 1_000_000_000.0).toLong()
            .coerceIn(EXPOSURE_NS_MIN, EXPOSURE_NS_MAX)
    }

    /**
     * Converts an exposure time in nanoseconds to the equivalent shutter angle.
     *
     * @param exposureNs  Exposure time in nanoseconds.
     * @param fps         Frame rate in frames per second.
     * @return Shutter angle in degrees (0°–360°).
     */
    fun exposureNsToShutterAngle(exposureNs: Long, fps: Double): Double {
        if (fps <= 0.0 || exposureNs <= 0L) return 0.0
        return (exposureNs.toDouble() / 1_000_000_000.0) * fps * 360.0
    }

    // ── White Balance ─────────────────────────────────────────────────────────

    /** Extended photographic white balance range — covers tungsten to overcast sky. */
    const val WB_KELVIN_MIN_EXTENDED = 2000
    const val WB_KELVIN_MAX_EXTENDED = 15000
    const val WB_KELVIN_MIN_STANDARD = 2500
    const val WB_KELVIN_MAX_STANDARD = 8000

    // Named daylight presets (in Kelvin)
    const val WB_CANDLELIGHT    = 1900
    const val WB_TUNGSTEN       = 3200
    const val WB_FLUORESCENT    = 4200
    const val WB_DAYLIGHT       = 5500
    const val WB_CLOUD          = 6500
    const val WB_SHADE          = 7500
    const val WB_OVERCAST       = 8000

    /**
     * Clamps a white balance temperature to the specified range.
     *
     * @param value White balance in Kelvin.
     * @param min   Minimum Kelvin (default: 2000K for cinema cameras).
     * @param max   Maximum Kelvin (default: 15000K).
     */
    fun clampWhiteBalanceKelvin(
        value: Int,
        min: Int = WB_KELVIN_MIN_EXTENDED,
        max: Int = WB_KELVIN_MAX_EXTENDED
    ): Int = value.coerceIn(min, max)

    /**
     * Returns the display name for a known white balance preset.
     */
    fun wbPresetName(kelvin: Int): String? = when (kelvin) {
        in 1800..2000 -> "Candlelight"
        in 3100..3400 -> "Tungsten"
        in 4000..4500 -> "Fluorescent"
        in 5200..5800 -> "Daylight"
        in 6000..7000 -> "Cloudy"
        in 7000..8000 -> "Shade"
        else -> null
    }

    // ── Focus Distance ────────────────────────────────────────────────────────

    /** Infinity focus (zero diopters in Camera2's convention). */
    const val FOCUS_INFINITY_DIOPTERS = 0.0f
    /** Maximum close-focus (15 diopters ≈ 6.7cm minimum focus distance). */
    const val FOCUS_MACRO_DIOPTERS    = 15.0f

    /**
     * Clamps focus distance to the valid diopter range.
     *
     * @param diopters Focus distance in diopters (0.0 = infinity).
     * @param min      Minimum diopters (default: 0.0 = infinity).
     * @param max      Maximum diopters (default: 15.0 = ~6.7cm).
     */
    fun clampFocusDistance(
        diopters: Float,
        min: Float = FOCUS_INFINITY_DIOPTERS,
        max: Float = FOCUS_MACRO_DIOPTERS
    ): Float = diopters.coerceIn(min, max)

    /**
     * Converts focus distance in diopters to meters.
     * @param diopters Focus distance in diopters (>0).
     * @return Focus distance in meters (0.0 for infinity).
     */
    fun diopterToMeters(diopters: Float): Float {
        if (diopters <= 0f) return Float.POSITIVE_INFINITY
        return 1.0f / diopters
    }

    /**
     * Converts focus distance in meters to diopters.
     * @param meters Distance in meters (use Float.POSITIVE_INFINITY for infinity).
     */
    fun metersToDiopter(meters: Float): Float {
        if (meters <= 0f || meters.isInfinite()) return 0f
        return 1.0f / meters
    }

    // ── Exposure Compensation ─────────────────────────────────────────────────

    /** Extended EV range for professional HDR mastering */
    const val EV_MIN = -12.0f
    const val EV_MAX = +12.0f

    /**
     * Clamps an exposure compensation value to the valid EV range.
     *
     * @param ev  Exposure compensation in EV stops.
     * @param min Minimum EV (default: -6.0 EV).
     * @param max Maximum EV (default: +6.0 EV).
     */
    fun clampExposureCompensation(ev: Float, min: Float = EV_MIN, max: Float = EV_MAX): Float =
        ev.coerceIn(min, max)

    /**
     * Converts an EV offset to a linear multiplier for the preview LUT.
     * @param ev Exposure compensation in stops.
     * @return Linear multiplier (e.g. +1 EV = 2.0×, -1 EV = 0.5×).
     */
    fun evToLinearMultiplier(ev: Float): Double = Math.pow(2.0, ev.toDouble())

    // ── ND Filter ────────────────────────────────────────────────────────────

    /**
     * Converts ND filter factor notation (ND4, ND16, etc.) to f-stop reduction.
     *
     * @param ndFactor The ND number (e.g. 4 for ND4 = 2-stop reduction).
     * @return Number of f-stops reduction.
     */
    fun ndFactorToStops(ndFactor: Double): Double {
        if (ndFactor <= 1.0) return 0.0
        return Math.log(ndFactor) / Math.log(2.0)
    }

    /**
     * Converts f-stop reduction to the equivalent ND factor.
     * @param stops F-stops of light reduction.
     * @return ND factor (e.g. 2 stops = ND4, 3 stops = ND8).
     */
    fun stopsToNdFactor(stops: Double): Double = Math.pow(2.0, stops.coerceAtLeast(0.0))

    // ── Display Formatting ────────────────────────────────────────────────────

    /**
     * Formats an ISO value for display.
     * @param iso ISO value.
     * @return Formatted string (e.g. "ISO 800", "ISO 3200").
     */
    fun formatIso(iso: Int): String = "ISO $iso"

    /**
     * Formats an EV offset for display.
     * @param ev Exposure compensation in stops.
     * @return Formatted string (e.g. "+1.3 EV", "-0.7 EV").
     */
    fun formatEv(ev: Float): String {
        val sign = if (ev >= 0f) "+" else ""
        return "${sign}${String.format(Locale.US, "%.1f", ev)} EV"
    }

    /**
     * Formats a white balance Kelvin value for display.
     * @param kelvin White balance in Kelvin.
     * @return Formatted string (e.g. "5500K · Daylight", "3200K · Tungsten").
     */
    fun formatWhiteBalance(kelvin: Int): String {
        val preset = wbPresetName(kelvin)
        return if (preset != null) "${kelvin}K · $preset" else "${kelvin}K"
    }
}
