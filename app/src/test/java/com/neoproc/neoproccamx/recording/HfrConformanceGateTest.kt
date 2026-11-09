package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.cinema.CinemaFrameRateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-QV8 / F08 — HFR conformance gate (TEST-ONLY, honest characterization).
 *
 * Cross-checks the live [RecordingVideoEncoderPlanResolver] HFR level output against the published
 * HEVC Annex-A [HevcLevelSpecTable] caps and asserts the HFR slow-motion catalog metadata is honest:
 *
 *  1. 1080p120 HEVC is re-asserted as the KNOWN under-level conformance gap (resolver L4 < spec L5),
 *     locked as a DEVICE-GATED characterization rather than silently corrected (no-downgrade).
 *  2. 4K120 HEVC IS conformant (resolver level >= spec-minimal level).
 *  3. The slow-motion HFR catalog entries are honest metadata: exactly {120, 240} fps, all flagged
 *     `isSlowMotion` and none flagged `is4KCapable`.
 */
class HfrConformanceGateTest {

    private fun sdrRec709(): RecordingColorEncodingConfig =
        RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = false
        )

    private fun resolverHevcLevel(width: Int, height: Int, fps: Int): Int =
        RecordingVideoEncoderPlanResolver.resolvePlan(
            mime = "video/hevc",
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
        ).level!!

    private fun pixels(width: Int, height: Int): Long = width.toLong() * height.toLong()

    private fun samplesPerSecond(width: Int, height: Int, fps: Int): Long =
        pixels(width, height) * fps.toLong()

    @Test
    fun hevc1080p120_isKnownUnderLevelGap_deviceGatedCharacterization() {
        // KNOWN GAP (DEVICE-GATED): 1080p120 sample-rate 248_832_000 needs L5, but the live resolver
        // emits L4. Fixing it would change live level output for an existing input, so it is locked
        // here as a characterized gap rather than silently corrected.
        val resolver = resolverHevcLevel(1920, 1080, 120)
        val minimal = HevcLevelSpecTable.minimalConformantHevcLevel(
            pixels(1920, 1080),
            samplesPerSecond(1920, 1080, 120)
        )!!
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4, resolver)
        assertEquals("5", minimal.label)
        assertTrue(
            "documented under-level gap: resolver L4 is below the spec-minimal L5",
            HevcLevelSpecTable.ordinalOf(resolver) < HevcLevelSpecTable.ordinalOf(minimal.constant)
        )
        assertFalse(
            "1080p120 HEVC must NOT be marked conformant while the gap stands",
            HevcLevelSpecTable.isConformant(
                resolver,
                pixels(1920, 1080),
                samplesPerSecond(1920, 1080, 120)
            )
        )
    }

    @Test
    fun hevc4k120_isConformant() {
        // 4K120 sample-rate 995_328_000 -> spec-minimal L5.2; resolver emits L6.1 (over-level, safe).
        val resolver = resolverHevcLevel(3840, 2160, 120)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, resolver)
        assertTrue(
            "resolver level for 4K120 must be >= spec-minimal conformant level",
            HevcLevelSpecTable.isConformant(
                resolver,
                pixels(3840, 2160),
                samplesPerSecond(3840, 2160, 120)
            )
        )
    }

    @Test
    fun catalogSlowMotionEntries_areHonestHfrMetadata() {
        val slowMotion = CinemaFrameRateCatalog.getSlowMotionFrameRates()
        // Exactly the two HFR slow-motion rates, in catalog order.
        assertEquals(listOf(120, 240), slowMotion.map { it.fps.toInt() })
        slowMotion.forEach { frameRate ->
            assertTrue(
                "${frameRate.displayName} must be flagged isSlowMotion",
                frameRate.isSlowMotion
            )
            assertFalse(
                "${frameRate.displayName} must NOT claim 4K-capable HFR",
                frameRate.is4KCapable
            )
        }
    }
}
