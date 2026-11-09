package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase-0 characterization golden lock for AllIntraHevcPolicy (s6-f03): pins today's AllIntraConfig
 * defaults, bitrate-tier constants, and resolveEffectiveBitrate clamp behavior BEFORE the additive
 * AV1/Main12/floor-climb helpers land, so the no-downgrade default is provable. Every expected value
 * is read from the live data-class defaults / the live coerceIn formula, not invented.
 */
class AllIntraHevcPolicyGoldenTest {

    @Test
    fun allIntraConfigDefaultsAreGoldenLocked() {
        val cfg = AllIntraHevcPolicy.AllIntraConfig(bitrate = 1, profile = 1, level = null, frameRate = 30)
        assertEquals("video/hevc", cfg.mime)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, cfg.bitrateMode)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, cfg.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, cfg.colorTransfer)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, cfg.colorRange)
        // Stored field is 1f but applyToFormat always writes KEY_I_FRAME_INTERVAL = 0 (the real
        // all-intra signal); this golden documents the dead-metadata value, not the applied one.
        assertEquals(1f, cfg.iFrameIntervalSeconds, 0f)
    }

    @Test
    fun bitrateTierConstantsAreUnchanged() {
        assertEquals(180_000_000, AllIntraHevcPolicy.BITRATE_CINEMA_MASTER)
        assertEquals(120_000_000, AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA)
        assertEquals(100_000_000, AllIntraHevcPolicy.BITRATE_STANDARD)
        assertEquals(80_000_000, AllIntraHevcPolicy.BITRATE_ECONOMY)
    }

    @Test
    fun resolveEffectiveBitrateClampsToUpperWhenPreferredExceedsCap() {
        assertEquals(
            600_000_000,
            AllIntraHevcPolicy.resolveEffectiveBitrate(
                preferredBitrate = 800_000_000,
                capabilityLowerBound = 64_000,
                capabilityUpperBound = 600_000_000
            )
        )
    }

    @Test
    fun resolveEffectiveBitrateFloorsToEconomyWhenPreferredBelowPolicyFloor() {
        // policyFloor = BITRATE_ECONOMY (80M), clamped within [lower, upper].
        assertEquals(
            80_000_000,
            AllIntraHevcPolicy.resolveEffectiveBitrate(
                preferredBitrate = 60_000_000,
                capabilityLowerBound = 64_000,
                capabilityUpperBound = 600_000_000
            )
        )
    }

    @Test
    fun resolveEffectiveBitrateReturnsPreferredWithinRange() {
        assertEquals(
            150_000_000,
            AllIntraHevcPolicy.resolveEffectiveBitrate(
                preferredBitrate = 150_000_000,
                capabilityLowerBound = 64_000,
                capabilityUpperBound = 600_000_000
            )
        )
    }
}
