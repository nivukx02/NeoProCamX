package com.neoproc.neoproccamx.camera

/**
 * Per-frame consumer of the color-drift budget that [VideoColorStabilityPolicy] publishes.
 *
 * Historically [VideoColorStabilityPlan.maxFrameToFrameColorDriftUv] was resolved every frame
 * but only ever interpolated into a diagnostic log line — nothing measured against it, so a
 * panning or relit scene could let the HAL's auto-white-balance estimate hunt frame to frame
 * with no in-app guardrail. This governor closes that gap:
 *
 *  1. Each frame it converts the HAL's measured RGGB gains to u'v' ([WhiteBalanceChromaticity]).
 *  2. It runs that measurement through [VideoColorTemporalStabilityPolicy] against the previous
 *     stabilized white point, using the active mode's budget.
 *  3. It tracks the stabilized white point, whether this frame's motion had to be clamped to the
 *     budget (i.e. AWB hunting was detected), and how often that has happened in the shot.
 *
 * The stabilized white point is exposed both as u'v' and as ready-to-apply RGGB gains, so a
 * downstream stage can bound the on-device color when that path is enabled. The governor itself
 * is pure and side-effect free, so it is fully unit-testable off-device.
 *
 * Thread confinement: instances are not synchronized; drive a single governor from one thread
 * (the capture-result callback thread).
 */
internal class ColorDriftGovernor(
    private val smoothingFactor: Float = VideoColorTemporalStabilityPolicy.DEFAULT_SMOOTHING_FACTOR
) {

    /** Last stabilized white point, or `null` before the first usable measurement. */
    var stabilizedWhitePoint: ColorChromaticity? = null
        private set

    /** Full result of the most recent [accept] call that produced a stabilization step. */
    var lastResult: TemporalColorStabilizationResult? = null
        private set

    /** Number of frames whose requested motion exceeded the budget and was clamped this shot. */
    var clampedFrameCount: Long = 0L
        private set

    /** Total number of frames the governor has stabilized this shot. */
    var stabilizedFrameCount: Long = 0L
        private set

    /** True when the most recent frame's drift had to be clamped (AWB hunting suppressed). */
    val lastFrameClamped: Boolean
        get() = lastResult?.clamped == true

    /**
     * Clears all temporal state. Call when the capture session restarts or the scene context
     * changes discontinuously, so a stale white point from a previous shot cannot trigger a
     * spurious first-frame clamp.
     */
    fun reset() {
        stabilizedWhitePoint = null
        lastResult = null
        clampedFrameCount = 0L
        stabilizedFrameCount = 0L
    }

    /**
     * P1.8: seed the running white point without a measurement, so a same-sensor session rebuild (a
     * mode toggle — SDR↔HDR, resolution change) carries the previous shot's white point forward and
     * the preview does not re-converge-flash. This is safe: the next [accept] merely slew-limits the
     * fresh measurement *toward* the seed within the mode budget, so a seed that turns out stale
     * self-corrects at the bounded drift rate rather than causing a jump. Leaves [lastResult] null so
     * no seeded white point is actively applied before a real measurement clamps.
     */
    fun seed(whitePoint: ColorChromaticity) {
        stabilizedWhitePoint = whitePoint
        lastResult = null
        clampedFrameCount = 0L
        stabilizedFrameCount = 0L
    }

    /**
     * Stabilizes one frame's measured RGGB gains against the running white point under the given
     * per-frame u'v' budget.
     *
     * @return the stabilization result, or `null` if the gains were unusable (non-finite /
     *   non-positive), in which case the temporal state is left untouched.
     */
    fun accept(
        red: Float,
        greenEven: Float,
        greenOdd: Float,
        blue: Float,
        maxFrameToFrameColorDriftUv: Float,
        /**
         * s2-f04 / CP47: illuminant-estimate confidence in [0,1] (from the SNCE fusion, s2-f10/f05).
         * Scales the per-frame ceiling via [confidenceCeilingScale]: full confidence ⇒ the full mode
         * budget (today); low confidence ⇒ a tighter ceiling so the governor moves less and never
         * chases a noisy estimate. Default 1f reproduces today's budget bit-for-bit.
         */
        confidence: Float = 1f
    ): TemporalColorStabilizationResult? {
        val measured = WhiteBalanceChromaticity.rggbGainsToUv(red, greenEven, greenOdd, blue) ?: return null
        val effectiveBudget = maxFrameToFrameColorDriftUv * confidenceCeilingScale(confidence)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = stabilizedWhitePoint,
            measured = measured,
            maxFrameToFrameColorDriftUv = effectiveBudget,
            smoothingFactor = smoothingFactor
        )
        stabilizedWhitePoint = result.stabilized
        lastResult = result
        stabilizedFrameCount++
        if (result.clamped) clampedFrameCount++
        return result
    }

    /**
     * The current stabilized white point expressed as green-normalized RGGB gains
     * `[r, gEven, gOdd, b]`, or `null` before the first usable measurement.
     */
    fun stabilizedGains(): FloatArray? =
        stabilizedWhitePoint?.let { WhiteBalanceChromaticity.uvToRggbGains(it) }

    companion object {
        /**
         * Lower bound of the CP47 confidence ceiling: even a zero-confidence frame keeps 25 % of the
         * mode budget, so the governor still tracks a genuine illuminant change slowly rather than
         * freezing (which would lag forever). Bounded in [floor, 1], monotone in confidence.
         */
        const val CEILING_CONFIDENCE_FLOOR = 0.25f

        /** s2-f04 / CP47: per-frame ceiling scale; 1.0 at full confidence (today), shrinking
         *  monotonically toward [CEILING_CONFIDENCE_FLOOR] as confidence drops. */
        fun confidenceCeilingScale(confidence: Float): Float {
            val c = confidence.coerceIn(0f, 1f)
            return CEILING_CONFIDENCE_FLOOR + (1f - CEILING_CONFIDENCE_FLOOR) * c
        }
    }
}
