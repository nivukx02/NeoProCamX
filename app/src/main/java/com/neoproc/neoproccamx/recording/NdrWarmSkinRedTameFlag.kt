package com.neoproc.neoproccamx.recording

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  NDR10+/NIVUKX — SKIN-GATED WARM-RED TAMING  (reversible, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS
 * ───────────────
 * The 5-month "red skin" bug. A host reproduction (`tools/calibration/ndr_live_repro.py`, an exact port
 * of the live [com.neoproc.neoproccamx.color.NdrToneOperator]) PROVED — numerically — that the NDR/NIVUKX
 * develop and the P010 decode are hue-preserving and do NOT create or amplify the red: feeding warm skin
 * through the full chain leaves it unchanged (skin R−G +0.20→+0.22, saturation 0.26→0.26). The warm/red
 * cast is baked into the CAPTURE by the vendor pipeline (AWB + HIGH_QUALITY CCM + red-channel
 * over-exposure clip) BEFORE any app code runs, and the hue-preserving develop faithfully passes it
 * through — which is exactly why every earlier develop/exposure fix was a no-op.
 *
 * WHAT ON DOES
 * ────────────
 * Adds a ratio-gated red-overflow reconstruction inside the canonical tone operator
 * ([NdrToneOperator.mapRgbHuePreserving], param `warmSkinRedTame`). Moderate R/G together with useful
 * B/G identifies skin, wood and warm neutral surfaces even after the red channel clips. Extreme R/G
 * protects true red, while very-low B/G protects orange. Corrected pixels are restored to their input
 * BT.2020 luminance, so exposure and highlight detail do not change.
 *
 * This is the app's OWN color-integrity math — no other vendor's technology. It does not change
 * resolution/fps/bitrate/10-bit/PQ, and it is not a global red scale or global desaturation.
 *
 * OFF → `strength` passed as 0.0 ⇒ byte-identical to the pre-fix delivery (keeps shader-parity and the
 * golden locks). Kept as an instant revert.
 *
 * DEFAULT ON (2026-07-16) at [DEFAULT_STRENGTH]; tune live if skin looks over/under-corrected.
 *
 * Runtime A/B (debug builds), instant revert / tuning:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.ndr_warm_skin_red_tame true|false
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ef debug.ndr_warm_skin_red_tame_strength 0.5
 */
internal object NdrWarmSkinRedTameFlag {

    /**
     * Device-verified default: enough authority to reconstruct a clipped-red warm subject while the
     * ratio gates still leave neutral, true-red, and low-blue orange pixels byte-identical.
     */
    const val DEFAULT_STRENGTH = 0.50f

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var strength: Float = DEFAULT_STRENGTH
        private set

    /** The strength the develop should apply: the tuned value when ON, or 0.0 (no-op) when OFF. */
    val effectiveStrength: Double
        get() = if (enabled) strength.toDouble() else 0.0

    fun set(value: Boolean) {
        enabled = value
    }

    fun setStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
    }
}
