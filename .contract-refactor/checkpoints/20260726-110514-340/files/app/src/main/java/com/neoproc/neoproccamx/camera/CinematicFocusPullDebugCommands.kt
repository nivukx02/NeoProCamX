package com.neoproc.neoproccamx.camera

/**
 * CP-QV20 — process-global command sink that routes ADB debug commands to the live
 * [CameraController]'s gated cinema focus-pull methods.
 *
 * The [com.neoproc.neoproccamx.ui.DebugCommandReceiver] runs in-process but has no handle to the
 * live camera; it flips process-global flags directly (see [ColorDriftEnforcementFlag] /
 * [MeteringDampingFlag]). This object mirrors that pattern for the focus-pull: the active
 * [CameraController] registers its gated handlers via [register], and the receiver invokes
 * [setMark] / [configure] / [trigger], which forward to those handlers when a camera is live.
 *
 * No-downgrade: every forwarded [CameraController] method is itself gated behind
 * [CinematicFocusPullFlag] (default OFF) and is a no-op when the flag is off, so these entry points
 * cannot change the default focus behavior. When no camera is registered the calls are silent no-ops.
 */
internal object CinematicFocusPullDebugCommands {

    /** Handlers wired by the live [CameraController]; null when no camera is active. */
    internal interface Handler {
        fun setCinematicFocusPullMark(isA: Boolean, diopters: Float)
        fun configureCinematicFocusPull(curve: CinematicFocusPullControl.EasingCurve, durationMs: Long)
        fun triggerCinematicFocusPull(): Boolean
    }

    @Volatile
    private var handler: Handler? = null

    fun register(value: Handler?) {
        handler = value
    }

    fun setMark(isA: Boolean, diopters: Float) {
        handler?.setCinematicFocusPullMark(isA, diopters)
    }

    fun configure(curve: CinematicFocusPullControl.EasingCurve, durationMs: Long) {
        handler?.configureCinematicFocusPull(curve, durationMs)
    }

    fun trigger(): Boolean = handler?.triggerCinematicFocusPull() ?: false

    /**
     * Parse the ADB `debug.focus_pull_curve` string into an [CinematicFocusPullControl.EasingCurve].
     * `linear_rack` -> LINEAR_RACK; anything else (incl. `ease_in_out`) -> EASE_IN_OUT (cinema default).
     */
    fun parseCurve(value: String?): CinematicFocusPullControl.EasingCurve =
        when (value?.trim()?.lowercase()) {
            "linear_rack" -> CinematicFocusPullControl.EasingCurve.LINEAR_RACK
            else -> CinematicFocusPullControl.EasingCurve.EASE_IN_OUT
        }
}
