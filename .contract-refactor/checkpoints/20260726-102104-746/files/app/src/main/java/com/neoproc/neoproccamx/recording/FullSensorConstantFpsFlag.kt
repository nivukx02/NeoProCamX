package com.neoproc.neoproccamx.recording

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  FULL-SENSOR CONSTANT-FPS FLAG  (reversible A/B, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Pins FULL-SENSOR (4096×3072, ≥12 MP) recording to a rock-solid CONSTANT 25 fps end-to-end:
 *   • record/encoder target is held at exactly 25 (the record-then-develop LIGHT lane's 30 fps lift is
 *     capped back to 25 for full-sensor so the cadence never wobbles between 25↔30 under thermal load),
 *   • the offscreen-AHB/EGL container is muxed as CFR (uniform 25 fps presentation steps) instead of VFR,
 *     so the delivered file timeline is constant rather than tracking egl-presentation jitter.
 *
 * The sensor AE cadence is separately governed by CONTROL_AE_TARGET_FPS_RANGE; on devices that advertise a
 * fixed [25,25] range the capture is already locked there (see CameraPreviewFpsPolicy.rangePriority). This
 * flag adds the deterministic parts we control: the record target and the container cadence.
 *
 * No-downgrade / reversible: default ON delivers the user-requested constant 25. Set OFF ⇒ byte-identical
 * to the prior behaviour — the LIGHT-lane 30 fps full-sensor lift and offscreen VFR muxing are restored
 * exactly. Full-sensor is the ONLY tier touched (4K-class 3840×2160 / 4096×2304 / 4096×2160 are untouched).
 *
 * Runtime A/B (debug builds), instant revert (no rebuild), applied on the next recording:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.full_sensor_constant_fps true
 */
internal object FullSensorConstantFpsFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Sets the gate and, if it changed, notifies the listener (a recording restart re-reads it). */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
