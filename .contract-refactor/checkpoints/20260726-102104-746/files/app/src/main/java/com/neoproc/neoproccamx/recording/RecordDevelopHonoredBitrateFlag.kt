package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopHonoredBitrateFlag — reversible A/B lever (default ON) for the SDR/NDR develop HEVC encoder.
 *
 * THE bitrate fix. [RecordDevelopHevcMovEncoder] computes a high target bitrate (~286 Mbps at 4K30 via
 * HIGH_QUALITY_BITS_PER_PIXEL=1.15, floor 180 Mbps) and writes it to KEY_BIT_RATE, but the legacy
 * `setSupportedBitrateMode` selected BITRATE_MODE_CQ FIRST and never set KEY_QUALITY. Under CQ the encoder
 * IGNORES KEY_BIT_RATE and collapses to its own low internal default quality → the low-bitrate output.
 *
 * ON  => prefer VBR → CBR → CQ so the computed KEY_BIT_RATE is HONORED (virtually every HW HEVC encoder
 *        supports VBR). If CQ is the only supported mode it now carries a near-max KEY_QUALITY
 *        (CQ_QUALITY = 100, parity with the live cinema record path) so it does not collapse.
 * OFF => byte-identical legacy CQ-first order (for A/B).
 *
 * NO-DOWNGRADE: turning this ON can only RAISE the encoded bitrate/quality toward the already-computed
 * target; it never lowers it. The develop-conversion output file is the only thing affected — the live
 * record path is untouched.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.develop_honored_bitrate true
 */
internal object RecordDevelopHonoredBitrateFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
