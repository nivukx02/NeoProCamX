package com.neoproc.neoproccamx.core

import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import java.security.MessageDigest

object SingularityHorizonProfile {
    const val PROFILE_NAME = "IMAX_STRICT_V20_100000"
    const val ULTRA_DETAIL_AMPLIFICATION = 10.0f
    const val ADAPTIVE_GAIN_CEILING = 4.0f
    const val SHARPENING_STRICT_CEILING = 1.20f
    const val MAX_ADDITIVE_DETAIL_GAIN = 10.0f

    const val QUALITY_BOOST_24FPS = 750.0f
    const val QUALITY_BOOST_60FPS = 1500.0f
    const val QUALITY_BOOST_120FPS = 3600.0f
    const val QUALITY_BOOST_240FPS = 7200.0f
    const val QUALITY_BOOST_480FPS = 14400.0f

    const val LUT_CUBE_SIZE = 129
    const val RUNTIME_PREVIEW_LUT_CUBE_SIZE = 65
    const val LUT_INTERPOLATION = "TETRAHEDRAL"

    const val MIN_BITRATE_24K_MBPS = 1600
    const val MIN_BITRATE_17K_MBPS = 1600
    const val MIN_BITRATE_12K_MBPS = 1600
    const val MIN_BITRATE_8K_MBPS = 1600
    const val MIN_BITRATE_5K_MBPS = 1200
    const val MIN_BITRATE_4K_MBPS = 1200
    const val MIN_BITRATE_2K_MBPS = 500
    const val MIN_BITRATE_1080P_MBPS = 340
    const val MIN_BITRATE_720P_MBPS = 180

    const val TNR_STRICT_CEILING = 0.98f
    const val TNR_TRANSCENDENT_CEILING = TNR_STRICT_CEILING
    const val TNR_MOTION_GATE = 0.42f
    const val TNR_FRAME_BUFFER_DEPTH = 9
    const val MC_TNR_TAPS_EXPORT = 9
    const val MOTION_VECTOR_PRECISION = "QUARTER_PIXEL"

    const val AI_RESOLUTION_PREVIEW = 2560
    const val AI_RESOLUTION_RECORDING = 3200
    const val MIN_MASK_CONFIDENCE = 0.32f
    const val AI_SEMANTIC_CLASSES = 64
    const val AI_TEMPORAL_CONSISTENCY = 0.78f
    const val AI_SUBPIXEL_EDGE_REFINE = 0.65f

    const val SHOT_NOISE_COEFFICIENT = 0.0026f
    const val READ_NOISE_COEFFICIENT = 0.00050f
    const val HETEROSCEDASTIC_FLOOR = 0.0000002f
    const val MIN_LUMA_SNR_BASE_DB = 50.0
    const val MIN_LUMA_SNR_HIGH_ISO_DB = 40.0
    const val MAX_CHROMA_NOISE_BASE_STD = 0.4
    const val MAX_CHROMA_NOISE_HIGH_ISO_STD = 0.8
    const val MAX_TEMPORAL_FLICKER_PERCENT = 0.8
    const val MIN_MTF50_CY_PER_PX = 0.34
    const val MAX_OVERSHOOT_PERCENT = 3.0
    const val MIN_DETAIL_RETENTION_RAW = 0.93
    const val MIN_DETAIL_RETENTION_HIGH_ISO_RAW = 0.90
    const val MAX_DELTA_E2000_MEAN = 1.3
    const val MAX_DELTA_E2000 = 3.0
    const val MAX_GRAY_DELTA_ABS = 1.0
    const val MAX_BANDING_SCORE = 0.0
    const val MAX_DEAD_HOT_PIXELS = 0
    const val CHROMA_DENOISE_FLOOR = 0.65f
    const val SHADOW_CHROMA_LOCK_FLOOR = 0.75f
    const val NLM_PATCH_SIZE = 9
    const val NLM_SEARCH_WINDOW = 35
    const val WAVELET_LEVELS = 10
    const val BM3D_GROUP_SIZE = 48

    const val EXPORT_COLOR_SPACES = "Rec2020_PQ"
    const val EXPORT_WORKING_SPACE = "Rec2020_PQ"
    const val EXPORT_CHROMA_SUBSAMPLING = "4:2:2"
    const val EXPORT_BIT_DEPTH = 12
    const val EXPORT_ARCHIVAL_BIT_DEPTH = 12
    const val WAVELET_LEVELS_EXPORT = 6
    const val DITHER_STAGES = 2

    const val DETAIL_PYRAMID_LEVELS = 6
    const val STEERABLE_ORIENTATIONS = 8
    const val LAPLACIAN_KERNEL = "9_TAP_STRICT_IMAX"
    const val BILATERAL_KERNEL = "37_TAP"
    const val CONVOLUTION_ALGORITHM = "EDGE_AWARE_LAPLACIAN"
    const val FFT_ALGORITHM = "NONE_REALTIME"

    const val FORCE_TRANSCENDENT_ONLY = true
    const val FRAME_LEVEL_ATTESTATION = true
    const val FP16_FORBIDDEN_IN_DETAIL_NR = true
    const val ABORT_ON_THERMAL_THROTTLE = true

    val CANONICAL_UNIFORM_SHA512: String =
        hashHex("SHA-512", canonicalUniformPayload())

    val CANONICAL_UNIFORM_CHECKSUM: String = CANONICAL_UNIFORM_SHA512

    fun canonicalUniformPayload(): String =
        listOf(
            "profile=$PROFILE_NAME",
            "ultraDetail=${ULTRA_DETAIL_AMPLIFICATION.toBits()}",
            "adaptiveGain=${ADAPTIVE_GAIN_CEILING.toBits()}",
            "sharpeningCeiling=${SHARPENING_STRICT_CEILING.toBits()}",
            "maxAdditiveDetail=${MAX_ADDITIVE_DETAIL_GAIN.toBits()}",
            "tnrCeiling=${TNR_STRICT_CEILING.toBits()}",
            "tnrMotionGate=${TNR_MOTION_GATE.toBits()}",
            "shotNoise=${SHOT_NOISE_COEFFICIENT.toBits()}",
            "readNoise=${READ_NOISE_COEFFICIENT.toBits()}",
            "noiseFloor=${HETEROSCEDASTIC_FLOOR.toBits()}",
            "minLumaSnrBase=${MIN_LUMA_SNR_BASE_DB.toBits()}",
            "minLumaSnrHigh=${MIN_LUMA_SNR_HIGH_ISO_DB.toBits()}",
            "maxChromaNoiseBase=${MAX_CHROMA_NOISE_BASE_STD.toBits()}",
            "maxChromaNoiseHigh=${MAX_CHROMA_NOISE_HIGH_ISO_STD.toBits()}",
            "maxTemporalFlicker=${MAX_TEMPORAL_FLICKER_PERCENT.toBits()}",
            "minMtf50=${MIN_MTF50_CY_PER_PX.toBits()}",
            "maxOvershoot=${MAX_OVERSHOOT_PERCENT.toBits()}",
            "minDetailRaw=${MIN_DETAIL_RETENTION_RAW.toBits()}",
            "minDetailHigh=${MIN_DETAIL_RETENTION_HIGH_ISO_RAW.toBits()}",
            "maxDeltaEMean=${MAX_DELTA_E2000_MEAN.toBits()}",
            "maxDeltaE=${MAX_DELTA_E2000.toBits()}",
            "maxGrayDelta=${MAX_GRAY_DELTA_ABS.toBits()}",
            "maxBanding=${MAX_BANDING_SCORE.toBits()}",
            "maxDeadHotPixels=$MAX_DEAD_HOT_PIXELS",
            "chromaFloor=${CHROMA_DENOISE_FLOOR.toBits()}",
            "shadowChromaFloor=${SHADOW_CHROMA_LOCK_FLOOR.toBits()}",
            "lut=$LUT_CUBE_SIZE",
            "runtimePreviewLut=$RUNTIME_PREVIEW_LUT_CUBE_SIZE",
            "aiPreview=$AI_RESOLUTION_PREVIEW",
            "aiRecording=$AI_RESOLUTION_RECORDING",
            "exportBitDepth=$EXPORT_BIT_DEPTH",
            "exportArchivalBitDepth=$EXPORT_ARCHIVAL_BIT_DEPTH"
        ).joinToString(separator = "|")
}

object ImaxStrictProfile {
    const val PROFILE_NAME = "IMAX_STRICT_V20_100000"
    const val ULTRA_DETAIL_AMPLIFICATION = 7.20f
    const val ADAPTIVE_GAIN_CEILING = 2.90f
    const val SHARPENING_STRICT_CEILING = 0.96f
    const val MAX_ADDITIVE_DETAIL_GAIN = 10.0f
    const val QUALITY_BOOST_60FPS = 1500.0f
    const val LUT_CUBE_SIZE = 129
    const val RUNTIME_PREVIEW_LUT_CUBE_SIZE = 65
    const val MIN_BITRATE_720P_MBPS = 180
    const val MIN_BITRATE_1080P_MBPS = 340
    const val MIN_BITRATE_2K_MBPS = 500
    const val MIN_BITRATE_4K_MBPS = 820
    const val MIN_BITRATE_5K_MBPS = 1200
    const val MIN_BITRATE_8K_MBPS = 1600
    const val TNR_STRICT_CEILING = 0.88f
    const val TNR_MOTION_GATE = 0.42f
    const val AI_RESOLUTION_PREVIEW = 2560
    const val AI_RESOLUTION_RECORDING = 3200
    const val MIN_MASK_CONFIDENCE = 0.32f
    const val CHROMA_DENOISE_FLOOR = 0.32f
    const val SHADOW_CHROMA_LOCK_FLOOR = 0.40f
    const val SHOT_NOISE_COEFFICIENT = 0.0026f
    const val READ_NOISE_COEFFICIENT = 0.00050f
    const val HETEROSCEDASTIC_FLOOR = 0.0000002f
    const val MIN_LUMA_SNR_BASE_DB = 50.0
    const val MIN_LUMA_SNR_HIGH_ISO_DB = 40.0
    const val MAX_CHROMA_NOISE_BASE_STD = 0.4
    const val MAX_CHROMA_NOISE_HIGH_ISO_STD = 0.8
    const val MAX_TEMPORAL_FLICKER_PERCENT = 0.8
    const val MIN_MTF50_CY_PER_PX = 0.34
    const val MAX_OVERSHOOT_PERCENT = 3.0
    const val MIN_DETAIL_RETENTION_RAW = 0.93
    const val MIN_DETAIL_RETENTION_HIGH_ISO_RAW = 0.90
    const val MAX_DELTA_E2000_MEAN = 1.3
    const val MAX_DELTA_E2000 = 3.0
    const val MAX_GRAY_DELTA_ABS = 1.0
    const val MAX_BANDING_SCORE = 0.0
    const val MAX_DEAD_HOT_PIXELS = 0
    const val EXPORT_COLOR_SPACE = "Rec2020_PQ"
    const val EXPORT_COLOR_SPACES = "Rec2020_PQ"
    const val EXPORT_CHROMA_SUBSAMPLING = "4:2:2"
    const val EXPORT_BIT_DEPTH = 12
    const val EXPORT_ARCHIVAL_BIT_DEPTH = 12
    const val LUT_INTERPOLATION = "TETRAHEDRAL"
    const val FORCE_STRICT_ONLY = true

    val CANONICAL_UNIFORM_CHECKSUM: String =
        hashHex("SHA-512", canonicalUniformPayload())

    fun canonicalUniformPayload(): String =
        listOf(
            "profile=$PROFILE_NAME",
            "ultraDetail=${ULTRA_DETAIL_AMPLIFICATION.toBits()}",
            "adaptiveGain=${ADAPTIVE_GAIN_CEILING.toBits()}",
            "sharpeningCeiling=${SHARPENING_STRICT_CEILING.toBits()}",
            "maxAdditiveDetail=${MAX_ADDITIVE_DETAIL_GAIN.toBits()}",
            "tnrCeiling=${TNR_STRICT_CEILING.toBits()}",
            "tnrMotionGate=${TNR_MOTION_GATE.toBits()}",
            "shotNoise=${SHOT_NOISE_COEFFICIENT.toBits()}",
            "readNoise=${READ_NOISE_COEFFICIENT.toBits()}",
            "noiseFloor=${HETEROSCEDASTIC_FLOOR.toBits()}",
            "minLumaSnrBase=${MIN_LUMA_SNR_BASE_DB.toBits()}",
            "minLumaSnrHigh=${MIN_LUMA_SNR_HIGH_ISO_DB.toBits()}",
            "maxChromaNoiseBase=${MAX_CHROMA_NOISE_BASE_STD.toBits()}",
            "maxChromaNoiseHigh=${MAX_CHROMA_NOISE_HIGH_ISO_STD.toBits()}",
            "maxTemporalFlicker=${MAX_TEMPORAL_FLICKER_PERCENT.toBits()}",
            "minMtf50=${MIN_MTF50_CY_PER_PX.toBits()}",
            "maxOvershoot=${MAX_OVERSHOOT_PERCENT.toBits()}",
            "minDetailRaw=${MIN_DETAIL_RETENTION_RAW.toBits()}",
            "minDetailHigh=${MIN_DETAIL_RETENTION_HIGH_ISO_RAW.toBits()}",
            "maxDeltaEMean=${MAX_DELTA_E2000_MEAN.toBits()}",
            "maxDeltaE=${MAX_DELTA_E2000.toBits()}",
            "maxGrayDelta=${MAX_GRAY_DELTA_ABS.toBits()}",
            "maxBanding=${MAX_BANDING_SCORE.toBits()}",
            "maxDeadHotPixels=$MAX_DEAD_HOT_PIXELS",
            "chromaFloor=${CHROMA_DENOISE_FLOOR.toBits()}",
            "shadowChromaFloor=${SHADOW_CHROMA_LOCK_FLOOR.toBits()}",
            "lut=$LUT_CUBE_SIZE",
            "runtimePreviewLut=$RUNTIME_PREVIEW_LUT_CUBE_SIZE",
            "aiPreview=$AI_RESOLUTION_PREVIEW",
            "aiRecording=$AI_RESOLUTION_RECORDING",
            "exportBitDepth=$EXPORT_BIT_DEPTH",
            "exportArchivalBitDepth=$EXPORT_ARCHIVAL_BIT_DEPTH"
        ).joinToString(separator = "|")
}

object BeyondImaxEliteProfile {
    const val PROFILE_NAME = "BEYOND_IMAX_ELITE_10_10"
    const val BASELINE_PROFILE_NAME = "IMAX_STRICT_10_10"
    const val ULTRA_DETAIL_AMPLIFICATION = 15.0f
    const val ADAPTIVE_GAIN_CEILING = 6.0f
    const val SHARPENING_STRICT_CEILING = 1.35f
    const val MAX_ADDITIVE_DETAIL_GAIN = 10.0f

    const val QUALITY_BOOST_24FPS = 1000.0f
    const val QUALITY_BOOST_60FPS = 2400.0f
    const val QUALITY_BOOST_120FPS = 5200.0f
    const val QUALITY_BOOST_240FPS = 10400.0f
    const val QUALITY_BOOST_480FPS = 20800.0f

    const val LUT_CUBE_SIZE = 257
    const val RUNTIME_PREVIEW_LUT_CUBE_SIZE = 65
    const val LUT_INTERPOLATION = ImaxStrictProfile.LUT_INTERPOLATION

    const val MIN_BITRATE_24K_MBPS = 2400
    const val MIN_BITRATE_17K_MBPS = 2400
    const val MIN_BITRATE_12K_MBPS = 2400
    const val MIN_BITRATE_8K_MBPS = 2000
    const val MIN_BITRATE_5K_MBPS = 1500
    const val MIN_BITRATE_4K_MBPS = 1000
    const val MIN_BITRATE_2K_MBPS = 640
    const val MIN_BITRATE_1080P_MBPS = 420
    const val MIN_BITRATE_720P_MBPS = 240

    const val TNR_STRICT_CEILING = 0.99f
    const val TNR_MOTION_GATE = 0.28f
    const val TNR_FRAME_BUFFER_DEPTH = 12
    const val MC_TNR_TAPS_EXPORT = 13
    const val MOTION_VECTOR_PRECISION = "QUARTER_PIXEL_PLUS_CONFIDENCE"

    const val AI_RESOLUTION_PREVIEW = 3200
    const val AI_RESOLUTION_RECORDING = 4096
    const val MIN_MASK_CONFIDENCE = 0.45f
    const val AI_SEMANTIC_CLASSES = SingularityHorizonProfile.AI_SEMANTIC_CLASSES
    const val AI_TEMPORAL_CONSISTENCY = 0.84f
    const val AI_SUBPIXEL_EDGE_REFINE = 0.72f

    const val SHOT_NOISE_COEFFICIENT = ImaxStrictProfile.SHOT_NOISE_COEFFICIENT
    const val READ_NOISE_COEFFICIENT = ImaxStrictProfile.READ_NOISE_COEFFICIENT
    const val HETEROSCEDASTIC_FLOOR = ImaxStrictProfile.HETEROSCEDASTIC_FLOOR
    const val MIN_LUMA_SNR_BASE_DB = 54.0
    const val MIN_LUMA_SNR_HIGH_ISO_DB = 43.0
    const val MAX_CHROMA_NOISE_BASE_STD = 0.25
    const val MAX_CHROMA_NOISE_HIGH_ISO_STD = 0.55
    const val MAX_TEMPORAL_FLICKER_PERCENT = 0.45
    const val MIN_MTF50_CY_PER_PX = 0.38
    const val MAX_OVERSHOOT_PERCENT = 2.0
    const val MIN_DETAIL_RETENTION_RAW = 0.96
    const val MIN_DETAIL_RETENTION_HIGH_ISO_RAW = 0.93
    const val MAX_DELTA_E2000_MEAN = 0.9
    const val MAX_DELTA_E2000 = 2.0
    const val MAX_GRAY_DELTA_ABS = 0.65
    const val MAX_BANDING_SCORE = 0.0
    const val MAX_DEAD_HOT_PIXELS = 0
    const val CHROMA_DENOISE_FLOOR = 0.85f
    const val SHADOW_CHROMA_LOCK_FLOOR = 0.92f
    const val NLM_PATCH_SIZE = SingularityHorizonProfile.NLM_PATCH_SIZE
    const val NLM_SEARCH_WINDOW = SingularityHorizonProfile.NLM_SEARCH_WINDOW
    const val WAVELET_LEVELS = SingularityHorizonProfile.WAVELET_LEVELS
    const val BM3D_GROUP_SIZE = SingularityHorizonProfile.BM3D_GROUP_SIZE

    const val EXPORT_COLOR_SPACE = ImaxStrictProfile.EXPORT_COLOR_SPACE
    const val EXPORT_COLOR_SPACES = ImaxStrictProfile.EXPORT_COLOR_SPACES
    const val EXPORT_CHROMA_SUBSAMPLING = ImaxStrictProfile.EXPORT_CHROMA_SUBSAMPLING
    const val EXPORT_BIT_DEPTH = ImaxStrictProfile.EXPORT_BIT_DEPTH
    const val EXPORT_ARCHIVAL_BIT_DEPTH = ImaxStrictProfile.EXPORT_ARCHIVAL_BIT_DEPTH
    const val FORCE_STRICT_ONLY = true

    val CANONICAL_UNIFORM_CHECKSUM: String =
        hashHex("SHA-512", canonicalUniformPayload())

    fun canonicalUniformPayload(): String =
        listOf(
            "profile=$PROFILE_NAME",
            "baseline=$BASELINE_PROFILE_NAME",
            "ultraDetail=${ULTRA_DETAIL_AMPLIFICATION.toBits()}",
            "adaptiveGain=${ADAPTIVE_GAIN_CEILING.toBits()}",
            "sharpeningCeiling=${SHARPENING_STRICT_CEILING.toBits()}",
            "maxAdditiveDetail=${MAX_ADDITIVE_DETAIL_GAIN.toBits()}",
            "tnrCeiling=${TNR_STRICT_CEILING.toBits()}",
            "tnrMotionGate=${TNR_MOTION_GATE.toBits()}",
            "shotNoise=${SHOT_NOISE_COEFFICIENT.toBits()}",
            "readNoise=${READ_NOISE_COEFFICIENT.toBits()}",
            "noiseFloor=${HETEROSCEDASTIC_FLOOR.toBits()}",
            "minLumaSnrBase=${MIN_LUMA_SNR_BASE_DB.toBits()}",
            "minLumaSnrHigh=${MIN_LUMA_SNR_HIGH_ISO_DB.toBits()}",
            "maxChromaNoiseBase=${MAX_CHROMA_NOISE_BASE_STD.toBits()}",
            "maxChromaNoiseHigh=${MAX_CHROMA_NOISE_HIGH_ISO_STD.toBits()}",
            "maxTemporalFlicker=${MAX_TEMPORAL_FLICKER_PERCENT.toBits()}",
            "minMtf50=${MIN_MTF50_CY_PER_PX.toBits()}",
            "maxOvershoot=${MAX_OVERSHOOT_PERCENT.toBits()}",
            "minDetailRaw=${MIN_DETAIL_RETENTION_RAW.toBits()}",
            "minDetailHigh=${MIN_DETAIL_RETENTION_HIGH_ISO_RAW.toBits()}",
            "maxDeltaEMean=${MAX_DELTA_E2000_MEAN.toBits()}",
            "maxDeltaE=${MAX_DELTA_E2000.toBits()}",
            "maxGrayDelta=${MAX_GRAY_DELTA_ABS.toBits()}",
            "maxBanding=${MAX_BANDING_SCORE.toBits()}",
            "maxDeadHotPixels=$MAX_DEAD_HOT_PIXELS",
            "chromaFloor=${CHROMA_DENOISE_FLOOR.toBits()}",
            "shadowChromaFloor=${SHADOW_CHROMA_LOCK_FLOOR.toBits()}",
            "lut=$LUT_CUBE_SIZE",
            "runtimePreviewLut=$RUNTIME_PREVIEW_LUT_CUBE_SIZE",
            "aiPreview=$AI_RESOLUTION_PREVIEW",
            "aiRecording=$AI_RESOLUTION_RECORDING",
            "exportBitDepth=$EXPORT_BIT_DEPTH",
            "exportArchivalBitDepth=$EXPORT_ARCHIVAL_BIT_DEPTH"
        ).joinToString(separator = "|")
}

private fun hashHex(algorithm: String, value: String): String =
    MessageDigest.getInstance(algorithm)
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * HighEndQualityPolicy — Controls high-end quality features.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FLAGSHIP CINEMA PIPELINE — ZERO DOWNSCALE / ZERO LAG / MAXIMUM SENSOR
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This policy enforces maximum sensor utilization across the entire pipeline.
 * When enabled (default), the camera will:
 *   - Use the full pixel array for video recording at the device's sensor-native maximum
 *   - Never downscale or upscale frames
 *   - Maintain native sensor aspect ratio at all times
 *   - Encode at maximum hardware-supported bitrate
 *   - Target 60fps preview for maximum smoothness within the video contract
 *   - Engage FLAGSHIP runtime tier for maximum LUT/AI capability
 *
 * Thread safety: All mutable state is @Volatile for lock-free reads.
 */
object HighEndQualityPolicy {

    // ── Master Switches ────────────────────────────────────────────────────────

    /** Enable high-end quality features globally. */
    @Volatile
    var enabled: Boolean = true

    /** Enable strict high-end only mode (disables all fallbacks). */
    @Volatile
    var strictHighEndOnly: Boolean = true

    /** Force full sensor readout for ALL recording modes. */
    @Volatile
    var forceFullSensorReadout: Boolean = true

    /** Prohibit any resolution scaling (up or down). */
    @Volatile
    var prohibitResolutionScaling: Boolean = true

    /** Lock native sensor aspect ratio — never alter AR in pipeline. */
    @Volatile
    var lockNativeAspectRatio: Boolean = true

    /**
     * Device-capability CEILING — the highest [HighEndRuntimeTier] this device can actually sustain,
     * set ONCE at app startup from a hardware probe (see DeviceCapabilityCeilingPolicy). Default
     * FLAGSHIP is a NO-OP cap (maximum rank), so with no injected signal every strict floor below is
     * byte-for-byte preserved. A lower ceiling relaxes the strict floors so a weaker device records
     * its OWN native-sensor maximum instead of being rejected for missing 4K — "no quality compromise"
     * means the best that device can do, and a capable device is NEVER downscaled.
     */
    @Volatile
    var deviceCapabilityCeiling: HighEndRuntimeTier = HighEndRuntimeTier.FLAGSHIP

    /** True when the device ceiling cannot reach tier [t] — used to relax strict floors on weak devices. */
    private fun ceilingBelow(t: HighEndRuntimeTier): Boolean = deviceCapabilityCeiling.rank < t.rank

    /**
     * Multiplier on the bitrate FLOOR by device ceiling: 1.0 for CINEMA-and-up (no encoder regression
     * on any 4K-capable device — the pinned BeyondImax floors are unchanged), softened only for sub-4K
     * ceilings so a weak encoder is not asked for the ~1 Gbps it cannot sustain.
     */
    private fun deviceBitrateFloorScale(): Double = when {
        deviceCapabilityCeiling.rank >= HighEndRuntimeTier.CINEMA.rank -> 1.0
        deviceCapabilityCeiling == HighEndRuntimeTier.STANDARD -> 0.5
        else -> 0.35
    }

    /**
     * True when this device should take the GLES render/encode fallback instead of Vulkan — derived
     * from the (ceiling-clamped) runtime tier's [HighEndRuntimeTier.vulkanEnabled]. This is the
     * Kotlin-verifiable backend SELECTION signal; achieving GLES pixel-parity with the Vulkan compute
     * path is a separate device-verified task.
     */
    fun shouldUseGlesFallback(previewFps: Float, recording: Boolean): Boolean =
        !runtimeTier(previewFps, recording).vulkanEnabled

    // ── Query API ──────────────────────────────────────────────────────────────

    fun isHighEndEnabled(): Boolean = enabled
    fun isStrictHighEndOnly(): Boolean = strictHighEndOnly
    fun isHighEndQualityActive(): Boolean = enabled && strictHighEndOnly
    fun isFullSensorForced(): Boolean = enabled && forceFullSensorReadout
    fun isScalingProhibited(): Boolean = enabled && prohibitResolutionScaling
    fun isNativeAspectLocked(): Boolean = enabled && lockNativeAspectRatio

    // ── Runtime Tier Resolution ────────────────────────────────────────────────

    /**
     * Resolves the runtime processing tier based on current FPS, recording
     * state, and quality policy. In strict mode, every tier is escalated
     * to its maximum capability level for flagship performance.
     */
    fun runtimeTier(
        previewFps: Float,
        recording: Boolean,
        enabledOverride: Boolean = enabled
    ): HighEndRuntimeTier {
        if (!enabledOverride) return HighEndRuntimeTier.BASIC
        val fps = previewFps.coerceAtLeast(1f)
        val baseTier = when {
            recording && fps >= 48f -> HighEndRuntimeTier.CINEMA
            recording && fps >= 25f -> HighEndRuntimeTier.PROFESSIONAL
            recording -> HighEndRuntimeTier.BALANCED
            fps >= 60f -> HighEndRuntimeTier.CINEMA
            fps >= 48f -> HighEndRuntimeTier.PROFESSIONAL
            fps >= 25f -> HighEndRuntimeTier.BALANCED
            else -> HighEndRuntimeTier.STANDARD
        }
        if (!strictHighEndOnly) return baseTier
        // FLAGSHIP ESCALATION: Every tier pushes to maximum capability, but NEVER above what the
        // device can actually sustain (deviceCapabilityCeiling — default FLAGSHIP = no cap, so a
        // capable device escalates exactly as before; a weaker device is clamped to its ceiling).
        val escalated = when (baseTier) {
            HighEndRuntimeTier.BASIC -> HighEndRuntimeTier.PROFESSIONAL
            HighEndRuntimeTier.STANDARD -> HighEndRuntimeTier.CINEMA
            HighEndRuntimeTier.BALANCED -> HighEndRuntimeTier.ULTRA
            HighEndRuntimeTier.PROFESSIONAL -> HighEndRuntimeTier.ULTRA
            HighEndRuntimeTier.CINEMA -> HighEndRuntimeTier.FLAGSHIP
            HighEndRuntimeTier.ULTRA -> HighEndRuntimeTier.FLAGSHIP
            HighEndRuntimeTier.FLAGSHIP -> HighEndRuntimeTier.FLAGSHIP
            HighEndRuntimeTier.RECOVERY -> HighEndRuntimeTier.CINEMA
            HighEndRuntimeTier.SUSTAINED -> HighEndRuntimeTier.ULTRA
        }
        return HighEndRuntimeTier.min(escalated, deviceCapabilityCeiling)
    }

    // ── FPS Control ────────────────────────────────────────────────────────────

    /**
     * Enforces the supported cinema FPS floor for the video contract.
     */
    fun previewFpsFloor(requestedFps: Int, recording: Boolean): Int {
        return when {
            requestedFps <= 24 -> 24
            requestedFps <= 25 -> 25
            requestedFps <= 30 -> 25
            requestedFps <= 48 -> 48
            requestedFps <= 50 -> 50
            else -> 60
        }
    }

    fun maxPreviewFps(requestedFps: Int): Int {
        return when {
            requestedFps <= 24 -> 24
            requestedFps <= 30 -> 25
            else -> requestedFps.coerceAtMost(60)
        }
    }

    val maxPreviewFps: Int
        get() = maxPreviewFps(targetPreviewFps)

    /** Target 60fps preview for ultra-smooth motion within the recording contract. */
    val targetPreviewFps: Int = 60

    fun snapToCinemaFps(fps: Int): Int {
        return when {
            fps <= 24 -> 24
            fps <= 25 -> 25
            fps <= 30 -> 25
            fps <= 48 -> 48
            fps <= 50 -> 50
            fps <= 60 -> 60
            else -> 60
        }
    }

    // ── AI Configuration ───────────────────────────────────────────────────────

    /**
     * Maximum AI analysis dimension. Full sensor mode allows larger
     * analysis frames for better subject detection accuracy.
     */
    fun aiMaxDimension(
        previewFps: Float,
        recording: Boolean,
        enabledOverride: Boolean = enabled
    ): Int {
        if (!enabledOverride) return 1080
        return when {
            strictHighEndOnly && recording -> BeyondImaxEliteProfile.AI_RESOLUTION_RECORDING
            strictHighEndOnly -> BeyondImaxEliteProfile.AI_RESOLUTION_PREVIEW
            recording -> 1440
            else -> 1080
        }
    }

    // ── Quality Boost ──────────────────────────────────────────────────────────

    /**
     * Computes the quality multiplier applied to bitrate, LUT precision,
     * and processing complexity. Scales with FPS and recording state.
     *
     * @return Multiplier in range [1.0, 3.5]
     */
    fun qualityBoost(
        recording: Boolean,
        previewFps: Float,
        enabledOverride: Boolean = enabled
    ): Float {
        if (!enabledOverride) return 1.0f
        if (strictHighEndOnly) {
            return when {
                previewFps >= 480f -> BeyondImaxEliteProfile.QUALITY_BOOST_480FPS
                previewFps >= 240f -> BeyondImaxEliteProfile.QUALITY_BOOST_240FPS
                previewFps >= 120f -> BeyondImaxEliteProfile.QUALITY_BOOST_120FPS
                previewFps >= 60f -> BeyondImaxEliteProfile.QUALITY_BOOST_60FPS
                else -> BeyondImaxEliteProfile.QUALITY_BOOST_24FPS
            }
        }
        val baseBoost = 75.0f
        val fpsScale = when {
            previewFps >= 60f -> if (recording) 8.0f else 6.0f
            previewFps >= 48f -> if (recording) 6.0f else 4.0f
            else -> 1.0f
        }
        val recordingScale = if (recording) 4.0f else 1.0f
        return (baseBoost * fpsScale * recordingScale).coerceAtMost(1000.0f)
    }

    fun qualityBoost(recording: Boolean, enabledOverride: Boolean = enabled): Float =
        qualityBoost(
            recording = recording,
            previewFps = targetPreviewFps.toFloat(),
            enabledOverride = enabledOverride
        )

    // ── LUT Configuration ──────────────────────────────────────────────────────

    /**
     * Returns LUT cube size for preview/recording. Cinema mode uses 65³
     * for maximum color precision matching DaVinci Resolve standards.
     */
    fun lutSizeForPreview(highFidelity: Boolean, previewFps: Float, recording: Boolean): Int {
        return if (strictHighEndOnly) {
            BeyondImaxEliteProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE
        } else if (highFidelity) {
            if (recording) 65 else 65
        } else {
            33
        }
    }

    val highPrecisionLutSize: Int = BeyondImaxEliteProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE
    val standardLutSize: Int = 33

    // ── Sensor Resolution Policy ───────────────────────────────────────────────

    /**
     * Returns the minimum acceptable recording resolution for the current
     * quality tier. In strict mode, enforces 4K as the absolute minimum.
     */
    // The 4K floor holds for strict-mode devices that can actually reach it (ceiling >= CINEMA);
    // a weaker device (ceiling below CINEMA) drops to a 1080 floor so it records its native-sensor
    // maximum instead of being rejected. Default ceiling FLAGSHIP keeps the 3840/2160 floor unchanged.
    fun minimumRecordingWidth(): Int =
        if (strictHighEndOnly && !ceilingBelow(HighEndRuntimeTier.CINEMA)) 3840 else 1920
    fun minimumRecordingHeight(): Int =
        if (strictHighEndOnly && !ceilingBelow(HighEndRuntimeTier.CINEMA)) 2160 else 1080

    /**
     * Maximum allowed long edge for recording. In full-sensor mode,
     * this returns Int.MAX_VALUE to allow native sensor dimensions.
     */
    fun maximumRecordingLongEdge(): Int =
        if (forceFullSensorReadout) Int.MAX_VALUE else 7680

    /**
     * Whether the pipeline should use the full sensor pixel array
     * instead of a cropped/binned readout.
     */
    fun shouldUseFullPixelArray(): Boolean =
        enabled && (forceFullSensorReadout || strictHighEndOnly)

    /**
     * Whether recording at the given resolution should be allowed.
     * In strict mode, rejects anything below 4K unless the sensor
     * itself is smaller than 4K.
     */
    fun isRecordingResolutionAcceptable(width: Int, height: Int, sensorWidth: Int): Boolean {
        if (!strictHighEndOnly) return true
        val longEdge = maxOf(width, height)
        val sensorLongEdge = maxOf(sensorWidth, 1)
        // Accept if within 90% of sensor resolution or at least 4K
        return longEdge >= minOf(minimumRecordingWidth(), sensorLongEdge) ||
            longEdge.toFloat() / sensorLongEdge >= 0.90f
    }

    // ── Bitrate Policy ─────────────────────────────────────────────────────────

    /**
     * Returns the minimum acceptable bitrate for recording at the given
     * dimensions. Flagship mode enforces significantly higher floors.
     */
    fun minimumBitrateBps(width: Int, height: Int, fps: Int): Int {
        return minimumBitrateBpsLong(width, height, fps)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun minimumBitrateBpsLong(width: Int, height: Int, fps: Int): Long {
        val pixels = width.toLong() * height.toLong()
        val baseFloor = when {
            pixels >= 24576L * 12960L -> BeyondImaxEliteProfile.MIN_BITRATE_24K_MBPS * 1_000_000L
            pixels >= 17408L * 9216L -> BeyondImaxEliteProfile.MIN_BITRATE_17K_MBPS * 1_000_000L
            pixels >= 12288L * 6480L -> BeyondImaxEliteProfile.MIN_BITRATE_12K_MBPS * 1_000_000L
            pixels >= 7680L * 4320 -> BeyondImaxEliteProfile.MIN_BITRATE_8K_MBPS * 1_000_000L
            pixels >= 5120L * 2880 -> BeyondImaxEliteProfile.MIN_BITRATE_5K_MBPS * 1_000_000L
            pixels >= 3840L * 2160 -> BeyondImaxEliteProfile.MIN_BITRATE_4K_MBPS * 1_000_000L
            pixels >= 2048L * 1080 -> BeyondImaxEliteProfile.MIN_BITRATE_2K_MBPS * 1_000_000L
            pixels >= 1920L * 1080 -> BeyondImaxEliteProfile.MIN_BITRATE_1080P_MBPS * 1_000_000L
            pixels >= 1280L * 720 -> BeyondImaxEliteProfile.MIN_BITRATE_720P_MBPS * 1_000_000L
            else -> 50_000_000L
        }
        val fpsMultiplier = when {
            fps >= 480 -> 19.2
            fps >= 240 -> 9.6
            fps >= 120 -> 4.8
            fps >= 60 -> 2.4
            else -> 1.0
        }
        // Device floor scale: 1.0 for any 4K-capable (CINEMA+) ceiling — default FLAGSHIP keeps the
        // pinned BeyondImax floors byte-identical — softened only for sub-4K devices.
        return (baseFloor * fpsMultiplier * deviceBitrateFloorScale()).toLong()
    }
}
