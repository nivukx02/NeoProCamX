package com.neoproc.neoproccamx.camera

/**
 * Runtime gate for the cinema focus-pull control ([CinematicFocusPullControl], s5-f07).
 *
 * Default **OFF**. The cine focus-pull path (mark-to-mark rack on a chosen easing curve + duration,
 * DoF / peaking / hunting operator readout) is net-new capability; with this flag OFF the
 * [CameraController] never queries the control and autofocus / manual-lens drive behave exactly as
 * today (proven by the cine-off golden lock). The owner enables it device-side, e.g. on a debug
 * build via ADB:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.cine_focus_pull true
 *
 * When ON and a pull is active, [CameraController] routes the per-frame stepped diopter through the
 * existing manual-lens-distance request path. s5-f08 wires its [com.neoproc.neoproccamx.ai
 * .AIFocusDistanceController] pull engine into the same control behind this same flag.
 */
internal object CinematicFocusPullFlag {

    @Volatile
    var enabled: Boolean = false
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
