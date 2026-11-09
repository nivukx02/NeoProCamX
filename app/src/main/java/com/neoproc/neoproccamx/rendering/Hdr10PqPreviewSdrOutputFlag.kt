package com.neoproc.neoproccamx.rendering

import android.util.Log

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  HDR10 PQ PREVIEW — SDR-DISPLAY OUTPUT MODE  (reversible, default OFF)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * This branch was introduced for early devices on which native BT.2020-PQ surface creation appeared
 * unavailable. Later device evidence proved strict RGB10_A2/PQ initialization on the target Vivo.
 * The branch is retained only so the old shader remains auditable; product policy rejects enabling
 * it because an NDR request must never silently change bit depth, transfer, or colour volume.
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
 * OFF → strict 10-bit BT.2020-PQ output. Setup failure is reported as unsupported and cannot authorize
 *       a direct or 8-bit preview.
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
    var enabled: Boolean = false
        private set

    /**
     * The legacy 8-bit monitor route is retained only as source history. Strict delivery rejects
     * attempts to enable it so an NDR request can never be silently converted to SDR.
     */
    fun set(value: Boolean) {
        if (value) {
            Log.w(TAG, "Ignored request to enable legacy 8-bit NDR monitor output")
        }
    }

    /** Registered by the ViewModel to re-bind the preview when the mode flips (on-device A/B). */
    fun registerOnChanged(@Suppress("UNUSED_PARAMETER") callback: (() -> Unit)?) = Unit

    private const val TAG = "Hdr10PqPreviewOutput"
}
