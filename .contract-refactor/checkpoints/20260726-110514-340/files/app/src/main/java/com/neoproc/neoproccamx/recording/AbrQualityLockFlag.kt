package com.neoproc.neoproccamx.recording

/**
 * AbrQualityLockFlag — reversible lock that keeps the runtime ABR (adaptive-bitrate) backpressure
 * path from ever LOWERING recorded quality on the auto VIDEO/PRO tiers.
 *
 * The ABR policy ([RecordingAbrPolicy]) reacts to encoder/muxer backpressure by dropping the target
 * bitrate AND scaling shader entropy / detail-gain / dither below 1.0 — a genuine detail downgrade.
 * The shipped code already locks that off for cinema/elite tiers; this flag extends the same lock to
 * the ordinary VIDEO/PRO tiers. On the vivo offscreen-EGL path the encoder submit is ~0.66ms (ample
 * headroom), so ABR reduction is not needed to protect pacing, and disabling it also removes the
 * per-frame inline `codec.setParameters` call from the drain thread (when reduction is off the policy
 * returns shouldSetCodecParameters=false), removing that stall too.
 *
 * NO-DOWNGRADE: turning this ON can only PREVENT an automatic bitrate/detail reduction — it never
 * lowers quality. It is the opposite of a battery optimisation (it keeps the encoder working harder).
 * Manual bitrate selection is always respected. Default ON to honour the strict no-downgrade intent;
 * reversible for on-device A/B (flip OFF to restore the shipped adaptive behaviour):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.abr_quality_lock false
 */
object AbrQualityLockFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
