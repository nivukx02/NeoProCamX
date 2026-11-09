package com.neoproc.neoproccamx.camera

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  NEO-LOG HAL TONEMAP POLICY  (genuine N-LOG baked by the camera ISP)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Gate for baking the user's REAL NeoLog transfer ([TonemapCurveFactory.buildRecordingLogCurve]
 * → [com.neoproc.neoproccamx.ai.NLogMath.encode], the N-LOG v5 APEX-LOG IP) into the LOG capture
 * request via `TONEMAP_MODE_CONTRAST_CURVE` + `TONEMAP_CURVE`.
 *
 * Why this exists
 * ───────────────
 * The LOG record path previously used the vendor `TONEMAP_MODE_HIGH_QUALITY` curve and merely tagged
 * the clip "N-LOG v5 APEX-LOG" — so the recorded pixels were the vendor tonemap, NOT the user's
 * NeoLog. This routes the genuine NeoLog curve to the ISP so the camera encodes N-LOG into the P010
 * stream itself. Because LOG preview + record share ONE Camera2 repeating request, the visible
 * preview then shows the SAME genuine NeoLog frames (true WYSIWYG) — which is why the additive GL
 * preview compositor is bypassed for LOG whenever this is active (avoids double-log).
 *
 * SAFETY (the load-bearing rules)
 * ───────────────────────────────
 *  • LOG only. Never RAW (RAW keeps its sensor-truthful bypass), never HDR (NDR/NIVUKX keep their
 *    10-bit HLG DynamicRangeProfile transport), never still capture.
 *  • Requires the device to advertise `TONEMAP_MODE_CONTRAST_CURVE`; the caller also wraps the
 *    request writes in runCatching, so a device that refuses the custom curve falls back to the
 *    proven vendor tonemap — a look change, never a pipeline downgrade.
 *  • The recorded container stays genuine 10-bit HEVC-Main10 / P010 regardless (this only changes
 *    the ISP tone curve, not the encoder format / DynamicRangeProfile).
 *
 * Runtime-flippable for on-device A/B and instant revert (debug builds):
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.neolog_hal false
 *
 * (Toggle the LOG mode off/on, or trigger a session rebuild, for the new value to take effect.)
 */
object NeoLogHalTonemapPolicy {

    /**
     * DEFAULT OFF — DEVICE TRUTH (vivo I2405, 2026-07-05): the device ADVERTISES
     * TONEMAP_MODE_CONTRAST_CURVE and accepts the request key WITHOUT throwing, but its ISP does NOT
     * honour a custom (non-identity) tone curve on the processed-video path — a recorded LOG clip came
     * back vendor-contrasty (crushed blacks), NOT the flat N-LOG, while staying 30fps/4K/Main10/10-bit.
     * Because enabling it also bypasses the GL preview compositor (to avoid double-log), an unhonoured
     * curve makes the LOG preview contrasty too — a regression. So this ships OFF: LOG preview uses the
     * genuine NeoLog compositor shader, and recording stays the proven 10-bit path. The mechanism is
     * kept + ADB-flippable for any FUTURE device whose HAL genuinely honours TONEMAP_CURVE.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        // RETIRED no-op: LOG is convert-only, so the direct-LOG HAL bake is permanently off and
        // cannot be re-enabled. Kept for the debug-command / preview-gate call sites; toggling it
        // has no effect on recorded or preview pixels.
    }

    /**
     * True when the LOG capture request should carry the genuine NeoLog `TONEMAP_CURVE`. Pure — the
     * single source of truth. IDENTITY-safe: every non-LOG / non-SDR / unsupported case returns false
     * so the proven paths are untouched.
     *
     * @param logMode              LOG look is active (camera-side `previewLogLookEnabled`).
     * @param rawCapture           RAW master capture is active — forces false (RAW keeps its bypass).
     * @param hdrMode              any NDR/NIVUKX HDR dynamic range is active — forces false.
     * @param stillCapture         still-image request — forces false (video LOG only).
     * @param supportsContrastCurve device advertises TONEMAP_MODE_CONTRAST_CURVE.
     */
    fun shouldApply(
        logMode: Boolean,
        rawCapture: Boolean,
        hdrMode: Boolean,
        stillCapture: Boolean,
        supportsContrastCurve: Boolean
    ): Boolean = false // RETIRED: LOG is convert-only — NeoLog is never baked into recorded pixels.
}
