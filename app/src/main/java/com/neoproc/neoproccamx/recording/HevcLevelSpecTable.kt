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

    enum class Tier { MAIN, HIGH }

    data class HevcLevel(
        /** `MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevelXX`. */
        val constant: Int,
        val label: String,
        /** MaxLumaPS — maximum luma picture size in luma samples (Annex A, Table A.8). */
        val maxLumaPs: Long,
        /** MaxLumaSR — maximum luma sample rate in luma samples/second (Annex A, Table A.9). */
        val maxLumaSr: Long,
    )

    data class HevcTransportLevel(
        val constant: Int,
        val label: String,
        val tier: Tier,
        val maxLumaPs: Long,
        val maxLumaSr: Long,
        val maxBitrateKbps: Long
    )

    private data class LevelSpec(
        val label: String,
        val mainConstant: Int,
        val highConstant: Int,
        val maxLumaPs: Long,
        val maxLumaSr: Long,
        val mainMaxBitrateKbps: Long,
        val highMaxBitrateKbps: Long
    )

    /*
     * Annex-A level envelopes. High tier is not defined by H.265 below Level 4; Android still
     * exposes constants for those combinations, so their bitrate envelope is conservatively equal
     * to Main tier. Values are the general Main/Main10 MaxBR values in kbit/s.
     */
    private val LEVEL_SPECS: List<LevelSpec> = listOf(
        LevelSpec("1", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1, 36_864L, 552_960L, 128L, 128L),
        LevelSpec("2", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2, 122_880L, 3_686_400L, 1_500L, 1_500L),
        LevelSpec("2.1", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21, 245_760L, 7_372_800L, 3_000L, 3_000L),
        LevelSpec("3", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3, 552_960L, 16_588_800L, 6_000L, 6_000L),
        LevelSpec("3.1", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31, 983_040L, 33_177_600L, 10_000L, 10_000L),
        LevelSpec("4", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4, 2_228_224L, 66_846_720L, 12_000L, 30_000L),
        LevelSpec("4.1", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41, 2_228_224L, 133_693_440L, 20_000L, 50_000L),
        LevelSpec("5", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5, 8_912_896L, 267_386_880L, 25_000L, 100_000L),
        LevelSpec("5.1", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51, 8_912_896L, 534_773_760L, 40_000L, 160_000L),
        LevelSpec("5.2", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52, 8_912_896L, 1_069_547_520L, 60_000L, 240_000L),
        LevelSpec("6", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6, 35_651_584L, 1_069_547_520L, 60_000L, 240_000L),
        LevelSpec("6.1", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, 35_651_584L, 2_139_095_040L, 120_000L, 480_000L),
        LevelSpec("6.2", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62, 35_651_584L, 4_278_190_080L, 240_000L, 800_000L),
    )

    /** Ascending High-tier level ladder with the published Annex-A picture-size / sample-rate caps. */
    val HIGH_TIER_LADDER: List<HevcLevel> = LEVEL_SPECS.map { spec ->
        HevcLevel(spec.highConstant, spec.label, spec.maxLumaPs, spec.maxLumaSr)
    }

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

    /** Decodes Android's bit-flag level constant into its actual tier/level envelope. */
    fun transportLevelOf(levelConstant: Int): HevcTransportLevel? {
        val spec = LEVEL_SPECS.firstOrNull { candidate ->
            candidate.mainConstant == levelConstant || candidate.highConstant == levelConstant
        } ?: return null
        val highTier = spec.highConstant == levelConstant
        return HevcTransportLevel(
            constant = levelConstant,
            label = spec.label,
            tier = if (highTier) Tier.HIGH else Tier.MAIN,
            maxLumaPs = spec.maxLumaPs,
            maxLumaSr = spec.maxLumaSr,
            maxBitrateKbps = if (highTier) spec.highMaxBitrateKbps else spec.mainMaxBitrateKbps
        )
    }

    /**
     * True when the encoder-reported level envelope contains the complete requested envelope.
     *
     * Android HEVC level constants are powers-of-two bit flags, not scalar level numbers. Exact or
     * numeric comparison therefore rejects valid higher-level read-back and can also accept a tier
     * transition whose bitrate envelope is smaller. Comparing MaxLumaPS, MaxLumaSR and MaxBR keeps
     * both level and tier semantics intact.
     */
    fun isTransportCompatible(requestedLevelConstant: Int, actualLevelConstant: Int): Boolean {
        if (requestedLevelConstant == actualLevelConstant) return true
        val requested = transportLevelOf(requestedLevelConstant) ?: return false
        val actual = transportLevelOf(actualLevelConstant) ?: return false
        return actual.maxLumaPs >= requested.maxLumaPs &&
            actual.maxLumaSr >= requested.maxLumaSr &&
            actual.maxBitrateKbps >= requested.maxBitrateKbps
    }
}
