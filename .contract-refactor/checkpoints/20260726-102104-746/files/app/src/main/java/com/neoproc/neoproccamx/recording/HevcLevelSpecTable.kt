package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo

/**
 * Published HEVC (ITU-T H.265 / ISO-IEC 23008-2) Annex A level limits, encoded explicitly as the
 * ground-truth for the recording encoder-plan level audit (s6-f02).
 *
 * `MaxLumaPS` (max luma picture size, in luma samples — Table A.8) and `MaxLumaSR` (max luma sample
 * rate, in luma samples/second — Table A.9) are **tier-independent** (Main and High tier share the
 * same picture-size and sample-rate ceilings; only MaxBR/MaxCPB differ between tiers). The recording
 * resolver emits `HEVCHighTierLevelXX` constants, so the ladder below uses the High-tier constants.
 *
 * This table is an **assertion ground-truth only** — it is NOT wired into
 * [RecordingVideoEncoderPlanResolver]. The live resolver keeps its existing (coarser) breakpoint
 * tables; `HevcLevelSpecTableTest` cross-checks that the resolver stays at or above the spec-minimal
 * conformant level for the live matrix, and documents the one known under-level corner (1080p120)
 * as a DEVICE-GATED conformance gap rather than silently changing live level output.
 */
internal object HevcLevelSpecTable {

    data class HevcLevel(
        /** `MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevelXX`. */
        val constant: Int,
        val label: String,
        /** MaxLumaPS — maximum luma picture size in luma samples (Annex A, Table A.8). */
        val maxLumaPs: Long,
        /** MaxLumaSR — maximum luma sample rate in luma samples/second (Annex A, Table A.9). */
        val maxLumaSr: Long,
    )

    /** Ascending High-tier level ladder with the published Annex-A picture-size / sample-rate caps. */
    val HIGH_TIER_LADDER: List<HevcLevel> = listOf(
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1, "1", 36_864L, 552_960L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2, "2", 122_880L, 3_686_400L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21, "2.1", 245_760L, 7_372_800L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3, "3", 552_960L, 16_588_800L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31, "3.1", 983_040L, 33_177_600L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4, "4", 2_228_224L, 66_846_720L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41, "4.1", 2_228_224L, 133_693_440L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5, "5", 8_912_896L, 267_386_880L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51, "5.1", 8_912_896L, 534_773_760L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52, "5.2", 8_912_896L, 1_069_547_520L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6, "6", 35_651_584L, 1_069_547_520L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, "6.1", 35_651_584L, 2_139_095_040L),
        HevcLevel(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62, "6.2", 35_651_584L, 4_278_190_080L),
    )

    /**
     * The smallest High-tier level whose MaxLumaPS and MaxLumaSR both bound the stream, i.e. the
     * spec-minimal conformant level. Returns null when the stream exceeds even Level 6.2.
     */
    fun minimalConformantHevcLevel(pixels: Long, samplesPerSecond: Long): HevcLevel? =
        HIGH_TIER_LADDER.firstOrNull { it.maxLumaPs >= pixels && it.maxLumaSr >= samplesPerSecond }

    /** Position of a `HEVCHighTierLevelXX` constant in the ascending ladder, or -1 if absent. */
    fun ordinalOf(levelConstant: Int): Int =
        HIGH_TIER_LADDER.indexOfFirst { it.constant == levelConstant }

    /** True iff `levelConstant` is at or above the spec-minimal conformant level (conformance-safe). */
    fun isConformant(levelConstant: Int, pixels: Long, samplesPerSecond: Long): Boolean {
        val minimal = minimalConformantHevcLevel(pixels, samplesPerSecond) ?: return false
        val actual = ordinalOf(levelConstant)
        return actual >= 0 && actual >= ordinalOf(minimal.constant)
    }
}
