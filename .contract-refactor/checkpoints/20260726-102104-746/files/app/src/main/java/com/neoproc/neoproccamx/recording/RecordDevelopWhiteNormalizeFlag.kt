package com.neoproc.neoproccamx.recording

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-DEVELOP — SNCE WHITE-NORMALISATION  (reversible, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * The offline NDR develop decodes the HLG-coherent intermediate to SCENE-referred linear where diffuse
 * white sits at ≈0.265 (the 75%-signal HLG reference white), not 1.0. But Stage A of
 * [VideoDevelopColorPipeline] runs SNCE v2 / CAM16 with an adopted white of Y≈1.0. CAM16's colourfulness
 * prediction is luminance-dependent, so running it ~2 stops below its designed operating point makes it
 * OVER-predict colourfulness and push saturated hues — the dominant contributor to the NDR "red
 * over-boost". (The PQ encode already re-normalises with `k = 1/diffuseWhiteScene`, but only AFTER Stage A,
 * so the appearance stage still ran at the wrong point.)
 *
 * This is pure ITU/SMPTE-consistent standards math (operate the appearance model at diffuse white, per the
 * BT.2408 203-nit / HLG-reference-white anchor) applied to the user's OWN NDR10+/NIVUKX tones — no other
 * vendor's technology.
 *
 * WHAT ON DOES
 * ────────────
 * ON  → the NDR genuine-HDR develop builds [VideoDevelopColorPipeline] with a white-normalisation scale of
 *       `1 / diffuseWhiteScene` (≈3.77 for HLG). The pipeline multiplies it in JUST around Stage A so CAM16
 *       sees diffuse white ≈1.0, then divides it back out — the output scale (and therefore the PQ encode's
 *       `k`) is unchanged, so exposure/highlight mapping is identical; only the appearance prediction is
 *       corrected. Reds stop over-saturating without touching the tone curve.
 *
 * OFF → byte-identical to the pre-fix path (scale = 1f ⇒ Stage A unchanged). Kept as an instant revert.
 *
 * DEFAULT ON (2026-07-16): the CAM16 operating-point correction is standards-consistent and directly
 * addresses the user's primary complaint (NDR/NIVUKX red over-saturation). It is the single safest,
 * highest-confidence red-reduction lever available offline; if a gray-card A/B ever shows it swings the
 * neutral balance the wrong way, flip it OFF live with the broadcast below (no rebuild).
 *
 * SCOPE: NDR genuine-HDR develop only; SDR develop is unaffected (its intermediate is already ≈white-1.0).
 * Enabling it makes the CPU develop diverge from the native GPU accelerator, so
 * [RecordDevelopParityGatePolicy] safely falls back to the CPU develop for NDR converts (correct output,
 * only slower — offline, so no live impact) until the native GPU path mirrors the normalisation. No
 * resolution/fps/bitrate/10-bit/PQ change.
 *
 * Runtime A/B (debug builds), instant revert:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_develop_white_normalize true|false
 */
internal object RecordDevelopWhiteNormalizeFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    /** Flip the lever (read live when the develop plan is built; no rebind needed). */
    fun set(value: Boolean) {
        enabled = value
    }
}
