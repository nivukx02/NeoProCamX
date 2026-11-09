package com.neoproc.neoproccamx.camera

/**
 * Reversible A/B gate (default ON) — RC2 fix: during RAW *video* preview/recording, keep the SHARED
 * preview/repeating request on a viewable HIGH_QUALITY tonemap instead of the SUPREME_FIDELITY_BYPASS
 * linear identity curve (HardwareAbstractionLayer.kt tonemapMode=CONTRAST_CURVE + applyLinearTonemapCurve),
 * which makes the RAW monitor preview look flat/washed/gray.
 *
 * SAFE by construction: the recorded RAW/DNG is sourced from the dedicated RAW_SENSOR ImageReader
 * (CameraController rawReader -> onRawReady -> RawVideoManager.onRawFrameAvailable), and RAW_SENSOR output
 * is pre-ISP-tonemap, so dropping the linear curve from the shared processed-output request cannot alter
 * recorded RAW. Only the tonemap changes; EDGE/NOISE_REDUCTION/HOT_PIXEL/SHADING stay OFF.
 *
 * Default ON: gives the RAW monitor preview a viewable (non-gray) image. Flip OFF for instant revert:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.raw_preview_viewable_tonemap false
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

    /**
     * Pure, unit-testable decision: substitute a viewable tonemap on the shared preview request? Only for
     * the live repeating request (not a still), only when RAW video capture is active, and only when the
     * resolved profile would otherwise apply the flat linear curve.
     */
    fun shouldSubstituteViewableTonemap(
        flagEnabled: Boolean,
        rawVideoCaptureEnabled: Boolean,
        forStillCapture: Boolean,
        profileAppliesLinearTonemap: Boolean
    ): Boolean =
        flagEnabled && rawVideoCaptureEnabled && !forStillCapture && profileAppliesLinearTonemap
}
