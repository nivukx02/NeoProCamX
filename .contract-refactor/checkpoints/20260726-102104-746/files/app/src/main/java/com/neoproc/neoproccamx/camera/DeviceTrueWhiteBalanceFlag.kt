package com.neoproc.neoproccamx.camera

/**
 * Process-global, runtime-flippable gate for the DEVICE-TRUE manual white balance.
 *
 * Default ON. When enabled, the manual-WB `TRANSFORM_MATRIX` path (used on devices without the
 * API-36 hardware CCT correction) resolves `COLOR_CORRECTION_GAINS` — and, when derivable, the
 * paired `COLOR_CORRECTION_TRANSFORM` — from the sensor's own DNG calibration via
 * [DngWhiteBalanceMath]/[DeviceColorCalibration], instead of the generic Tanner-Helland blackbody
 * approximation in [WhiteBalanceConversion]. That makes a chosen (Kelvin, tint) neutralise exactly
 * on this specific sensor — the "accurate data from Pro Control" target.
 *
 * Non-regressive by construction: it engages ONLY when the sensor publishes usable DNG calibration
 * AND the solve succeeds; otherwise the request falls back to the exact legacy
 * [WhiteBalanceConversion.kelvinToRggb] path. Flipping this OFF restores today's behaviour bit-for-
 * bit, so it doubles as the on-device A/B safety valve:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.device_true_wb false
 *
 * [set] fires [registerOnChanged] so [CameraController] rebuilds the preview request at once.
 */
internal object DeviceTrueWhiteBalanceFlag {

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
