package com.neoproc.neoproccamx.ui

import android.content.pm.ActivityInfo
import android.util.Log
import android.view.Window
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  WINDOW HDR COLOR MODE POLICY
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Controls [Window.setColorMode] so the Activity window participates in the
 * HDR display pipeline when the user has selected an NDR recording mode.
 *
 * Why this matters
 * ────────────────
 * Even when Camera2 produces real 10-bit dynamic-range frames (via
 * `setDynamicRangeProfile` on the preview Surface) and the panel itself
 * is extended-range capable, the system compositor will tone-map the SurfaceView
 * down to SDR brightness *unless* the hosting Window declares it can
 * render extended range. Without this, the user sees a dim BT.2020 SDR preview that
 * doesn't match the recorded file. With it, the panel drives full HDR
 * brightness on capable devices and falls back gracefully on others.
 *
 * Lifecycle
 * ─────────
 * Call [apply] whenever the user changes the HDR mode (or LOG flag) and
 * also during Activity setup. Calling with [HdrColorMode.SDR] reverts the
 * window to default colour mode — important so SDR captures don't get
 * displayed at HDR brightness.
 */
internal object WindowHdrColorModePolicy {
    private const val TAG = "WindowHdrColorMode"

    /**
     * The two states this policy switches between. We deliberately keep
     * this independent of `HdrDynamicRangeMode` because the window-level
     * decision is binary on Android (either HDR-extended-range is on or
     * it isn't); per-profile distinctions are handled
     * downstream by the compositor.
     */
    enum class HdrColorMode {
        /** Default colour mode — system-managed SDR rendering. */
        SDR,

        /**
         * HDR colour mode — opts the window into the wide-gamut /
         * extended-range pipeline. On Android 8+ this maps to
         * [ActivityInfo.COLOR_MODE_HDR].
         */
        HDR
    }

    /**
     * Decide which window colour mode pairs with the active recording
     * dynamic range mode. SDR/STANDARD recording ⇒ SDR window; any
     * 10-bit Neo dynamic range profile -> HDR window.
     *
     * NDR modes get HDR window. PRO / LOG / RAW / NORMAL get SDR window
     * unless an extended-range transfer is explicitly active.
     *
     * Pure for testing.
     */
    fun resolveColorMode(
        videoHdrMode: String?,
        logEnabled: Boolean
    ): HdrColorMode {
        val normalized = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(videoHdrMode)
        return if (normalized != "OFF") {
            HdrColorMode.HDR
        } else {
            when {
                logEnabled -> HdrColorMode.SDR       // LOG: original wide-gamut SDR preview
                else -> HdrColorMode.SDR             // PRO/RAW/NORMAL: normal SDR preview
            }
        }
    }

    /**
     * Apply the resolved colour mode to [window]. Safely no-ops on any
     * runtime that throws a SecurityException for the requested mode
     * (some emulator / TV builds reject HDR).
     */
    fun apply(window: Window, mode: HdrColorMode, forceReassert: Boolean = false) {
        applyOreo(window, mode, forceReassert)
    }

    private fun applyOreo(window: Window, mode: HdrColorMode, forceReassert: Boolean) {
        val target = when (mode) {
            HdrColorMode.HDR -> ActivityInfo.COLOR_MODE_HDR
            HdrColorMode.SDR -> ActivityInfo.COLOR_MODE_DEFAULT
        }
        try {
            if (window.colorMode != target) {
                window.colorMode = target
                Log.i(TAG, "Window colorMode → $mode (raw=$target)")
            } else if (forceReassert && target == ActivityInfo.COLOR_MODE_HDR) {
                // Cached colorMode already reads HDR, but the compositor can reclaim
                // the window's effective extended range while backgrounded — the
                // SurfaceView then shows the 10-bit HLG preview tone-mapped to SDR
                // even though the recorded file (separate Camera2 DynamicRangeProfile
                // path) is untouched. Bounce DEFAULT→HDR to force SurfaceFlinger to
                // re-negotiate the extended-range window. Display-side only.
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                window.colorMode = target
                Log.i(TAG, "Window colorMode re-asserted HDR (forced)")
            }
        } catch (t: Throwable) {
            // Some OEM ROMs throw on COLOR_MODE_HDR when no HDR display is
            // attached, others silently downgrade. Either way we want to
            // continue without HDR rather than crash the activity.
            Log.w(TAG, "Failed to apply window colorMode=$mode: ${t.message}")
        }
    }
}
