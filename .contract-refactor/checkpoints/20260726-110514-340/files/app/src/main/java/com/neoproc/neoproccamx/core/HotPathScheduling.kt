package com.neoproc.neoproccamx.core

import android.os.Process

/**
 * HotPathScheduling — reversible real-time scheduling elevation for the app's latency-critical
 * threads (record submit/drain, the visible preview compositors, the RAW writer, the P010 input).
 *
 * WHY THIS EXISTS
 * ───────────────
 * JVM `Thread.priority` is largely ignored by Android's CFS scheduler; only
 * `android.os.Process.setThreadPriority` (which sets the Linux *nice* value of the CALLING thread)
 * actually reprioritises a thread. Several hot threads previously set ONLY the JVM priority — and
 * the RAW writer even ran BELOW normal (`Thread.NORM_PRIORITY - 1`) — so they could be preempted by
 * ordinary background AI/analysis/system work, adding frame-pacing jitter on the exact live paths.
 * Calling [applyUrgentDisplay]/[applyDisplay] from INSIDE each thread's run-body fixes that; the
 * [urgentDisplayPriority]/[displayPriority] getters feed the same value into `HandlerThread`'s
 * priority-taking constructor.
 *
 * NO-DOWNGRADE / NOT-A-BATTERY-OPT
 * ────────────────────────────────
 * This changes ONLY scheduling — identical frames, identical recorded/composited pixels. It WINS
 * more CPU contention and therefore burns MORE power, i.e. it is the OPPOSITE of a battery
 * optimisation (battery is an explicit non-goal for this app).
 *
 * TWO TIERS (so URGENT does not become meaningless)
 * ─────────────────────────────────────────────────
 *  - URGENT_DISPLAY: the record submit/drain path + the visible preview compositors + the P010/RAW
 *    writer (these must never stall the recorder or the viewfinder).
 *  - DISPLAY: monitor/analysis workers (one notch lower, so overlays never starve the record path).
 *
 * DEFAULT ON: pure scheduling with no possible quality downgrade, and the app explicitly wants
 * maximum smoothness with battery as a non-constraint. Fully reversible for on-device A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.hot_path_priority false
 * When disabled, [applyUrgentDisplay]/[applyDisplay] are no-ops and the priority getters return
 * `THREAD_PRIORITY_DEFAULT` (the HandlerThread default), so behaviour is byte-identical to before.
 */
object HotPathScheduling {

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

    /** Nice value for the record/preview-critical tier — or the HandlerThread default when disabled. */
    fun urgentDisplayPriority(): Int =
        if (enabled) Process.THREAD_PRIORITY_URGENT_DISPLAY else Process.THREAD_PRIORITY_DEFAULT

    /** Nice value for the analysis/monitor tier — or the HandlerThread default when disabled. */
    fun displayPriority(): Int =
        if (enabled) Process.THREAD_PRIORITY_DISPLAY else Process.THREAD_PRIORITY_DEFAULT

    /** Elevate the CALLING thread to the record/preview-critical tier. No-op when disabled; never throws. */
    fun applyUrgentDisplay() {
        if (enabled) applyToCurrentThread(Process.THREAD_PRIORITY_URGENT_DISPLAY)
    }

    /** Elevate the CALLING thread to the analysis/monitor tier. No-op when disabled; never throws. */
    fun applyDisplay() {
        if (enabled) applyToCurrentThread(Process.THREAD_PRIORITY_DISPLAY)
    }

    private fun applyToCurrentThread(priority: Int) {
        try {
            Process.setThreadPriority(priority)
        } catch (_: Throwable) {
            // A scheduling hint must never crash a hot path (e.g. SecurityException on locked-down OEMs).
        }
    }
}
