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
 * It engages ONLY when the sensor publishes a complete, usable gains-plus-CCM calibration and the
 * solve succeeds. An incomplete transform is never paired with generic gains or a CCM harvested from
 * another frame; unsupported devices keep their advertised AUTO/preset path. Flipping this OFF is an
 * on-device safety valve that disables the calibrated transform path without emitting partial manual
 * requests:
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
