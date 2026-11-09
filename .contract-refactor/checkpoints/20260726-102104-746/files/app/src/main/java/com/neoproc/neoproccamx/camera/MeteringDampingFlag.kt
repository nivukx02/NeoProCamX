package com.neoproc.neoproccamx.camera

/**
 * Runtime gate for the reframe-transition metering damping ([MeteringConvergenceHysteresis]).
 *
 * Default ON (it is the fix for the native reframe washout / skin-color shift). Turn it OFF to
 * A/B the damping against the raw single-frame convergence behaviour, either from the in-app
 * Settings toggle or, on a debug build, via ADB:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.metering_damping false
 *
 * When OFF, [CameraController] feeds the instantaneous CONTROL_AE_STATE / CONTROL_AWB_STATE into the
 * SDR auto-lock (original behaviour). When ON, it feeds the hysteresis-damped signal so a brief
 * reframe / AF-scan blip does not collapse the lock.
 */
internal object MeteringDampingFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
