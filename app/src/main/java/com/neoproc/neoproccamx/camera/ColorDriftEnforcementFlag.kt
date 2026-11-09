package com.neoproc.neoproccamx.camera

/**
 * Process-global, runtime-flippable gate for active color-drift protection. Default ON: the
 * temporally stabilized white point ([ColorDriftGovernor]) supplies confidence and drift evidence;
 * a converged processed-video shot is held with Camera2 AWB lock so colour stays put across focus
 * pulls and camera tilts without replacing the vendor ISP colour pipeline.
 *
 * Scope is deliberately narrow and non-regressive: the override engages ONLY in converged,
 * lockable-auto normal-SDR shots ([VideoColorStabilityPlan.requiresLockableAutoWhiteBalance]) —
 * never in RAW/HDR/NDR/LOG/photo, so the 10-bit HDR color path is untouched — and it is IDENTITY
 * when the scene is stable. Capture-result gains and CCM remain diagnostic inputs and are never
 * replayed as a partial transform-matrix request, preserving vendor hue, saturation, and tone logic.
 *
 * Still runtime-flippable for on-device A/B (there is no longer a Settings-sheet row; use ADB on a
 * debug build):
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.color_drift_enforce false
 *
 * [set] fires [registerOnChanged] so [CameraController] rebuilds the preview request immediately
 * (the override is applied during request building, not per-frame).
 */
internal object ColorDriftEnforcementFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Sets the flag and, if it changed, notifies the registered listener to refresh the preview. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by [CameraController] to rebuild the preview request when the flag flips. */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
