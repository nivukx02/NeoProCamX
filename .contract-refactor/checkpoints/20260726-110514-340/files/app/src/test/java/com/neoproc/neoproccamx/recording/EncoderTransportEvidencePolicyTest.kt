package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy tests for the s6-f01 encoder transport-truth evidence. No MediaCodec/MediaFormat
 * runtime is exercised — only the deterministic decision logic. Honest-gate behavior (NOT_PROVEN
 * for unavailable read-back, MISMATCH for faked profile / Rec.709-tagged HDR) is the focus.
 */
class EncoderTransportEvidencePolicyTest {

    private val main10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
    private val main = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
    private val l51 = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51

    private fun ev(
        requestedProfile: Int = main10,
        muxedProfile: Int? = main10,
        requestedLevel: Int = l51,
        muxedLevel: Int? = l51,
        requestedBitrate: Int = 300_000_000,
        muxedBitrate: Int? = 900_000_000,
        requestedColorStandard: Int = MediaFormat.COLOR_STANDARD_BT2020,
        muxedColorStandard: Int = MediaFormat.COLOR_STANDARD_BT2020,
        requestedColorTransfer: Int = MediaFormat.COLOR_TRANSFER_ST2084,
        muxedColorTransfer: Int = MediaFormat.COLOR_TRANSFER_ST2084,
        requestedColorRange: Int = MediaFormat.COLOR_RANGE_FULL,
        muxedColorRange: Int = MediaFormat.COLOR_RANGE_FULL,
        width: Int = 3840,
        height: Int = 2160,
        inputFrameCount: Long? = null,
        muxedFrameCount: Long = 1800L,
        droppedMuxerSamples: Int = 0,
        maxAvSyncDriftMs: Long = 0L,
        avSyncToleranceMs: Long = 100L
    ) = EncoderTransportEvidencePolicy.build(
        requestedProfile = requestedProfile,
        requestedLevel = requestedLevel,
        requestedBitrateBps = requestedBitrate,
        requestedColorStandard = requestedColorStandard,
        requestedColorTransfer = requestedColorTransfer,
        requestedColorRange = requestedColorRange,
        muxedProfile = muxedProfile,
        muxedLevel = muxedLevel,
        muxedBitrateBps = muxedBitrate,
        muxedColorStandard = muxedColorStandard,
        muxedColorTransfer = muxedColorTransfer,
        muxedColorRange = muxedColorRange,
        width = width,
        height = height,
        inputFrameCount = inputFrameCount,
        muxedFrameCount = muxedFrameCount,
        droppedMuxerSamples = droppedMuxerSamples,
        maxAvSyncDriftMs = maxAvSyncDriftMs,
        avSyncToleranceMs = avSyncToleranceMs
    )

    @Test
    fun bitrateFloorBpsFor4kReturns820Mbps() {
        assertEquals(820_000_000, EncoderTransportEvidencePolicy.bitrateFloorBpsFor(3840, 2160))
    }

    @Test
    fun bitrateFloorBpsFor1080pReturns340Mbps() {
        assertEquals(340_000_000, EncoderTransportEvidencePolicy.bitrateFloorBpsFor(1920, 1080))
    }

    @Test
    fun bitrateFloorBpsForUnknownResolutionReturnsZeroFloorNotApplicable() {
        assertEquals(0, EncoderTransportEvidencePolicy.bitrateFloorBpsFor(1280, 720))
    }

    @Test
    fun main10RequestedAndMuxedMain10ProfileProvenStatusProven() {
        val e = ev(requestedProfile = main10, muxedProfile = main10)
        assertTrue(e.profileProven)
        assertEquals(EncoderTransportEvidencePolicy.STATUS_PROVEN, e.status)
    }

    @Test
    fun main10RequestedButMuxedMainProfileProvenFalseStatusMismatch() {
        val e = ev(requestedProfile = main10, muxedProfile = main)
        assertFalse(e.profileProven)
        assertEquals(EncoderTransportEvidencePolicy.STATUS_MISMATCH, e.status)
    }

    @Test
    fun muxedBitrateAboveFloorBelowBitrateFloorFalse() {
        assertFalse(ev(muxedBitrate = 900_000_000).belowBitrateFloor)
    }

    @Test
    fun muxedBitrateBelowFloorBelowBitrateFloorTrue() {
        // 4K floor is 820 Mbps; an honestly-measured 500 Mbps is below floor.
        assertTrue(ev(muxedBitrate = 500_000_000).belowBitrateFloor)
    }

    @Test
    fun colorKeysMatchColorProvenTrue() {
        assertTrue(ev().colorProven)
    }

    @Test
    fun hdrTransferTaggedAsRec709ColorProvenFalseStatusMismatch() {
        // Requested PQ/HDR but the MOV atom carries Rec.709 transfer -> caught as MISMATCH.
        val e = ev(
            requestedColorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            muxedColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        assertFalse(e.colorProven)
        assertEquals(EncoderTransportEvidencePolicy.STATUS_MISMATCH, e.status)
    }

    @Test
    fun noReadBackAllProvenFalseStatusNotProven() {
        val e = ev(muxedProfile = null, muxedLevel = null, muxedBitrate = null)
        assertFalse(e.encoderOutputReadBackAvailable)
        assertFalse(e.profileProven)
        assertFalse(e.levelProven)
        assertFalse(e.belowBitrateFloor) // floor check not applicable without a measured bitrate
        assertEquals(EncoderTransportEvidencePolicy.STATUS_NOT_PROVEN, e.status)
    }

    @Test
    fun inputEqualsMuxedZeroDropsZeroDropProvenTrueAvSyncWithinBound() {
        val e = ev(inputFrameCount = 1800L, muxedFrameCount = 1800L, droppedMuxerSamples = 0)
        assertEquals(0L, e.headFrameGap)
        assertTrue(e.zeroDropProven)
        assertTrue(e.avSyncWithinBound)
    }

    @Test
    fun inputExceedsMuxedHeadFrameGapAccountedZeroDropProvenFalse() {
        val e = ev(inputFrameCount = 1805L, muxedFrameCount = 1800L)
        assertEquals(5L, e.headFrameGap)
        assertFalse(e.zeroDropProven)
    }

    @Test
    fun surfaceModeInputUnobservableFrameDropNotProven() {
        val e = ev(inputFrameCount = null, muxedFrameCount = 1800L)
        assertFalse(e.frameDropAccountingAvailable)
        assertEquals(-1L, e.headFrameGap)
        assertFalse(e.zeroDropProven)
    }

    @Test
    fun avDriftExceedsToleranceAvSyncWithinBoundFalse() {
        assertFalse(ev(maxAvSyncDriftMs = 150L, avSyncToleranceMs = 100L).avSyncWithinBound)
    }

    @Test
    fun toMetadataEmitsHonestKeysNeverSynthesizedPassAndNoGateFlipPrefix() {
        val provenMeta = EncoderTransportEvidencePolicy.toMetadata(ev())
        assertEquals(EncoderTransportEvidencePolicy.STATUS_PROVEN, provenMeta["encoderTransportStatus"])

        val notProvenMeta = EncoderTransportEvidencePolicy.toMetadata(
            ev(muxedProfile = null, muxedLevel = null, muxedBitrate = null)
        )
        assertEquals(EncoderTransportEvidencePolicy.STATUS_NOT_PROVEN, notProvenMeta["encoderTransportStatus"])
        // Device-gated read-back emits the NOT_PROVEN sentinel, not a misleading 0.
        assertEquals("NOT_PROVEN", notProvenMeta["encoderTransportMuxedProfile"])
        // No key may start with the Tier-Omega gate-trigger prefixes (RecordingQualityEvidencePolicy).
        provenMeta.keys.forEach { key ->
            assertFalse(key, key.startsWith("tierOmega", ignoreCase = true))
            assertFalse(key, key.startsWith("beyondImax", ignoreCase = true))
        }
    }
}
