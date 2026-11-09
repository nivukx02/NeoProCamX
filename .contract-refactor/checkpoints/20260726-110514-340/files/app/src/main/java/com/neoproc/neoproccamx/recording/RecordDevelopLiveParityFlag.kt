package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopLiveParityFlag — reversible A/B lever (default ON) that makes the SDR/NDR develop-conversion
 * SHARPNESS / EDGES / NOISE match the LIVE CINEMA CAMERA record look (WYSIWYG parity).
 *
 * The shipped CINEMA_*_NATURAL develop profiles run `liftEdgeAwareLumaDetail` (detail gains 1.16/1.24/1.36
 * SDR, 1.12/1.22/1.32 NDR) PLUS six compounding Apex luma lanes with spatialDenoise=0 — measured to
 * amplify skin grain ~3.8× and high-freq energy 8–12× vs the source. The live offscreen-EGL record shader
 * instead applies an edge-aware 5-tap bilateral denoise then ONE halo-guarded unsharp
 * (amt = uSharpness·0.85, cap = 0.05 + 0.05·amt) — grain ~0.93×, high-freq ~1.2×. That shader is the
 * "cinema camera" reference the converted file must match.
 *
 * ON  => enhanceProfileFor returns the CINEMA_*_LIVE_PARITY profiles (all three detail gains = 0, so every
 *        lift/Apex luma-sharpen lane early-outs; temporal luma/chroma blend capped to the live 0.35; 1-LSB
 *        dither on) AND every develop frame is routed through [Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010]
 *        (the live bilateral + halo-guarded unsharp). SDR uses the live SDR cap (0.60 sharpen / 0.50
 *        denoise, non-adaptive). NDR tracks the SAME ISO/noise-adaptive curve as the live HDR record
 *        shader (NdrRecordEnhancementCapsPolicy, measured per frame): noisy clips get more denoise + less
 *        sharpen. Chroma-NR (kept strong = clean) + temporal + dither still run in the gain-zeroed kernel.
 * OFF => byte-identical current CINEMA_*_NATURAL over-sharpened profiles and the existing CPU/GPU path (A/B).
 *
 * NO-DOWNGRADE: this REMOVES over-sharpening / grain amplification and matches the live look the user
 * already sees; it never reduces genuine resolution, fps, bit-depth, or colour. Reversible for on-device A/B.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.develop_live_parity true
 */
internal object RecordDevelopLiveParityFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
