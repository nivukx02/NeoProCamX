package com.neoproc.neoproccamx.rendering

/**
 * Process-global, runtime-flippable gate for the NDR10+/NIVUKX **input-transfer decode** fix.
 *
 * DEVICE-CONFIRMED BUG (2026-07-15, vivo I2405 logcat): the NDR/NIVUKX capture negotiates
 * `PROFILE_HDR10_PLUS` — i.e. the camera delivers **PQ (ST.2084)**-encoded BT.2020 frames
 * (`HDR session profiles ... preview=8`). But both NDR pixel operators decode the frames as **HLG**:
 *  • preview: [Hdr10PqPreviewCompositor] FRAGMENT_SHADER `hlgInvOetf(...) / NEO_HLG_DIFFUSE_SCENE`
 *  • record: `offscreen_egl_encoder_bridge.cpp` `neoDeliverPqFromHlg` / `hlgInverseOetf`
 * Applying an HLG curve to PQ-encoded data bends colour across every hue (the "reddish, all colour
 * families, NOT a tone problem" report) and crushes the bright/"exposed" range. SDR is unaffected
 * (STANDARD profile + separate path), which matches the user's PRO-vs-HDR A/B.
 *
 * FIX (default ON): decode the input with the **PQ (ST.2084) EOTF** (`display-linear = pqEotf(e)`,
 * anchored ×10000/203 nit) so the existing 203-nit / master-peak NDR10+/NIVUKX operator runs
 * PQ-in → PQ-out. This keeps NDR10+/NIVUKX as the user's own PQ colour technology — NO HLG anywhere
 * (HLG is explicitly out of scope per the user). Not a downgrade: still 10-bit BT.2020, still the same
 * tone operator and delivery; only the input transfer curve is corrected to match what the camera
 * actually sends.
 *
 * Reversible A/B safety valve — flip OFF to restore the previous (broken) HLG decode:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.ndr_pq_decode false
 *
 * NOTE: intended for PQ-capture devices (HDR10 / HDR10_PLUS). On a device that can only capture
 * HLG10, flip this OFF so the HLG decode is used (the frames are genuinely HLG there).
 */
internal object NdrPqDecodeFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
