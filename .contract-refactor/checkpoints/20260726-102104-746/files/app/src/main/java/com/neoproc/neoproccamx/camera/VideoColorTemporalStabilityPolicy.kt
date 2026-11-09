package com.neoproc.neoproccamx.camera

import kotlin.math.sqrt

/**
 * A white-point estimate expressed in CIE 1976 u'v' chromaticity. u'v' is the space
 * the color-drift budget ([VideoColorStabilityPlan.maxFrameToFrameColorDriftUv]) is
 * measured in, so all temporal stabilization happens here rather than in RGB gains.
 */
internal data class ColorChromaticity(val u: Float, val v: Float)

/**
 * Result of a single temporal-stabilization step.
 *
 * @property stabilized the white point the pipeline should use this frame.
 * @property appliedDriftUv the actual u'v' distance moved from the previous frame.
 * @property clamped true when the requested move exceeded the drift budget and was
 *   capped (i.e. AWB hunting was actively suppressed this frame).
 * @property converged true when the stabilized point has effectively reached the
 *   measured target (no meaningful further motion pending).
 */
internal data class TemporalColorStabilizationResult(
    val stabilized: ColorChromaticity,
    val appliedDriftUv: Float,
    val clamped: Boolean,
    val converged: Boolean
)

/**
 * Consumes the per-frame color-drift budget that [VideoColorStabilityPolicy] already
 * publishes but that nothing enforced, turning it into an actual temporal smoother.
 *
 * The pipeline historically stabilized color only spatially (per-frame neutral-axis
 * protection); a panning or relit scene could still let the auto-white-balance estimate
 * jump frame to frame ("AWB hunting"). This policy bounds that motion:
 *
 *  1. Exponential smoothing toward the freshly measured white point removes jitter.
 *  2. The smoothed step is then hard-clamped so its u'v' magnitude never exceeds the
 *     mode's [VideoColorStabilityPlan.maxFrameToFrameColorDriftUv] budget. Genuine scene
 *     changes are still followed, but only at a calm, bounded slew rate.
 *
 * It is intentionally a strict no-op on the first frame (no history) and a no-op when the
 * measurement has not moved, so it can never destabilize an already-locked shot. A budget
 * of `0` produces a hard lock (the white point is frozen at [previous]).
 */
internal object VideoColorTemporalStabilityPolicy {

    /** Default exponential-smoothing weight applied to the requested step before clamping. */
    const val DEFAULT_SMOOTHING_FACTOR: Float = 0.5f

    /** u'v' distance below which the stabilized point is treated as having reached its target. */
    const val CONVERGENCE_EPSILON_UV: Float = 0.00005f

    /**
     * Stabilizes one frame's measured white point against the previous stabilized value.
     *
     * @param previous last frame's stabilized white point, or `null` on the first frame.
     * @param measured this frame's raw auto-white-balance white point.
     * @param maxFrameToFrameColorDriftUv per-frame u'v' budget from the active
     *   [VideoColorStabilityPlan]; negative values are treated as `0` (hard lock).
     * @param smoothingFactor exponential weight in (0, 1]; coerced into range.
     */
    fun stabilize(
        previous: ColorChromaticity?,
        measured: ColorChromaticity,
        maxFrameToFrameColorDriftUv: Float,
        smoothingFactor: Float = DEFAULT_SMOOTHING_FACTOR
    ): TemporalColorStabilizationResult {
        // First frame: nothing to stabilize against — adopt the measurement verbatim.
        if (previous == null) {
            val safe = if (measured.isFinite()) measured else NEUTRAL_D65
            return TemporalColorStabilizationResult(
                stabilized = safe,
                appliedDriftUv = 0f,
                clamped = false,
                converged = true
            )
        }
        // A non-finite measurement (e.g. a dropped/garbage AWB result) must not poison the
        // state: hold the last good white point rather than jumping to NaN.
        if (!measured.isFinite()) {
            return TemporalColorStabilizationResult(
                stabilized = previous,
                appliedDriftUv = 0f,
                clamped = false,
                converged = false
            )
        }

        val budget = maxFrameToFrameColorDriftUv.coerceAtLeast(0f)
        val smoothing = smoothingFactor.coerceIn(0f, 1f)

        // 1. Exponential step toward the measurement (jitter suppression).
        var stepU = (measured.u - previous.u) * smoothing
        var stepV = (measured.v - previous.v) * smoothing

        // 2. Hard-clamp the step magnitude to the drift budget (anti-hunting slew limit).
        val stepMagnitude = sqrt(stepU * stepU + stepV * stepV)
        val clamped = stepMagnitude > budget
        if (clamped && stepMagnitude > 0f) {
            val scale = budget / stepMagnitude
            stepU *= scale
            stepV *= scale
        }

        val stabilized = ColorChromaticity(previous.u + stepU, previous.v + stepV)
        val appliedDriftUv = sqrt(stepU * stepU + stepV * stepV)

        // Converged when the *measurement* is essentially where we already are.
        val remainingU = measured.u - stabilized.u
        val remainingV = measured.v - stabilized.v
        val converged =
            sqrt(remainingU * remainingU + remainingV * remainingV) <= CONVERGENCE_EPSILON_UV

        return TemporalColorStabilizationResult(
            stabilized = stabilized,
            appliedDriftUv = appliedDriftUv,
            clamped = clamped,
            converged = converged
        )
    }

    /**
     * Convenience overload that reads the drift budget straight from a resolved
     * [VideoColorStabilityPlan], so callers cannot accidentally stabilize against the wrong
     * budget for the active mode.
     */
    fun stabilize(
        plan: VideoColorStabilityPlan,
        previous: ColorChromaticity?,
        measured: ColorChromaticity,
        smoothingFactor: Float = DEFAULT_SMOOTHING_FACTOR
    ): TemporalColorStabilizationResult =
        stabilize(previous, measured, plan.maxFrameToFrameColorDriftUv, smoothingFactor)

    /** CIE 1976 u'v' coordinates of the D65 white point, used as a finite first-frame fallback. */
    private val NEUTRAL_D65 = ColorChromaticity(0.1978f, 0.4683f)

    private fun ColorChromaticity.isFinite(): Boolean = u.isFinite() && v.isFinite()
}
