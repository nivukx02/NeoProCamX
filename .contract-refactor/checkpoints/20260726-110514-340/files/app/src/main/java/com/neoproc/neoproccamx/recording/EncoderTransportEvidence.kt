package com.neoproc.neoproccamx.recording

/**
 * Honest transport-truth evidence for the live HEVC encoder + MOV muxer (s6-f01).
 *
 * PURE Kotlin over primitives, so it is fully unit-testable without MediaCodec/MediaFormat.
 * [RecordingManager] extracts the values — the requested encoder config, the start-time
 * `codec.outputFormat` read-back, and the muxed MOV color-atom source (`activeColorEncodingConfig`)
 * — and feeds them to [EncoderTransportEvidencePolicy.build]; the result is appended to the
 * recording quality certificate as additive `encoderTransport*` evidence.
 *
 * Honest by construction (OmegaCapabilityGate contract):
 *  - Encoder-output profile/level/bitrate read-back is vendor-dependent (`codec.outputFormat` may
 *    omit `KEY_PROFILE`/`KEY_LEVEL`/`KEY_BIT_RATE`, and is unavailable on the JVM). When a value was
 *    not read back it is passed as `null` here and lands NOT_PROVEN — never a synthesized PASS.
 *  - Color parity (requested ODT vs the MOV color atom) is always evaluable, so a Rec.709-tagged
 *    HDR/HLG file is caught as MISMATCH.
 *  - Frame-drop accounting needs an observable input-frame count; the GPU encoder-surface path has
 *    none (the presentation queue is CONFLATED), so surface-mode input count is NOT_PROVEN.
 */
internal data class EncoderTransportEvidence(
    val status: String,
    val encoderOutputReadBackAvailable: Boolean,
    val requestedProfile: Int,
    val muxedProfile: Int,
    val profileProven: Boolean,
    val requestedLevel: Int,
    val muxedLevel: Int,
    val levelProven: Boolean,
    val requestedBitrateBps: Int,
    val muxedBitrateBps: Int,
    val bitrateFloorBps: Int,
    val belowBitrateFloor: Boolean,
    val requestedColorStandard: Int,
    val muxedColorStandard: Int,
    val requestedColorTransfer: Int,
    val muxedColorTransfer: Int,
    val requestedColorRange: Int,
    val muxedColorRange: Int,
    val colorProven: Boolean,
    val frameDropAccountingAvailable: Boolean,
    val inputFrameCount: Long,
    val muxedFrameCount: Long,
    val headFrameGap: Long,
    val droppedMuxerSamples: Int,
    val zeroDropProven: Boolean,
    val maxAvSyncDriftMs: Long,
    val avSyncToleranceMs: Long,
    val avSyncWithinBound: Boolean,
)

internal object EncoderTransportEvidencePolicy {

    /** Mastering bitrate floors mirrored from `ImaxStrictProfile` (4K = 820 Mbps, 1080p = 340 Mbps). */
    const val FOUR_K_BITRATE_FLOOR_BPS = 820_000_000
    const val FHD_BITRATE_FLOOR_BPS = 340_000_000

    const val STATUS_PROVEN = "PROVEN"
    const val STATUS_MISMATCH = "MISMATCH"
    const val STATUS_NOT_PROVEN = "NOT_PROVEN"

    /** Strict mastering bitrate floor by pixel tier; 0 (= floor check not applicable) below 1080p. */
    fun bitrateFloorBpsFor(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        return when {
            pixels >= 3840L * 2160L -> FOUR_K_BITRATE_FLOOR_BPS
            pixels >= 1920L * 1080L -> FHD_BITRATE_FLOOR_BPS
            else -> 0
        }
    }

    /**
     * Derives the transport-truth verdict. `muxed{Profile,Level,BitrateBps}` are nullable: pass
     * `null` when the encoder did not report that key (→ NOT_PROVEN, never a fabricated match).
     * `inputFrameCount` is nullable: pass `null` for the GPU-surface path (→ frame-drop NOT_PROVEN).
     */
    fun build(
        requestedProfile: Int,
        requestedLevel: Int,
        requestedBitrateBps: Int,
        requestedColorStandard: Int,
        requestedColorTransfer: Int,
        requestedColorRange: Int,
        muxedProfile: Int?,
        muxedLevel: Int?,
        muxedBitrateBps: Int?,
        muxedColorStandard: Int,
        muxedColorTransfer: Int,
        muxedColorRange: Int,
        width: Int,
        height: Int,
        inputFrameCount: Long?,
        muxedFrameCount: Long,
        droppedMuxerSamples: Int,
        maxAvSyncDriftMs: Long,
        avSyncToleranceMs: Long
    ): EncoderTransportEvidence {
        val readBack = muxedProfile != null || muxedLevel != null || muxedBitrateBps != null
        val profileProven = muxedProfile != null && muxedProfile == requestedProfile
        val levelProven = muxedLevel != null && muxedLevel == requestedLevel
        val floor = bitrateFloorBpsFor(width, height)
        val belowFloor = muxedBitrateBps != null && floor > 0 && muxedBitrateBps < floor
        val colorProven =
            muxedColorStandard == requestedColorStandard &&
                muxedColorTransfer == requestedColorTransfer &&
                muxedColorRange == requestedColorRange
        val frameAccounting = inputFrameCount != null
        val gap = if (inputFrameCount != null) (inputFrameCount - muxedFrameCount).coerceAtLeast(0L) else -1L
        val zeroDropProven = frameAccounting && gap == 0L && droppedMuxerSamples == 0
        val avWithin = maxAvSyncDriftMs <= avSyncToleranceMs
        val status = when {
            !colorProven -> STATUS_MISMATCH
            readBack && !profileProven -> STATUS_MISMATCH
            !readBack -> STATUS_NOT_PROVEN
            else -> STATUS_PROVEN
        }
        return EncoderTransportEvidence(
            status = status,
            encoderOutputReadBackAvailable = readBack,
            requestedProfile = requestedProfile,
            muxedProfile = muxedProfile ?: -1,
            profileProven = profileProven,
            requestedLevel = requestedLevel,
            muxedLevel = muxedLevel ?: -1,
            levelProven = levelProven,
            requestedBitrateBps = requestedBitrateBps,
            muxedBitrateBps = muxedBitrateBps ?: -1,
            bitrateFloorBps = floor,
            belowBitrateFloor = belowFloor,
            requestedColorStandard = requestedColorStandard,
            muxedColorStandard = muxedColorStandard,
            requestedColorTransfer = requestedColorTransfer,
            muxedColorTransfer = muxedColorTransfer,
            requestedColorRange = requestedColorRange,
            muxedColorRange = muxedColorRange,
            colorProven = colorProven,
            frameDropAccountingAvailable = frameAccounting,
            inputFrameCount = inputFrameCount ?: -1L,
            muxedFrameCount = muxedFrameCount,
            headFrameGap = gap,
            droppedMuxerSamples = droppedMuxerSamples,
            zeroDropProven = zeroDropProven,
            maxAvSyncDriftMs = maxAvSyncDriftMs,
            avSyncToleranceMs = avSyncToleranceMs,
            avSyncWithinBound = avWithin
        )
    }

    /**
     * Honest string-map evidence for the recording quality certificate. All keys are prefixed
     * `encoderTransport` (never `tierOmega`/`beyondImax`), so they cannot flip the Tier-Omega gate.
     * Device-gated values emit the literal `NOT_PROVEN` sentinel rather than a misleading 0.
     */
    fun toMetadata(e: EncoderTransportEvidence): Map<String, String> = mapOf(
        "encoderTransportStatus" to e.status,
        "encoderTransportReadBackAvailable" to e.encoderOutputReadBackAvailable.toString(),
        "encoderTransportRequestedProfile" to e.requestedProfile.toString(),
        "encoderTransportMuxedProfile" to if (e.muxedProfile < 0) STATUS_NOT_PROVEN else e.muxedProfile.toString(),
        "encoderTransportProfileProven" to e.profileProven.toString(),
        "encoderTransportRequestedLevel" to e.requestedLevel.toString(),
        "encoderTransportMuxedLevel" to if (e.muxedLevel < 0) STATUS_NOT_PROVEN else e.muxedLevel.toString(),
        "encoderTransportLevelProven" to e.levelProven.toString(),
        "encoderTransportRequestedBitrateBps" to e.requestedBitrateBps.toString(),
        "encoderTransportMuxedBitrateBps" to if (e.muxedBitrateBps < 0) STATUS_NOT_PROVEN else e.muxedBitrateBps.toString(),
        "encoderTransportBitrateFloorBps" to e.bitrateFloorBps.toString(),
        "encoderTransportBelowBitrateFloor" to e.belowBitrateFloor.toString(),
        "encoderTransportRequestedColorStandard" to e.requestedColorStandard.toString(),
        "encoderTransportMuxedColorStandard" to e.muxedColorStandard.toString(),
        "encoderTransportRequestedColorTransfer" to e.requestedColorTransfer.toString(),
        "encoderTransportMuxedColorTransfer" to e.muxedColorTransfer.toString(),
        "encoderTransportRequestedColorRange" to e.requestedColorRange.toString(),
        "encoderTransportMuxedColorRange" to e.muxedColorRange.toString(),
        "encoderTransportColorProven" to e.colorProven.toString(),
        "encoderTransportFrameDropAccountingAvailable" to e.frameDropAccountingAvailable.toString(),
        "encoderTransportInputFrameCount" to if (e.inputFrameCount < 0) STATUS_NOT_PROVEN else e.inputFrameCount.toString(),
        "encoderTransportMuxedFrameCount" to e.muxedFrameCount.toString(),
        "encoderTransportHeadFrameGap" to if (e.headFrameGap < 0) STATUS_NOT_PROVEN else e.headFrameGap.toString(),
        "encoderTransportDroppedMuxerSamples" to e.droppedMuxerSamples.toString(),
        "encoderTransportZeroDropProven" to e.zeroDropProven.toString(),
        "encoderTransportMaxAvSyncDriftMs" to e.maxAvSyncDriftMs.toString(),
        "encoderTransportAvSyncWithinBound" to e.avSyncWithinBound.toString()
    )
}
