package com.neoproc.neoproccamx.pipeline

import kotlin.math.pow

/**
 * RawExposureNormalizationPolicy — maps a black-to-white normalized RAW sample to scene-linear
 * relative exposure for the NeoLog encoder.
 *
 * RAW sensor data is GENUINELY scene-linear (unlike a display-referred preview), so the normalized
 * value (0 = black level, 1 = sensor saturation) is used DIRECTLY as scene-linear relative exposure:
 * 18% of the range → 0.18 → NeoLogCurve GRAY_18_CODE (0.50), saturation → 1.0 → NeoLog "white". This
 * deliberately AVOIDS the display-0..1-as-linear mistake (that bug applied a log curve to
 * display-referred pixels; RAW is already linear, so no de-gamma is applied here). An
 * exposure-compensation control scales the signal in photographic stops for the converter UI.
 */
object RawExposureNormalizationPolicy {

    const val DEFAULT_EXPOSURE_STOPS = 0f

    /** Linear multiplier for an exposure compensation in stops (2^stops). */
    fun exposureScale(stops: Float): Float = 2.0.pow(stops.toDouble()).toFloat()

    /**
     * Black-subtract + normalize a raw sample to [0,∞) scene-linear (applied before white-balance and
     * the colour matrix). Clamped at zero so a below-black sample never produces a negative signal.
     */
    fun sceneLinear(sample: Int, blackLevel: Int, whiteLevel: Int): Float {
        val range = (whiteLevel - blackLevel).coerceAtLeast(1)
        return ((sample - blackLevel).toFloat() / range.toFloat()).coerceAtLeast(0f)
    }
}
