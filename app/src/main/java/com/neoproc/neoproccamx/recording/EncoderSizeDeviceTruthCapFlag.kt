package com.neoproc.neoproccamx.recording

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  ENCODER-SIZE DEVICE-TRUTH CAP FLAG  (reversible A/B, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Gates the encoder-size acceptance test in [RecordingManager.resolveVideoConfig].
 *
 * The legacy accept predicate uses [android.media.MediaCodecInfo.VideoCapabilities.isSizeSupported],
 * which only validates spatial width/height bounds and IGNORES the encoder's level / macroblock-
 * throughput ceiling. On full-sensor RAW recording the app requests the sensor pixel-array size
 * (e.g. 8192×6144); isSizeSupported wrongly reports it usable, so that size is committed to the
 * hardware HEVC encoder, MediaCodec.configure is later rejected DURING the camera-session rebuild,
 * and recording-start falls into a ~24 s retry cascade that never reaches the Recording state
 * (HealthMonitor loops WARNING→ERROR "Encoder pipeline error").
 *
 * When ON (default), the accept predicate additionally requires the honest device-truth
 * [android.media.MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported] at the ALREADY-CLAMPED
 * frame rate, so an unencodable size is rejected up front and falls through to the existing
 * capability-clamped fallback (a size the platform guarantees is configurable) BEFORE the expensive
 * session rebuild.
 *
 * NO-DOWNGRADE / reversible:
 *  • Any size the encoder can actually configure at its clamped rate passes BOTH predicates, so it
 *    resolves to the identical [ResolvedVideoConfig] (same size, same clamped fps, same exact-match
 *    flags) — every currently-working size is untouched.
 *  • The rate is the value already computed by clampFrameRate, NOT the raw request, so a supported
 *    size that merely needs fps-clamping is NOT over-rejected.
 *  • Only the reject-edge (a size that passes the spatial-only test but cannot run at any usable
 *    cinema rate) newly falls to the fallback.
 *  • This caps ONLY the companion HEVC encoder surface. The RAW deliverable is written by a separate
 *    RAW_SENSOR reader/writer path (resolveRecordingReadoutOverrideSize); RAW resolution is untouched.
 *  • Set OFF ⇒ byte-identical to the prior isSizeSupported-only behaviour for ALL sizes.
 *
 * Runtime A/B (debug builds), applied on the next recording-start (resolveVideoConfig re-reads it):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.encoder_size_device_truth_cap true
 */
internal object EncoderSizeDeviceTruthCapFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * Pure (JVM-unit-testable) accept decision for an encoder candidate at a given size — see
 * [EncoderSizeDeviceTruthCapFlag].
 *
 * [sizeAndRateSupported] is a lambda so the (device-truth) check is evaluated ONLY when it can change
 * the outcome: never when the size is not even spatially supported, and never when the flag is OFF.
 * That keeps the flag-OFF path byte-identical to the legacy spatial-only predicate — the device-truth
 * framework call is not made at all.
 */
internal object EncoderSizeAcceptancePolicy {

    inline fun accepts(
        spatiallySupported: Boolean,
        deviceTruthCapEnabled: Boolean = EncoderSizeDeviceTruthCapFlag.enabled,
        sizeAndRateSupported: () -> Boolean
    ): Boolean {
        if (!spatiallySupported) return false          // never acceptable — matches legacy.
        if (!deviceTruthCapEnabled) return true        // legacy behaviour: spatial-only.
        return sizeAndRateSupported()                  // device-truth: must run at the clamped rate.
    }
}
