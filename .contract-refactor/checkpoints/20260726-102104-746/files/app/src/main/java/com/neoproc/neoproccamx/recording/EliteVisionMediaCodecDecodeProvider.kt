package com.neoproc.neoproccamx.recording

/**
 * s8-f06 / CP-Q8 — honest-dormant, DEVICE-GATED source for the real decoded master frames. The
 * actual decode (MediaExtractor → MediaCodec → ImageReader over the just-recorded / exported file)
 * cannot run under the pure-JVM unit suite — Robolectric is banned and MediaCodec is not mockable —
 * so this provider is dormant by construction: it returns no frames and declares it cannot measure,
 * which [EliteVisionProductionMeasurement] turns into an honest NOT_PROVEN. It is NEVER a
 * synthetic-as-real PASS, and (unlike a silently-missing path) it documents the exact on-device
 * enable procedure.
 *
 * On-device enable (owner-run, device-gated — out of scope for the JVM-green commit):
 *   1. adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND --ez debug.elite_vision_measure true
 *   2. Record (or NPV10-export) a master while framing an ISO-12233 slanted-edge + ColorChecker chart.
 *   3. A device decode step decodes N frames into an [EliteVisionMeasurementHarness.GoldenFrames] and
 *      wraps it in [EliteVisionByteBufferFrameProvider] (canMeasureAllRequiredCriteria = true) at the
 *      record-finalize seam, replacing [EliteVisionDecodedFrameProvider.Unavailable].
 * Until that device path is wired, the finalize seams keep using
 * [EliteVisionDecodedFrameProvider.Unavailable], so live behaviour is unchanged.
 */
internal object EliteVisionMediaCodecDecodeProvider : EliteVisionDecodedFrameProvider {

    /** Distinguishes this dormant device-gated source from the generic Unavailable in diagnostics. */
    const val LABEL_DEVICE_GATED = "NPV10_EXPORT_DECODE_DEVICE_GATED"

    override val producerLabel: String = LABEL_DEVICE_GATED
    override val canMeasureAllRequiredCriteria: Boolean = false

    // Dormant: the real Android decode is wired device-side behind EliteVisionMeasurementFlag.
    override fun framesOrNull(): EliteVisionMeasurementHarness.GoldenFrames? = null
}
