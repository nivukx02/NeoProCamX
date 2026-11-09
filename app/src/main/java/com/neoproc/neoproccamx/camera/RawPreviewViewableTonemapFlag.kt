package com.neoproc.neoproccamx.camera

/**
 * Reversible live-monitor gate (default ON). The high-resolution and recording RAW paths use the
 * processed Camera2 companion surface for a responsive preview; that surface must receive a viewable
 * HIGH_QUALITY SDR tonemap instead of the sensor-linear identity curve.
 *
 * The RAW/DNG master is always sourced from the dedicated RAW_SENSOR ImageReader and is pre-tonemap.
 * The app-owned GL/Vulkan sensor-debayer monitor already performs SDR display encoding and a neutral
 * highlight shoulder. To disable the Camera2 monitor transform temporarily for diagnostics:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver \
 *       --ez debug.raw_preview_viewable_tonemap false
 */
internal object RawPreviewViewableTonemapFlag {

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

    /** True only for a live RAW-video processed monitor that would otherwise receive an identity curve. */
    fun shouldSubstituteViewableTonemap(
        flagEnabled: Boolean,
        rawVideoCaptureEnabled: Boolean,
        forStillCapture: Boolean,
        profileAppliesLinearTonemap: Boolean
    ): Boolean =
        flagEnabled && rawVideoCaptureEnabled && !forStillCapture && profileAppliesLinearTonemap
}
