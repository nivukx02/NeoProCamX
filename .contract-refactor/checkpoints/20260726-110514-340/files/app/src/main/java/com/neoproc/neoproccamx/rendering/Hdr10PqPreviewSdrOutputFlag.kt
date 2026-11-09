package com.neoproc.neoproccamx.rendering

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  HDR10 PQ PREVIEW — SDR-DISPLAY OUTPUT MODE  (reversible, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * On the vivo I2405 the [Hdr10PqPreviewCompositor] can NOT bind its 10-bit
 * RGBA1010102 / BT.2020-PQ window surface (`eglCreateWindowSurface` with
 * EGL_GL_COLORSPACE_BT2020_PQ_EXT is rejected). [Hdr10PqPreviewPolicy] is
 * deliberately OFF by default, so the stable direct SurfaceView preview is used
 * unless debug.pq_preview explicitly opts into this compositor path. Without this
 * SDR-output mode, an opted-in compositor may fall back to the direct path, which
 * feeds raw BT.2020 / PQ 10-bit straight to the 8-bit SDR panel with only
 * SurfaceFlinger's generic HDR→SDR — no app gamut compression (BT.2020 wide
 * primaries → reds oversaturate) and no app tone-map of the PQ curve (highlights
 * crush).
 *
 * WHAT ON DOES
 * ────────────
 * ON  → the compositor binds a plain 8-bit sRGB window surface (the config proven
 *       on this device by [PreviewGlCompositor]), so `start()` SUCCEEDS and the
 *       camera is rebound to the compositor. The fragment shader still PQ-decodes
 *       the camera frame (ST.2084 EOTF) and runs the app's OWN NDR10+/NIVUKX
 *       hue-preserving tone operator, but its FINAL stage outputs display-referred
 *       SDR: a luminance-only highlight shoulder (keeps both exposed + highlight
 *       data, no per-channel hue rotation), a BT.2020→BT.709 gamut conversion with
 *       hue-preserving compression toward the neutral axis (kills the oversaturated
 *       red), then the sRGB OETF. This is standards math (ITU-R primaries / BT.2390
 *       principle) applied to the user's own tones — NO other-vendor technology.
 *
 * OFF → the legacy 10-bit BT.2020-PQ attempt (current on-device behaviour: fails to
 *       bind → direct preview). Kept so the on-device A/B can compare, and so a
 *       device that DOES support the 10-bit-PQ surface keeps genuine PQ WYSIWYG.
 *
 * NO DOWNGRADE: this is the on-screen MONITOR surface only. The recording encoder
 * OutputConfiguration stays PROFILE_HDR10_PLUS (PQ, 10-bit); recorded
 * resolution/fps/bitrate/10-bit/PQ are untouched. Preview bit-depth is not a
 * recorded-quality axis.
 *
 * Runtime A/B (debug builds), instant revert:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.pq_preview_sdr_out true|false
 */
internal object Hdr10PqPreviewSdrOutputFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Flip the mode; notify the listener to rebind the preview surface if it changed. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by the ViewModel to re-bind the preview when the mode flips (on-device A/B). */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
