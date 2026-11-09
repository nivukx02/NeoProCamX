package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f03 tests for the additive all-intra reach helpers: AV1 all-intra (CP70), HEVC Main12 reach
 * (CP71), and cap-tracking mastering bitrate. Honest-fallback and over-claim guards are asserted at
 * the JVM boundary; true bitstream I-only / device support is DEVICE-GATED (vivo).
 */
class AllIntraAv1MasteringPolicyTest {

    // ── CP70: AV1 all-intra ────────────────────────────────────────────────────────────────────

    @Test
    fun computeAv1LevelAssignsLevelByLumaRate() {
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level5, AllIntraHevcPolicy.computeAv1Level(3840, 2160, 30))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level51, AllIntraHevcPolicy.computeAv1Level(3840, 2160, 45))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level4, AllIntraHevcPolicy.computeAv1Level(1920, 1080, 30))
    }

    @Test
    fun allIntraAv1ConfigBuildsCbrAvOneConfigWithAdvertisedMain10() {
        val cfg = AllIntraHevcPolicy.allIntraAv1Config(
            width = 3840,
            height = 2160,
            frameRate = 30,
            tenBit = true,
            av1Main10Profile = 4096
        )
        assertNotNull(cfg)
        assertEquals("video/av01", cfg!!.mime)
        assertEquals(4096, cfg.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level5, cfg.level)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, cfg.bitrateMode)
    }

    @Test
    fun allIntraAv1ConfigTenBitWithoutMain10ProfileReturnsNullNoSilentDowngrade() {
        assertNull(
            AllIntraHevcPolicy.allIntraAv1Config(3840, 2160, 30, tenBit = true, av1Main10Profile = null)
        )
    }

    @Test
    fun allIntraAv1ConfigEightBitNeedsAdvertisedMainProfile() {
        assertNull(AllIntraHevcPolicy.allIntraAv1Config(3840, 2160, 30, tenBit = false, av1MainProfile = null))
        assertNotNull(
            AllIntraHevcPolicy.allIntraAv1Config(3840, 2160, 30, tenBit = false, av1MainProfile = 1)
        )
    }

    @Test
    fun allIntraAv1ConfigTwelveBitIsNotAvailable() {
        // AV1 Main is 8/10-bit only — a 12-bit AV1 request is honestly NOT_AVAILABLE.
        assertNull(
            AllIntraHevcPolicy.allIntraAv1Config(3840, 2160, 30, twelveBit = true, av1Main10Profile = 4096)
        )
    }

    // ── CP71: HEVC Main12 reach ────────────────────────────────────────────────────────────────

    @Test
    fun resolveAllIntraHevcProfileSelectsMain12OnlyWhenAdvertised() {
        assertEquals(
            99_999,
            AllIntraHevcPolicy.resolveAllIntraHevcProfile(tenBit = true, twelveBit = true, hevcMain12Profile = 99_999)
        )
    }

    @Test
    fun resolveAllIntraHevcProfileHonestMain10FallbackWhenNoMain12() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            AllIntraHevcPolicy.resolveAllIntraHevcProfile(tenBit = true, twelveBit = true, hevcMain12Profile = null)
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            AllIntraHevcPolicy.resolveAllIntraHevcProfile(tenBit = true, twelveBit = false, hevcMain12Profile = null)
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
            AllIntraHevcPolicy.resolveAllIntraHevcProfile(tenBit = false, twelveBit = false, hevcMain12Profile = null)
        )
    }

    // ── Cap-tracking mastering bitrate ─────────────────────────────────────────────────────────

    @Test
    fun masteringPreferredBitrateClimbsTowardStrictFloorWhenEnabled() {
        // Live floor source is HighEndQualityPolicy.minimumBitrateBpsLong (4K@30 = 1_000_000_000).
        assertEquals(
            1_000_000_000,
            AllIntraHevcPolicy.masteringPreferredBitrate(
                requestedTierBps = AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA,
                strictFloorBps = 1_000_000_000L,
                climbTowardFloor = true
            )
        )
    }

    @Test
    fun masteringPreferredBitrateDefaultLeavesRequestedTierUnchanged() {
        assertEquals(
            AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA,
            AllIntraHevcPolicy.masteringPreferredBitrate(
                requestedTierBps = AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA,
                strictFloorBps = 1_000_000_000L,
                climbTowardFloor = false
            )
        )
    }

    @Test
    fun masteringPreferredBitrateSaturatesOverIntFloorAndStaysUnderCap() {
        // A floor beyond Int range (e.g. 8K@60 strict floor) must saturate, never wrap negative.
        val climbed = AllIntraHevcPolicy.masteringPreferredBitrate(
            requestedTierBps = AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA,
            strictFloorBps = 3_000_000_000L,
            climbTowardFloor = true
        )
        assertEquals(Int.MAX_VALUE, climbed)
        assertTrue(climbed > 0)
        // Downstream cap clamp keeps the over-Int request honest (never exceeds device cap).
        val capped = AllIntraHevcPolicy.resolveEffectiveBitrate(
            preferredBitrate = climbed,
            capabilityLowerBound = 64_000,
            capabilityUpperBound = 600_000_000
        )
        assertEquals(600_000_000, capped)
    }
}
