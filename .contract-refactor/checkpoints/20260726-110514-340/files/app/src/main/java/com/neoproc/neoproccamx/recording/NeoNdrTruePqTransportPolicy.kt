package com.neoproc.neoproccamx.recording

/**
 * Single source of truth for the NDR10+/NIVUKX/APEX HDR transport on the shipping offscreen-EGL
 * record route. NDR is the app's OWN technology; this flag decides whether it is delivered as
 * genuine BT.2100 PQ (ST.2084) or as coherent BT.2100 HLG. Both are 10-bit BT.2020 HDR — neither is
 * a quality/bit-depth/gamut downgrade of the other.
 *
 * Fork A (this policy [enabled] == true, the default): "make the pixels genuinely PQ". The offscreen
 * record shader applies a real HLG->scene-linear->OOTF->PQ (ST.2084) conversion, the EGL delivery
 * surface is bound BT.2020_PQ, and the encoder / colr atom / HDR10 static metadata / MediaStore
 * column / sidecar are ALL tagged ST.2084. Pixels and tags agree = genuine PQ NDR.
 *
 * Fork B fallback ([enabled] == false): coherent HLG. No shader conversion (pixels stay HLG), EGL
 * delivery stays BT.2020_HLG, and EVERY tag (encoder/colr/HDR10/MediaStore/sidecar) is HLG. Pixels
 * and tags still agree = coherent HLG. This exists as the safety net for the on-device A/B: if the
 * PQ conversion looks wrong on the target (the documented red-blown / absolute-luminance risk), flip
 * this off and recording is a clean, un-mismatched HLG HDR stream instead.
 *
 * NEITHER state is the pre-fix corruption (HLG pixels wearing a PQ tag). The two states are locked
 * coherent by every transport site reading THIS flag, so a mismatch can never ship.
 *
 * On-device A/B (vivo): `adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.ndr_true_pq true|false`
 * Verify with ffprobe (transfer_characteristics smpte2084 vs arib-std-b67) AND that the file plays
 * un-blown. Once PQ is confirmed on the device it is already the default; no further change needed.
 */
object NeoNdrTruePqTransportPolicy {
    @Volatile
    var enabled: Boolean = true

    fun set(value: Boolean) {
        enabled = value
    }
}
