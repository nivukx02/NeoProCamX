package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CP-QV8 / F08 — High-frame-rate encoder-level golden lock (TEST-ONLY, no-downgrade anchor).
 *
 * Pins [RecordingVideoEncoderPlanResolver.resolvePlan]'s `.level` output for the HFR matrix
 * (4K120 / 1080p120 / 720p240 across HEVC, AVC, AV1) to today's ACTUAL values, so any future
 * change to the resolver's level breakpoints is caught before it ships. These are the real
 * outputs verified against the resolver's private `resolveHevcLevel` / `resolveAvcLevel` /
 * `resolveAv1Level` breakpoint tables — none of the mastering-tier level overrides fire here
 * (default `eliteQualityTier == null`), so the plain breakpoint ladder governs.
 */
class HfrLevelResolveTest {

    private fun sdrRec709(): RecordingColorEncodingConfig =
        RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = false
        )

    private fun resolveLevel(mime: String, width: Int, height: Int, fps: Int): Int? =
        RecordingVideoEncoderPlanResolver.resolvePlan(
            mime = mime,
            width = width,
            height = height,
            fps = fps,
            requestedBitrate = 100_000_000,
            tenBitEnabled = false,
            colorEncoding = sdrRec709(),
            hardwareTenBitSupported = true,
            av1Main10Profile = null,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            minimumBitrateFloorFor = { _, _ -> 50_000_000 },
            clampBitrate = { it }
        ).level

    @Test
    fun hevc4k120_locksToHighTierLevel61() {
        // pixels=8_294_400, sps=995_328_000 > 3840*2160*60 (497_664_000) -> L6.1
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61,
            resolveLevel("video/hevc", 3840, 2160, 120)
        )
    }

    @Test
    fun hevc1080p120_locksToHighTierLevel4() {
        // pixels=2_073_600, sps=248_832_000: not > 497_664_000, not > 248_832_000 (equal),
        // pixels < 4K, pixels >= 1920*1080 -> L4 (the KNOWN under-level gap, characterized separately).
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4,
            resolveLevel("video/hevc", 1920, 1080, 120)
        )
    }

    @Test
    fun hevc720p240_locksToHighTierLevel31() {
        // pixels=921_600, sps=221_184_000: below every higher breakpoint and pixels < 1920*1080 -> L3.1
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31,
            resolveLevel("video/hevc", 1280, 720, 240)
        )
    }

    @Test
    fun avc1080p120_locksToAvcLevel42() {
        // pixels=2_073_600, sps=248_832_000: not > 497_664_000/248_832_000, pixels < 4K,
        // sps > 1920*1080*30 (62_208_000) -> AVC Level 4.2
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel42,
            resolveLevel("video/avc", 1920, 1080, 120)
        )
    }

    @Test
    fun av1_1080p120_locksToAv1Level41() {
        // pixels=2_073_600, sps=248_832_000: below 8K breakpoints, not > 3840*2160*30 (equal),
        // pixels < 4K, sps > 1920*1080*30 (62_208_000) -> AV1 Level 4.1 (requires sdkInt >= Q).
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AV1Level41,
            resolveLevel("video/av01", 1920, 1080, 120)
        )
    }
}
