package com.neoproc.neoproccamx.camera

/**
 * RC5 same-sensor session-rebuild AWB-washout deferral (default OFF).
 *
 * On a same-sensor rebuild (SDR<->HDR toggle, resolution change, record-start) HAL 3A restarts on
 * the same scene while the last pre-rebuild capture result is still cached. When ON, the AWB lock is
 * deferred until a genuine post-rebuild convergence ([PostRebuildConvergenceGate]) and the AE
 * convergence state is carried across the rebuild, so the lock can never latch the stale washed-out
 * white point captured during the fresh-session AE overshoot. Default OFF = today's full 3A reset,
 * byte-identical. A/B via ADB:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.session_rebuild_color_hold true
 */
internal object SessionRebuildColorHoldFlag {
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
