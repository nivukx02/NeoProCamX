package com.neoproc.neoproccamx.camera

/**
 * Process-global, runtime-flippable gate for the NDR10+/NIVUKX **shared-capture warm-red fix**.
 *
 * DEVICE-CONFIRMED BUG (live screenshot 2026-07-16, vivo I2405): NDR10+/NIVUKX HDR renders warm/skin
 * tones as neon RED while neutrals (silver laptop/keyboard) stay correct — on PREVIEW, RECORDING and
 * after CONVERSION alike. A 6-agent trace proved all three surfaces draw from ONE shared HDR capture,
 * so the red is baked UPSTREAM, not in any develop/delivery chain (those are provably hue-preserving:
 * multiply-all-channels + radial desat only reduce saturation, never add it). The dominant origin is
 * [CameraHdrColorCorrectionPolicy] force-setting `COLOR_CORRECTION_MODE_HIGH_QUALITY` on every non-SDR
 * shot: the vendor "high quality" CCM applies proprietary memory-colour saturation that over-boosts
 * saturated warm/red hues while leaving neutrals alone — exactly the observed cast.
 *
 * Optional diagnostic (default OFF): for HDR (NDR10+/NIVUKX) capture, prefer the stock Camera2
 * `COLOR_CORRECTION_MODE_FAST` — a lighter, less-saturated vendor correction — instead of HIGH_QUALITY.
 * FAST is a standard advertised Camera2 mode (NO third-party / other-vendor technology), keeps a real
 * vendor CCM (no gray/washout, unlike TRANSFORM_MATRIX), needs NO session reconfigure, and because it
 * changes the single shared capture it de-reds preview + recording + convert TOGETHER — the change is
 * visible live in preview. SDR (including strict-processed-video SDR) is untouched — this is HDR-only.
 * No resolution/fps/bitrate/10-bit/PQ change. It is not enabled for shipping because FAST may reduce
 * ISP colour-correction quality; the NDR-owned capture exposure and scene-aware delivery correction
 * now solve the red cast without lowering the requested Camera2 quality mode.
 *
 * OFF → byte-identical to the pre-fix path (HIGH_QUALITY CCM). Kept as an instant revert if FAST ever
 * looks worse on a given HAL (FAST is not guaranteed less-saturated everywhere — gray-card A/B advised).
 *
 * Reversible A/B safety valve (debug builds), instant revert, no rebuild:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.hdr_ccm_fast true|false
 */
internal object CameraHdrColorFastModeFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    /** Flip the lever (read live when the capture request is assembled; no rebind needed). */
    fun set(value: Boolean) {
        enabled = value
    }
}
