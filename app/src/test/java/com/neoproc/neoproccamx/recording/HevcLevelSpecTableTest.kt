package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f02 level audit: locks the published HEVC Annex-A MaxLumaPS/MaxLumaSR constants and proves the
 * live resolver stays at or above the spec-minimal conformant level across the matrix. The resolver
 * is deliberately NOT changed; where it over-levels (8K30) that is conformance-safe, and the one
 * under-level corner (1080p120) is documented here as a KNOWN, DEVICE-GATED conformance gap.
 */
class HevcLevelSpecTableTest {

    private fun resolverHevcLevel(width: Int, height: Int, fps: Int): Int? =
        RecordingVideoEncoderPlanResolver.resolvePlan(
            mime = "video/hevc",
            width = width,
            height = height,
            fps = fps,
            requestedBitrate = 100_000_000,
            tenBitEnabled = false,
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = false,
                main10Requested = false
            ),
            hardwareTenBitSupported = true,
            av1Main10Profile = null,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            minimumBitrateFloorFor = { _, _ -> 50_000_000 },
            clampBitrate = { it }
        ).level

    private fun pixels(width: Int, height: Int): Long = width.toLong() * height.toLong()

    private fun samplesPerSecond(width: Int, height: Int, fps: Int): Long =
        pixels(width, height) * fps.toLong()

    @Test
    fun specTable_encodesPublishedMaxLumaPsAndMaxLumaSr() {
        fun level(label: String) = HevcLevelSpecTable.HIGH_TIER_LADDER.first { it.label == label }
        // ITU-T H.265 Annex A, Table A.8 (MaxLumaPS) / Table A.9 (MaxLumaSR).
        assertEquals(2_228_224L, level("4").maxLumaPs)
        assertEquals(66_846_720L, level("4").maxLumaSr)
        assertEquals(133_693_440L, level("4.1").maxLumaSr)
        assertEquals(8_912_896L, level("5").maxLumaPs)
        assertEquals(267_386_880L, level("5").maxLumaSr)
        assertEquals(534_773_760L, level("5.1").maxLumaSr)
        assertEquals(1_069_547_520L, level("5.2").maxLumaSr)
        assertEquals(35_651_584L, level("6").maxLumaPs)
        assertEquals(1_069_547_520L, level("6").maxLumaSr)
        assertEquals(2_139_095_040L, level("6.1").maxLumaSr)
        assertEquals(4_278_190_080L, level("6.2").maxLumaSr)
    }

    @Test
    fun minimalConformantLevel_isComputedFromTheSpecCaps() {
        // 4K60 needs L5.1 (L5 sample-rate ceiling 267M < 497M); 8K30 fits L6.
        assertEquals(
            "5.1",
            HevcLevelSpecTable.minimalConformantHevcLevel(pixels(3840, 2160), samplesPerSecond(3840, 2160, 60))!!.label
        )
        assertEquals(
            "5",
            HevcLevelSpecTable.minimalConformantHevcLevel(pixels(3840, 2160), samplesPerSecond(3840, 2160, 30))!!.label
        )
        assertEquals(
            "6",
            HevcLevelSpecTable.minimalConformantHevcLevel(pixels(7680, 4320), samplesPerSecond(7680, 4320, 30))!!.label
        )
        // 1080p120 needs L5 (sample-rate 248M exceeds both L4=66M and L4.1=133M).
        assertEquals(
            "5",
            HevcLevelSpecTable.minimalConformantHevcLevel(pixels(1920, 1080), samplesPerSecond(1920, 1080, 120))!!.label
        )
    }

    @Test
    fun resolverHevcLevelMatchesTodayAcrossMatrix() {
        // Golden lock of the live resolver's current level output (no-downgrade anchor).
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5, resolverHevcLevel(3840, 2160, 30))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51, resolverHevcLevel(3840, 2160, 60))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, resolverHevcLevel(7680, 4320, 30))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, resolverHevcLevel(7680, 4320, 60))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4, resolverHevcLevel(1920, 1080, 30))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4, resolverHevcLevel(1920, 1080, 120))
    }

    @Test
    fun resolverOverLevelsRemainConformantAcrossMatrix() {
        // The resolver may over-level (e.g. 8K30 -> L6.1 vs spec-minimal L6); that is conformance-safe.
        listOf(
            Triple(3840, 2160, 30),
            Triple(3840, 2160, 60),
            Triple(7680, 4320, 30),
            Triple(7680, 4320, 60),
        ).forEach { (w, h, fps) ->
            val level = resolverHevcLevel(w, h, fps)!!
            assertTrue(
                "resolver level for ${w}x$h@$fps must be >= spec-minimal conformant level",
                HevcLevelSpecTable.isConformant(level, pixels(w, h), samplesPerSecond(w, h, fps))
            )
        }
    }

    @Test
    fun resolver1080p120IsBelowSpecConformanceFloor_knownGapDeviceGated() {
        // KNOWN GAP (DEVICE-GATED): 1080p120 (sample-rate 248M) needs L5, but the live resolver emits
        // L4. Fixing it would change live level output for an existing input, so it is locked here as
        // a characterized gap rather than silently corrected. A future strict-conformance level mode
        // (device-validated) can promote it; for now this asserts the gap EXISTS and is not hidden.
        val resolver = resolverHevcLevel(1920, 1080, 120)!!
        val minimal = HevcLevelSpecTable.minimalConformantHevcLevel(
            pixels(1920, 1080),
            samplesPerSecond(1920, 1080, 120)
        )!!
        assertTrue(
            "documented under-level gap: resolver L4 is below the spec-minimal level",
            HevcLevelSpecTable.ordinalOf(resolver) < HevcLevelSpecTable.ordinalOf(minimal.constant)
        )
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4, resolver)
        assertEquals("5", minimal.label)
    }

    @Test
    fun transportCompatibility_comparesTierLevelEnvelopesNotBitFlagIntegers() {
        // Observed Qualcomm read-back: requested High 3.1 (0x200), reported Main 6.2 (0x1000000).
        // Main 6.2 contains the complete High-3.1 envelope, despite using a different tier flag.
        assertTrue(
            HevcLevelSpecTable.isTransportCompatible(
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62
            )
        )
        // A higher numeric level is not automatically enough when its Main-tier MaxBR is smaller.
        assertFalse(
            HevcLevelSpecTable.isTransportCompatible(
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61
            )
        )
        assertTrue(
            HevcLevelSpecTable.isTransportCompatible(
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62
            )
        )
        assertFalse(
            HevcLevelSpecTable.isTransportCompatible(
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5,
                MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41
            )
        )
    }
}
