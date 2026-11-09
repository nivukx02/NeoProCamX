package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.ai.AIFocusDistanceController
import com.neoproc.neoproccamx.ai.FocusPidState

/**
 * s5-f08 / CP-F9 — bridges f07's [CinematicFocusPullControl.CinematicFocusStepper] seam to the
 * READ-ONLY [AIFocusDistanceController] focus-pull API (the four named styles SMOOTH / RACK / CREEP
 * / SNAP). It owns ZERO easing math: every per-frame diopter is delegated to
 * [AIFocusDistanceController.applyFocusPull], which maps each style to its (duration, easing) profile
 * (SMOOTH→EASE_IN_OUT_CUBIC@320ms, RACK→EASE_IN_OUT_QUAD@180ms, CREEP→SMOOTHERSTEP@550ms,
 * SNAP→EASE_OUT_QUAD@90ms) and runs the state machine.
 *
 * The seam is progress/dt-based and stateless; the [AIFocusDistanceController] API is state + clock
 * based. This adapter reconciles the two by owning the [FocusPidState] and a monotonic nanosecond
 * clock advanced by the per-frame `deltaTimeSeconds`, seeded at a strictly-positive base so the
 * transition starts cleanly on the first step (no zero-`nowNs` restart). The bound `endDiopters`
 * never changes mid-pull, so the controller's completion is endpoint-exact with no restart kink.
 *
 * No-downgrade: an instance is only ever constructed by [CameraController.beginCinematicStylePull]
 * when [CinematicFocusPullFlag] is ON; with the flag OFF the f07 control keeps its default
 * [CameraFocusControl] stepper and the manual-lens path is byte-for-byte today.
 */
internal class AICinematicPullStepper(
    private val style: AIFocusDistanceController.FocusPullStyle,
    private val maxDiopters: Float = DEFAULT_MAX_DIOPTERS
) : CinematicFocusPullControl.CinematicFocusStepper {

    private var state = FocusPidState()
    private var elapsedNs = BASE_NS
    private var started = false

    override fun step(
        startDiopters: Float,
        endDiopters: Float,
        currentDiopters: Float,
        progress: Float,
        deltaTimeSeconds: Float,
        curve: CinematicFocusPullControl.EasingCurve,
        speedProfile: FocusSpeedProfile
    ): Float {
        if (!started) {
            // Seed the PID state at mark A so the first transition begins from the start diopter,
            // and pin the clock to a positive base so applyFocusPull arms the transition immediately.
            state = state.copy(lastAppliedDiopters = startDiopters)
            elapsedNs = BASE_NS
            started = true
        } else {
            elapsedNs += (deltaTimeSeconds.coerceAtLeast(0f) * 1_000_000_000f).toLong()
        }
        val result = AIFocusDistanceController.applyFocusPull(
            targetDiopters = endDiopters,
            maxDiopters = maxDiopters,
            state = state,
            nowNs = elapsedNs,
            pullStyle = style
        )
        state = result.nextState
        return result.appliedDiopters
    }

    internal companion object {
        const val DEFAULT_MAX_DIOPTERS = 20f
        private const val BASE_NS = 1_000_000_000L // 1s offset → transitionStartNs is always > 0

        /**
         * The pull duration (ms) [AIFocusDistanceController.applyFocusPull] uses for [style]. Mirrors
         * its private style→duration mapping so [CameraController] can align the f07 control's
         * `durationMs` with the pull's completion (progress≥1 lands on B at the same instant).
         */
        fun styleDurationMs(style: AIFocusDistanceController.FocusPullStyle): Long = when (style) {
            AIFocusDistanceController.FocusPullStyle.SMOOTH -> 320L
            AIFocusDistanceController.FocusPullStyle.RACK -> 180L
            AIFocusDistanceController.FocusPullStyle.CREEP -> 550L
            AIFocusDistanceController.FocusPullStyle.SNAP -> 90L
        }
    }
}
