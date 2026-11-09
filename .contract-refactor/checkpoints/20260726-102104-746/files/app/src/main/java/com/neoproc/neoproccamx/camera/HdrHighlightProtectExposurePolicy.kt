package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

/**
 * NDR10+/NIVUKX highlight protection at the CAPTURE stage.
 *
 * The whole point of an HDR mode is to record BOTH the bright (highlight) detail AND the dark
 * (shadow) detail. A tone curve downstream can only shape detail the sensor actually captured — if
 * auto-exposure meters for the midtones (the SDR habit), the highlights are already clipped to pure
 * white at the photosite and no PQ curve can bring them back. So HDR must be exposed to PROTECT the
 * highlights, letting the tone operator lift the (deliberately darker) midtones/shadows back up.
 *
 * This policy converts a small negative EV bias (expose a touch darker → keep highlight headroom)
 * into a Camera2 `CONTROL_AE_EXPOSURE_COMPENSATION` index, on top of the user's own EV dial. It is
 * applied ONLY in HDR modes and ONLY on the auto-exposure path (user manual sensor is untouched).
 *
 * The bias is intentionally modest (about 2/3 of a stop): enough to stop the "blown to white"
 * highlights the user reported, small enough that the graded result is not visibly dark once the
 * NDR/NIVUKX tone operator has mapped the signal to display nits.
 */
internal object HdrHighlightProtectExposurePolicy {

    /**
     * Baseline highlight-protect bias, in stops (negative = darker to protect highlights). Chosen as
     * ~2/3 stop: a standard "protect-highlights, recover-in-grade" HDR posture.
     */
    const val DEFAULT_HIGHLIGHT_PROTECT_STOPS = -0.67f

    /**
     * Resolve the exposure-compensation INDEX to request for an HDR auto-exposure frame.
     *
     * @param baseEvIndex the user's current EV dial index (already the neutral value at EV 0).
     * @param evStepStops stops per compensation index step (device `CONTROL_AE_COMPENSATION_STEP`).
     * @param evLowerIndex device compensation range lower bound (index units).
     * @param evUpperIndex device compensation range upper bound (index units).
     * @param highlightProtectStops the extra bias in stops to fold in (default
     *   [DEFAULT_HIGHLIGHT_PROTECT_STOPS]); pass 0f to disable (returns the clamped base index).
     * @return the compensation index to set, clamped to the device range.
     */
    fun resolveExposureCompensationIndex(
        baseEvIndex: Int,
        evStepStops: Float,
        evLowerIndex: Int,
        evUpperIndex: Int,
        highlightProtectStops: Float = DEFAULT_HIGHLIGHT_PROTECT_STOPS
    ): Int {
        val lower = minOf(evLowerIndex, evUpperIndex)
        val upper = maxOf(evLowerIndex, evUpperIndex)
        val clampedBase = baseEvIndex.coerceIn(lower, upper)
        if (highlightProtectStops == 0f || evStepStops <= 0f) return clampedBase
        // Convert the stop bias into whole index steps (rounding toward the nearest representable
        // step so a device with a coarse 1/2-stop step still applies a sensible protection).
        val biasSteps = (highlightProtectStops / evStepStops).roundToInt()
        return (clampedBase + biasSteps).coerceIn(lower, upper)
    }
}
