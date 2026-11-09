package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import com.neoproc.neoproccamx.color.NormalSdrVideoQualityScorecardPolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.NeoLogCurve
import java.util.Locale
import kotlin.math.roundToLong

internal enum class EliteVideoPipelineMode {
    NORMAL,
    PRO,
    HDR,
    LOG,
    RAW,
    NPV10
}

enum class EliteQualityAuditStatus {
    PASS,
    FAIL
}

enum class EliteVideoQualityTier {
    ELITE_NORMAL_SDR,
    ELITE_PRO_SDR_10BIT,
    ELITE_HDR_NDR10_PLUS_10BIT,
    ELITE_HDR_NIVUKX_VISION_10BIT,
    ELITE_LOG_N_LOG_10BIT,
    ELITE_LOG_N_LOG_BASE_10BIT,
    ELITE_LOG_N_LOG_SHADOW_10BIT,
    ELITE_LOG_N_LOG_HIGHLIGHT_10BIT,
    ELITE_LOG_N_LOG_CINEMA_10BIT,
    ELITE_RAW_12BIT,
    ELITE_RAW_14BIT,
    ELITE_RAW_16BIT,
    ELITE_NPV10_NATIVE_P010,
    ELITE_NPV10_PLANAR_YUV420P10,
    HIGH_NPV10_8BIT_EXPANDED,
    FAILED_UNSAFE
}

internal data class EliteVideoQualityRequest(
    val mode: EliteVideoPipelineMode,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: GpuVideoCodec,
    val hdrMode: String = "OFF",
    val logProfile: String = "N-Log Base",
    val rawBitDepth: Int = 0,
    val sourceBitDepth: Int = 8,
    val storageBitDepth: Int = 8,
    val sourceFormat: String = "YUV_420_888",
    val payloadFormat: Npv10PayloadFormat? = null,
    val tenBitTransport: Boolean = false,
    val colorSpace: String = "Rec.709",
    val colorScience: String = logProfile,
    val colorSpaceSelectedByUser: Boolean = true,
    val colorScienceSelectedByUser: Boolean = true,
    val savedColorSpacePreference: Boolean = false,
    val savedColorSciencePreference: Boolean = false,
    val exactSelectedResolution: Boolean = true
)

private fun EliteVideoQualityRequest.manualColorPlan(): ManualVideoColorSelectionPlan =
    ManualVideoColorSelectionPolicy.resolve(
        ManualVideoColorSelectionRequest(
            colorSpace = colorSpace,
            colorScience = colorScience.ifBlank { logProfile },
            colorSpaceSelectedByUser = colorSpaceSelectedByUser,
            colorScienceSelectedByUser = colorScienceSelectedByUser,
            savedColorSpacePreference = savedColorSpacePreference,
            savedColorSciencePreference = savedColorSciencePreference
        )
    )

internal data class EliteVideoQualityCertificate(
    val auditStatus: EliteQualityAuditStatus,
    val qualityTier: EliteVideoQualityTier,
    val normalizedHdrVersion: String,
    val normalizedLogVersion: String,
    val requiresTenBitTransport: Boolean,
    val truthfulNative10Bit: Boolean,
    val minimumBitrateBps: Int,
    val qualityValue: Int,
    val failureReasons: List<String>,
    val request: EliteVideoQualityRequest
) {
    fun toMetadata(): Map<String, String> = buildMap {
        put("mode", request.mode.name)
        put("qualityTier", qualityTier.name)
        put("auditStatus", auditStatus.name)
        put("normalizedHdrVersion", normalizedHdrVersion)
        put("normalizedLogVersion", normalizedLogVersion)
        put("requiresTenBitTransport", requiresTenBitTransport.toString())
        put("truthfulNative10Bit", truthfulNative10Bit.toString())
        put("sourceFormat", request.sourceFormat)
        put("sourceBitDepth", request.sourceBitDepth.toString())
        put("storageBitDepth", request.storageBitDepth.toString())
        put("rawBitDepth", request.rawBitDepth.toString())
        put("payloadFormat", request.payloadFormat?.name.orEmpty())
        put("exactSelectedResolution", request.exactSelectedResolution.toString())
        put("selectedResolution", "${request.width}x${request.height}")
        put("fps", request.fps.toString())
        put("codec", request.codec.name)
        put("colorSpace", request.colorSpace)
        put("colorScience", request.colorScience)
        put("minimumBitrateBps", minimumBitrateBps.toString())
        put("qualityValue", qualityValue.toString())
        put("failureReasons", failureReasons.joinToString(separator = "; "))
        putAll(RecordingQualityEvidencePolicy.unverifiedRequirementMetadata())
        putAll(NormalSdrVideoQualityScorecardPolicy.STRICT_10_10.asMetadata())
        putAll(request.manualColorPlan().toMetadata())
        putAll(EliteVideoEnhancementPolicy.resolve(request).asMetadata())
        putAll(NormalSdrRecordingMasteringPolicy.resolve(request).asMetadata())
        putAll(Sdr10RecordingMasteringPolicy.resolve(request).asMetadata())
        putAll(NLogRecordingMasteringPolicy.resolve(request, normalizedLogVersion).asMetadata())
        putAll(NeoHdrDynamicMasteringPolicy.resolve(request, normalizedHdrVersion).asMetadata())
        putAll(RecordingNivukxLookPolicy.resolve(request, normalizedHdrVersion).asMetadata())
        putAll(RecordingNivukxVisionPolicy.resolve(request, normalizedHdrVersion).asMetadata())
        putAll(
            NeoProfileTruthMetadataPolicy.resolve(
                request = request,
                normalizedHdrVersion = normalizedHdrVersion,
                normalizedLogVersion = normalizedLogVersion
            ).asMap()
        )
    }
}

internal data class EliteVideoEnhancementPlan(
    val elitePipelineGeneration: String,
    val enhancementLevel: String,
    val enhancementAppliedStage: String,
    val noiseReductionGoal: String,
    val noiseReductionPipeline: String,
    val temporalDenoiseModel: String,
    val chromaStabilityTarget: String,
    val detailEnhancement: String,
    val detailReconstruction: String,
    val edgeEnhancement: String,
    val edgeHaloPolicy: String,
    val perceptualUpscaleTarget: String,
    val resolutionTruth: String,
    val trueResolutionClaim: String,
    val perceptualResolutionPolicy: String,
    val textureModel: String,
    val edgeConfidenceMap: String,
    val texturePreservationGuard: String,
    val adaptiveDetailReconstruction: String,
    val sensorNoiseProfile: String,
    val shadowChromaLock: String,
    val subpixelEdgeDetection: String,
    val aliasSafeDetailReconstruction: String,
    val sceneComplexityBitrateGuard: String,
    val encoderArtifactGuard: String,
    val encoderArtifactSuppression: String,
    val bitrateFloorMultiplier: Double,
    val nativeOutputResolutionFaked: Boolean,
    val truthfulMasterPreserved: Boolean,
    val masterEnhancementDestructive: Boolean,
    val npv10MasterPolicy: String
) {
    fun asMetadata(): Map<String, String> = mapOf(
        "elitePipelineGeneration" to elitePipelineGeneration,
        "enhancementLevel" to enhancementLevel,
        "enhancementAppliedStage" to enhancementAppliedStage,
        "noiseReductionGoal" to noiseReductionGoal,
        "noiseReductionPipeline" to noiseReductionPipeline,
        "temporalDenoiseModel" to temporalDenoiseModel,
        "chromaStabilityTarget" to chromaStabilityTarget,
        "detailEnhancement" to detailEnhancement,
        "detailReconstruction" to detailReconstruction,
        "edgeEnhancement" to edgeEnhancement,
        "edgeHaloPolicy" to edgeHaloPolicy,
        "perceptualUpscaleTarget" to perceptualUpscaleTarget,
        "resolutionTruth" to resolutionTruth,
        "trueResolutionClaim" to trueResolutionClaim,
        "perceptualResolutionPolicy" to perceptualResolutionPolicy,
        "textureModel" to textureModel,
        "edgeConfidenceMap" to edgeConfidenceMap,
        "texturePreservationGuard" to texturePreservationGuard,
        "adaptiveDetailReconstruction" to adaptiveDetailReconstruction,
        "sensorNoiseProfile" to sensorNoiseProfile,
        "shadowChromaLock" to shadowChromaLock,
        "subpixelEdgeDetection" to subpixelEdgeDetection,
        "aliasSafeDetailReconstruction" to aliasSafeDetailReconstruction,
        "sceneComplexityBitrateGuard" to sceneComplexityBitrateGuard,
        "encoderArtifactGuard" to encoderArtifactGuard,
        "encoderArtifactSuppression" to encoderArtifactSuppression,
        "bitrateFloorMultiplier" to "%.3f".format(Locale.US, bitrateFloorMultiplier),
        "nativeOutputResolutionFaked" to nativeOutputResolutionFaked.toString(),
        "truthfulMasterPreserved" to truthfulMasterPreserved.toString(),
        "masterEnhancementDestructive" to masterEnhancementDestructive.toString(),
        "npv10MasterPolicy" to npv10MasterPolicy
    )
}

internal object EliteVideoEnhancementPolicy {
    fun resolve(request: EliteVideoQualityRequest): EliteVideoEnhancementPlan {
        val masterPreserved = request.mode == EliteVideoPipelineMode.NPV10 ||
            request.mode == EliteVideoPipelineMode.RAW
        return EliteVideoEnhancementPlan(
            elitePipelineGeneration = "NORMAL_PRO_SDR_10X_10_10_PIPELINE",
            enhancementLevel = "STRICT_10X_10_10_NORMAL_PRO_SDR",
            enhancementAppliedStage = when (request.mode) {
                EliteVideoPipelineMode.NPV10 -> "NPV10_EXPORT_ONLY"
                EliteVideoPipelineMode.RAW -> "RAW_EXPORT_ONLY"
                else -> "ENCODED_RECORDING"
            },
            noiseReductionGoal = "ZERO_VISIBLE_NOISE_10_10_TARGET",
            noiseReductionPipeline = "FULL_STRENGTH_MULTI_FRAME_LUMA_CHROMA_10X",
            temporalDenoiseModel = "PREVIEW_RECORDING_NPV10_PARITY_TEMPORAL_10X",
            chromaStabilityTarget = "TILT_LOCKED_COLOR_STABILITY_10_10",
            detailEnhancement = "WHOLE_STEP_10X_MICRO_DETAIL",
            detailReconstruction = "SUBPIXEL_10X_TEXTURE_RECONSTRUCTION",
            edgeEnhancement = "FULL_STRENGTH_10X_EDGE_AWARE_DETAIL",
            edgeHaloPolicy = "TEN_X_HALOGUARD_PREVIEW_RECORDING_EXPORT",
            perceptualUpscaleTarget = perceptualTarget(request.width, request.height),
            resolutionTruth = "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED",
            trueResolutionClaim = trueResolutionClaim(request.width, request.height),
            perceptualResolutionPolicy = "TRUTHFUL_PERCEPTUAL_SCALING_NATIVE_PIXELS_UNCHANGED",
            textureModel = "RICH_TEXTURE_ZERO_NOISE_10_10",
            edgeConfidenceMap = "EDGE_CONFIDENCE_MAP_ACTIVE",
            texturePreservationGuard = "TEXTURE_PRESERVATION_GUARD_ACTIVE",
            adaptiveDetailReconstruction = "ADAPTIVE_DETAIL_RECONSTRUCTION_ACTIVE",
            sensorNoiseProfile = "SENSOR_NOISE_PROFILE_ACTIVE",
            shadowChromaLock = "SHADOW_CHROMA_LOCK_ACTIVE",
            subpixelEdgeDetection = "SUBPIXEL_EDGE_DETECTION_ACTIVE",
            aliasSafeDetailReconstruction = "ALIAS_SAFE_DETAIL_RECONSTRUCTION_ACTIVE",
            sceneComplexityBitrateGuard = "SCENE_COMPLEXITY_BITRATE_GUARD_ACTIVE",
            encoderArtifactGuard = if (request.mode == EliteVideoPipelineMode.NPV10 || request.mode == EliteVideoPipelineMode.RAW) {
                "EXPORT_V4_TEXTURE_GUARDED_ARTIFACT_SUPPRESSION"
            } else {
                "PREVIEW_MATCHED_ADAPTIVE_QP_10X_ARTIFACT_SUPPRESSION"
            },
            encoderArtifactSuppression = "PREVIEW_MATCHED_ADAPTIVE_QP_10X_ARTIFACT_SUPPRESSION",
            bitrateFloorMultiplier = bitrateFloorMultiplier(request.width, request.height, request.fps),
            nativeOutputResolutionFaked = false,
            truthfulMasterPreserved = masterPreserved,
            masterEnhancementDestructive = false,
            npv10MasterPolicy = if (request.mode == EliteVideoPipelineMode.NPV10) {
                "PRIVATE_MASTER_UNMODIFIED"
            } else {
                "NOT_APPLICABLE"
            }
        )
    }

    private fun perceptualTarget(width: Int, height: Int): String {
        val longEdge = maxOf(width, height).coerceAtLeast(1)
        return when {
            longEdge <= 1280 -> "1K_TO_4K_PERCEPTUAL"
            longEdge <= 2048 -> "2K_TO_6K_PERCEPTUAL"
            longEdge <= 4096 -> "4K_TO_16K_PERCEPTUAL"
            longEdge <= 8192 -> "8K_NATIVE_DETAIL_PRESERVATION"
            else -> "SELECTED_RESOLUTION_DETAIL_PRESERVATION"
        }
    }

    private fun trueResolutionClaim(width: Int, height: Int): String =
        "TRUE_NATIVE_${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}_NO_FAKE_SUPER_RESOLUTION"

    private fun bitrateFloorMultiplier(width: Int, height: Int, fps: Int): Double {
        val longEdge = maxOf(width, height).coerceAtLeast(1)
        val resolutionLift = when {
            longEdge <= 1280 -> 1.72
            longEdge <= 2048 -> 1.58
            longEdge <= 4096 -> 1.44
            else -> 1.26
        }
        val fpsLift = if (fps >= 60) 1.12 else 1.0
        return (resolutionLift * fpsLift).coerceIn(1.0, 1.96)
    }
}

private fun capEncodedBitrateToCodecCeiling(value: Long, codec: GpuVideoCodec): Long =
    value.coerceAtMost(RecordingCodecCatalog.maxBitrateMbps(codec).toLong() * 1_000_000L)

internal data class NormalSdrRecordingMasteringPlan(
    val active: Boolean,
    val hdrLookBypass: Boolean,
    val allIntraRequired: Boolean,
    val bitrateFloorBps: Long,
    val bitrateCeilingBps: Long,
    val primaries: String,
    val transfer: String,
    val tenBitMain10Required: Boolean
) {
    fun asMetadata(): Map<String, String> = mapOf(
        "normalSdrMasteringActive" to active.toString(),
        "normalSdrHdrLookBypass" to hdrLookBypass.toString(),
        "normalSdrAllIntraRequired" to allIntraRequired.toString(),
        "normalSdrBitrateFloorBps" to bitrateFloorBps.toString(),
        "normalSdrBitrateCeilingBps" to bitrateCeilingBps.toString(),
        "normalSdrPrimaries" to primaries,
        "normalSdrTransfer" to transfer,
        "normalSdrTenBitMain10Required" to tenBitMain10Required.toString()
    )
}

internal object NormalSdrRecordingMasteringPolicy {
    fun resolve(request: EliteVideoQualityRequest): NormalSdrRecordingMasteringPlan {
        val active = request.mode == EliteVideoPipelineMode.NORMAL
        val config = BitPerfectRecordingPolicy().resolveNormalSdrAllIntraConfig(
            request.width,
            request.height,
            request.fps
        )
        val bitrateFloor = capEncodedBitrateToCodecCeiling(config.minimumBitrateBps, request.codec)
        val bitrateCeiling = capEncodedBitrateToCodecCeiling(config.maximumBitrateBps, request.codec)
            .coerceAtLeast(bitrateFloor)
        return NormalSdrRecordingMasteringPlan(
            active = active,
            hdrLookBypass = active && config.hdrLookBypass,
            allIntraRequired = active && config.isAllIntraRequired,
            bitrateFloorBps = if (active) bitrateFloor else 0L,
            bitrateCeilingBps = if (active) bitrateCeiling else 0L,
            primaries = "BT.709",
            transfer = "SDR_VIDEO",
            tenBitMain10Required = active && config.tenBitMain10Required
        )
    }
}

internal data class Sdr10RecordingMasteringPlan(
    val active: Boolean,
    val wideGamut: Boolean,
    val hdrLookBypass: Boolean,
    val allIntraRequired: Boolean,
    val bitrateFloorBps: Long,
    val bitrateCeilingBps: Long,
    val primaries: String,
    val transfer: String
) {
    fun asMetadata(): Map<String, String> = mapOf(
        "sdr10WideGamut" to wideGamut.toString(),
        "hdrLookBypass" to hdrLookBypass.toString(),
        "allIntraRequired" to allIntraRequired.toString(),
        "sdr10BitrateFloorBps" to bitrateFloorBps.toString(),
        "sdr10BitrateCeilingBps" to bitrateCeilingBps.toString(),
        "sdr10Primaries" to primaries,
        "sdr10Transfer" to transfer,
        "sdr10MasteringActive" to active.toString()
    )
}

internal object Sdr10RecordingMasteringPolicy {
    fun resolve(request: EliteVideoQualityRequest): Sdr10RecordingMasteringPlan {
        val active = request.mode == EliteVideoPipelineMode.PRO
        val config = BitPerfectRecordingPolicy().resolveSdr10AllIntraConfig(
            request.width,
            request.height,
            request.fps
        )
        val bitrateFloor = capEncodedBitrateToCodecCeiling(config.minimumBitrateBps, request.codec)
        val bitrateCeiling = capEncodedBitrateToCodecCeiling(config.maximumBitrateBps, request.codec)
            .coerceAtLeast(bitrateFloor)
        return Sdr10RecordingMasteringPlan(
            active = active,
            wideGamut = active,
            hdrLookBypass = active && config.hdrLookBypass,
            allIntraRequired = active && config.isAllIntraRequired,
            bitrateFloorBps = if (active) bitrateFloor else 0L,
            bitrateCeilingBps = if (active) bitrateCeiling else 0L,
            primaries = if (active) config.colorPrimaries else "BT.709",
            transfer = if (active) config.colorTransfer else "SDR_VIDEO"
        )
    }
}

internal data class NLogRecordingMasteringPlan(
    val active: Boolean,
    val curveVersion: String,
    val dynamicRangeStops: String,
    val dynamicRangeEvidence: String,
    val bitDepthRequired: Int,
    val p010Required: Boolean,
    val allIntraRequired: Boolean,
    val bitrateFloorBps: Long,
    val bitrateCeilingBps: Long,
    val primaries: String,
    val acesWorkflow: String,
    val shadowNoiseSuppression: String,
    val highlightGamutProtection: String,
    val vendorDependency: Boolean
) {
    fun asMetadata(): Map<String, String> = mapOf(
        "nLogMasteringActive" to active.toString(),
        "nLogCurveVersion" to curveVersion,
        "nLogDynamicRangeStops" to dynamicRangeStops,
        "nLogDynamicRangeEvidence" to dynamicRangeEvidence,
        "nLogBitDepthRequired" to bitDepthRequired.toString(),
        "nLogP010Required" to p010Required.toString(),
        "nLogAllIntraRequired" to allIntraRequired.toString(),
        "nLogBitrateFloorBps" to bitrateFloorBps.toString(),
        "nLogBitrateCeilingBps" to bitrateCeilingBps.toString(),
        "nLogPrimaries" to primaries,
        "nLogAcesWorkflow" to acesWorkflow,
        "nLogShadowNoiseSuppression" to shadowNoiseSuppression,
        "nLogHighlightGamutProtection" to highlightGamutProtection,
        "nLogVendorDependency" to vendorDependency.toString()
    )
}

internal object NLogRecordingMasteringPolicy {
    fun resolve(
        request: EliteVideoQualityRequest,
        normalizedLogVersion: String
    ): NLogRecordingMasteringPlan {
        val active = request.mode == EliteVideoPipelineMode.LOG &&
            normalizedLogVersion.startsWith("N-Log")
        val config = BitPerfectRecordingPolicy().resolveNLogAllIntraConfig(request.width, request.height)
        return NLogRecordingMasteringPlan(
            active = active,
            curveVersion = NeoLogCurve.VERSION,
            dynamicRangeStops = "UNVERIFIED",
            dynamicRangeEvidence = NeoLogCurve.DYNAMIC_RANGE_EVIDENCE,
            bitDepthRequired = 10,
            p010Required = active,
            allIntraRequired = active && config.isAllIntraRequired,
            bitrateFloorBps = if (active) config.minimumBitrateBps else 0L,
            bitrateCeilingBps = if (active) config.maximumBitrateBps else 0L,
            primaries = if (active) "BT.2020" else "BT.709",
            acesWorkflow = if (active) "ACES_AP0_AP1_REC2020" else "NONE",
            shadowNoiseSuppression = if (active) "EXPOSURE_ADAPTIVE_JOINT_BILATERAL" else "NONE",
            highlightGamutProtection = if (active) "OKLAB_HUE_PRESERVING_HIGHLIGHT_ROLLOFF" else "NONE",
            vendorDependency = false
        )
    }
}

internal data class NeoHdrDynamicMasteringPlan(
    val active: Boolean,
    val metadataCarrier: String,
    val payloadType: Int,
    val countryCode: Int,
    val providerCode: Int,
    val providerOrientedCode: Int,
    val masteringPeakNits: Int,
    val targetPeakNits: Int,
    val colorVolumeCompression: Double,
    val bitstreamAuditRequired: Boolean,
    val noVendorDependency: Boolean
) {
    fun asMetadata(): Map<String, String> = mapOf(
        "neoHdrDynamicSeiRequired" to "false",
        "neoHdrPrivateSidecarRequired" to active.toString(),
        "neoHdrMetadataCarrier" to metadataCarrier,
        "neoHdrSeiPayloadType" to payloadType.toString(),
        "neoHdrSeiCountryCode" to countryCode.toString(),
        "neoHdrSeiProviderCode" to providerCode.toString(),
        "neoHdrSeiProviderOrientedCode" to providerOrientedCode.toString(),
        "neoHdrMasteringPeakNits" to masteringPeakNits.toString(),
        "neoHdrTargetPeakNits" to targetPeakNits.toString(),
        "neoHdrColorVolumeCompression" to "%.2f".format(Locale.US, colorVolumeCompression),
        "neoHdrBitstreamAuditRequired" to bitstreamAuditRequired.toString(),
        "neoHdrNoVendorDependency" to noVendorDependency.toString()
    )
}

internal object NeoHdrDynamicMasteringPolicy {
    fun resolve(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String
    ): NeoHdrDynamicMasteringPlan {
        val active = request.mode == EliteVideoPipelineMode.HDR &&
            (normalizedHdrVersion in setOf("NDR10+", "NIVUKXVISION") ||
                normalizedHdrVersion.startsWith("APEX_"))
        val nivukx = normalizedHdrVersion == "NIVUKXVISION" ||
            normalizedHdrVersion.startsWith("APEX_")
        return NeoHdrDynamicMasteringPlan(
            active = active,
            metadataCarrier = if (active) "NEO_PRIVATE_SIDECAR" else "NONE",
            payloadType = 0,
            countryCode = 0,
            providerCode = 0,
            providerOrientedCode = 0,
            masteringPeakNits = when {
                !active -> 0
                nivukx -> 10_000
                else -> 8_400
            },
            targetPeakNits = when {
                !active -> 0
                nivukx -> 1_680
                else -> 1_420
            },
            colorVolumeCompression = when {
                !active -> 0.0
                nivukx -> 0.64
                else -> 0.54
            },
            bitstreamAuditRequired = false,
            noVendorDependency = active
        )
    }
}

internal object EliteVideoQualityContract {
    private const val MAX_QUALITY_VALUE = 100

    fun resolve(request: EliteVideoQualityRequest): EliteVideoQualityCertificate {
        val normalizedHdr = normalizeHdrVersion(request.hdrMode)
        val normalizedLog = normalizeLogVersion(request)
        val failureReasons = validateRequest(request, normalizedHdr)
        val passed = failureReasons.isEmpty()
        val tier = if (passed) {
            resolveTier(request, normalizedHdr, normalizedLog)
        } else {
            EliteVideoQualityTier.FAILED_UNSAFE
        }

        return EliteVideoQualityCertificate(
            auditStatus = if (passed) EliteQualityAuditStatus.PASS else EliteQualityAuditStatus.FAIL,
            qualityTier = tier,
            normalizedHdrVersion = normalizedHdr,
            normalizedLogVersion = normalizedLog,
            requiresTenBitTransport = requiresTenBitTransport(request.mode, normalizedHdr),
            truthfulNative10Bit = resolvesTruthfulNativeTenBit(request, tier),
            minimumBitrateBps = resolveMinimumBitrateBps(request, normalizedHdr, normalizedLog),
            qualityValue = MAX_QUALITY_VALUE,
            failureReasons = failureReasons,
            request = request
        )
    }

    fun resolveMinimumBitrateBps(request: EliteVideoQualityRequest): Int =
        resolveMinimumBitrateBps(
            request = request,
            normalizedHdr = normalizeHdrVersion(request.hdrMode),
            normalizedLog = normalizeLogVersion(request)
        )

    fun normalizeHdrVersion(hdrMode: String?): String {
        val normalized = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(hdrMode.orEmpty())
        return when (normalized) {
            "OFF" -> "OFF"
            "NIVUKXVISION" -> "NIVUKXVISION"
            "APEX_SHARP" -> "APEX_SHARP"
            "APEX_ZERO" -> "APEX_ZERO"
            "APEX_CINEMA" -> "APEX_CINEMA"
            else -> "NDR10+"
        }
    }

    fun normalizeLogVersion(profileName: String?): String =
        NeoLogProfilePolicy.effectiveOriginalLogFormat(profileName, logEnabled = true)
            ?: "N-Log Base"

    private fun normalizeLogVersion(request: EliteVideoQualityRequest): String {
        if (!request.requestsLogTransfer()) return "OFF"
        val profile = when (request.mode) {
            EliteVideoPipelineMode.NPV10 -> request.colorScience
                .takeIf { it.hasLogSignal() }
                ?: request.logProfile
            else -> request.logProfile
        }
        return normalizeLogVersion(profile)
    }

    private fun EliteVideoQualityRequest.requestsLogTransfer(): Boolean =
        when (mode) {
            EliteVideoPipelineMode.LOG -> true
            EliteVideoPipelineMode.NPV10 -> colorSpace.hasLogSignal()
            else -> false
        }

    private fun String?.hasLogSignal(): Boolean {
        val key = orEmpty().trim().lowercase(Locale.US)
        return key.contains("n-log") ||
            key.contains("n log") ||
            key.contains("nlog") ||
            key.contains("log")
    }

    private fun validateRequest(
        request: EliteVideoQualityRequest,
        normalizedHdr: String
    ): List<String> = buildList {
        if (request.width <= 0 || request.height <= 0) add("selected resolution must be positive")
        if (request.fps <= 0) add("fps must be positive")
        if (!request.exactSelectedResolution) add("actual capture resolution must match the selected resolution")
        addAll(request.manualColorPlan().failureReasons)

        if (requiresTenBitTransport(request.mode, normalizedHdr)) {
            if (!request.tenBitTransport) add("${request.mode.name} requires real 10-bit transport")
            if (request.storageBitDepth < 10) add("${request.mode.name} requires at least 10-bit storage")
            if (request.sourceBitDepth < 10) add("${request.mode.name} requires a 10-bit source path")
        }

        when (request.mode) {
            EliteVideoPipelineMode.HDR -> {
                if (normalizedHdr == "OFF") add("HDR mode requires a supported HDR version")
            }
            EliteVideoPipelineMode.LOG -> {
                if (normalizeLogVersion(request.logProfile).isBlank()) add("LOG mode requires a supported LOG version")
            }
            EliteVideoPipelineMode.RAW -> {
                if (request.rawBitDepth < 12) add("RAW mode requires at least 12-bit sensor storage")
                if (request.sourceBitDepth < 12) add("RAW mode requires at least a 12-bit source path")
                if (request.storageBitDepth < 12) add("RAW mode requires at least 12-bit storage")
            }
            EliteVideoPipelineMode.NPV10 -> validateNpv10(request)
            EliteVideoPipelineMode.NORMAL,
            EliteVideoPipelineMode.PRO -> Unit
        }
    }

    private fun MutableList<String>.validateNpv10(request: EliteVideoQualityRequest) {
        val payloadFormat = request.payloadFormat
        if (payloadFormat == null) {
            add("NPV10 requires an explicit payload format")
            return
        }
        val requiredStorageBitDepth = payloadFormat.rawBitDepth ?: Npv10ContainerPolicy.BIT_DEPTH
        if (request.storageBitDepth != requiredStorageBitDepth) {
            add("NPV10 storage bit depth must be $requiredStorageBitDepth")
        }
        when (payloadFormat) {
            Npv10PayloadFormat.P010_LE -> {
                if (!request.sourceFormat.equals("YCBCR_P010", ignoreCase = true)) {
                    add("P010 payload requires YCBCR_P010 source format")
                }
                if (request.sourceBitDepth < 10) {
                    add("P010 payload cannot be claimed from an 8-bit source")
                }
            }
            Npv10PayloadFormat.YUV420P10_LE -> {
                if (request.sourceBitDepth < 10) {
                    add("YUV420P10 payload requires a 10-bit source path")
                }
            }
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> {
                if (request.sourceBitDepth > 8) {
                    add("8-bit expanded payload must not be used for a native 10-bit source")
                }
            }
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> {
                if (!request.sourceFormat.equals("RAW_SENSOR", ignoreCase = true)) {
                    add("RAW12 packed payload requires RAW_SENSOR source format")
                }
                if (request.sourceBitDepth < 12) {
                    add("RAW12 packed payload requires at least a 12-bit source path")
                }
            }
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> {
                if (!request.sourceFormat.equals("RAW_PRIVATE", ignoreCase = true)) {
                    add("RAW14 packed payload requires RAW_PRIVATE source format")
                }
                if (request.sourceBitDepth < 14) {
                    add("RAW14 packed payload requires at least a 14-bit source path")
                }
            }
        }
    }

    private fun requiresTenBitTransport(
        mode: EliteVideoPipelineMode,
        normalizedHdr: String
    ): Boolean =
        when (mode) {
            EliteVideoPipelineMode.NORMAL,
            EliteVideoPipelineMode.PRO,
            EliteVideoPipelineMode.LOG -> true
            EliteVideoPipelineMode.HDR -> normalizedHdr != "OFF"
            EliteVideoPipelineMode.RAW,
            EliteVideoPipelineMode.NPV10 -> false
        }

    private fun resolveTier(
        request: EliteVideoQualityRequest,
        normalizedHdr: String,
        normalizedLog: String
    ): EliteVideoQualityTier =
        when (request.mode) {
            EliteVideoPipelineMode.NORMAL -> EliteVideoQualityTier.ELITE_NORMAL_SDR
            EliteVideoPipelineMode.PRO -> EliteVideoQualityTier.ELITE_PRO_SDR_10BIT
            EliteVideoPipelineMode.HDR -> hdrTier(normalizedHdr)
            EliteVideoPipelineMode.LOG -> logTier(normalizedLog)
            EliteVideoPipelineMode.RAW -> rawTier(request.rawBitDepth)
            EliteVideoPipelineMode.NPV10 -> npv10Tier(request)
        }

    private fun hdrTier(normalizedHdr: String): EliteVideoQualityTier =
        when (normalizedHdr) {
            "NDR10+" -> EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT
            "NIVUKXVISION" -> EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
            "APEX_SHARP",
            "APEX_ZERO",
            "APEX_CINEMA" -> EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
            else -> EliteVideoQualityTier.FAILED_UNSAFE
        }

    private fun logTier(normalizedLog: String): EliteVideoQualityTier =
        when (normalizedLog) {
            "N-Log", "N-Log Base" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT
            "N-Log Shadow" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT
            "N-Log Highlight" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT
            "N-Log Cinema" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT
            else -> EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT
        }

    private fun rawTier(rawBitDepth: Int): EliteVideoQualityTier =
        when {
            rawBitDepth >= 16 -> EliteVideoQualityTier.ELITE_RAW_16BIT
            rawBitDepth >= 14 -> EliteVideoQualityTier.ELITE_RAW_14BIT
            else -> EliteVideoQualityTier.ELITE_RAW_12BIT
        }

    private fun npv10Tier(request: EliteVideoQualityRequest): EliteVideoQualityTier =
        when (request.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> EliteVideoQualityTier.ELITE_NPV10_NATIVE_P010
            Npv10PayloadFormat.YUV420P10_LE -> EliteVideoQualityTier.ELITE_NPV10_PLANAR_YUV420P10
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> EliteVideoQualityTier.HIGH_NPV10_8BIT_EXPANDED
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> EliteVideoQualityTier.ELITE_RAW_12BIT
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> EliteVideoQualityTier.ELITE_RAW_14BIT
            null -> EliteVideoQualityTier.FAILED_UNSAFE
        }

    private fun resolvesTruthfulNativeTenBit(
        request: EliteVideoQualityRequest,
        tier: EliteVideoQualityTier
    ): Boolean =
        when (request.mode) {
            EliteVideoPipelineMode.NPV10 -> tier == EliteVideoQualityTier.ELITE_NPV10_NATIVE_P010 ||
                tier == EliteVideoQualityTier.ELITE_NPV10_PLANAR_YUV420P10 ||
                tier == EliteVideoQualityTier.ELITE_RAW_12BIT ||
                tier == EliteVideoQualityTier.ELITE_RAW_14BIT
            EliteVideoPipelineMode.HDR,
            EliteVideoPipelineMode.LOG,
            EliteVideoPipelineMode.NORMAL,
            EliteVideoPipelineMode.PRO -> request.tenBitTransport &&
                request.sourceBitDepth >= 10 &&
                request.storageBitDepth >= 10
            else -> false
        }

    private fun resolveMinimumBitrateBps(
        request: EliteVideoQualityRequest,
        normalizedHdr: String,
        normalizedLog: String
    ): Int {
        if (request.mode == EliteVideoPipelineMode.NPV10 || request.mode == EliteVideoPipelineMode.RAW) {
            return resolveUncompressedFloorBps(request)
        }
        if (request.mode == EliteVideoPipelineMode.NORMAL) {
            return BitPerfectRecordingPolicy()
                .resolveNormalSdrAllIntraConfig(request.width, request.height, request.fps)
                .minimumBitrateBps
                .let { capEncodedBitrateToCodecCeiling(it, request.codec) }
                .coerceToBitrateInt()
        }
        if (request.mode == EliteVideoPipelineMode.PRO) {
            return BitPerfectRecordingPolicy()
                .resolveSdr10AllIntraConfig(request.width, request.height, request.fps)
                .minimumBitrateBps
                .let { capEncodedBitrateToCodecCeiling(it, request.codec) }
                .coerceToBitrateInt()
        }
        val width = request.width.coerceAtLeast(1)
        val height = request.height.coerceAtLeast(1)
        val fps = request.fps.coerceAtLeast(24)
        val codecCeiling = RecordingCodecCatalog.maxBitrateMbps(request.codec).toLong() * 1_000_000L
        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            return codecCeiling.coerceToBitrateInt()
        }

        val pixels = width.toLong() * height.toLong()
        val bitsPerPixelFrame = baseBitsPerPixelFrame(request.codec) *
            modeBitrateMultiplier(request, normalizedHdr, normalizedLog) *
            EliteVideoEnhancementPolicy.resolve(request).bitrateFloorMultiplier
        val pixelFloor = (pixels.toDouble() * fps.toDouble() * bitsPerPixelFrame).roundToLong()
        val policyFloor = if (HighEndQualityPolicy.enabled) {
            HighEndQualityPolicy.minimumBitrateBps(width, height, fps).toLong()
        } else {
            0L
        }
        return maxOf(pixelFloor, policyFloor)
            .coerceAtMost(codecCeiling)
            .coerceToBitrateInt()
    }

    private fun baseBitsPerPixelFrame(codec: GpuVideoCodec): Double =
        when (codec) {
            GpuVideoCodec.AVC -> 3.20
            GpuVideoCodec.HEVC -> 2.45
            GpuVideoCodec.AV1 -> 2.05
        }

    private fun modeBitrateMultiplier(
        request: EliteVideoQualityRequest,
        normalizedHdr: String,
        normalizedLog: String
    ): Double {
        val colorBoost = if (request.colorSpace.isWideGamut()) 1.08 else 1.0
        val bitDepthBoost = if (request.tenBitTransport || request.storageBitDepth >= 10) 1.12 else 1.0
        val modeBoost = when (request.mode) {
            EliteVideoPipelineMode.NORMAL -> 1.00
            EliteVideoPipelineMode.PRO -> 1.18
            EliteVideoPipelineMode.HDR -> when (normalizedHdr) {
                "NDR10+" -> 1.66
                "NIVUKXVISION" -> 1.76
                "APEX_SHARP" -> 1.92
                "APEX_ZERO" -> 1.84
                "APEX_CINEMA" -> 1.88
                else -> 1.24
            }
            EliteVideoPipelineMode.LOG -> when (normalizedLog) {
                "N-Log", "N-Log Base" -> 1.88
                "N-Log Shadow" -> 1.92
                "N-Log Highlight" -> 1.94
                "N-Log Cinema" -> 1.98
                else -> 1.44
            }
            EliteVideoPipelineMode.RAW,
            EliteVideoPipelineMode.NPV10 -> 1.0
        }
        return colorBoost * bitDepthBoost * modeBoost
    }

    private fun resolveUncompressedFloorBps(request: EliteVideoQualityRequest): Int {
        val width = request.width.coerceAtLeast(1).toLong()
        val height = request.height.coerceAtLeast(1).toLong()
        val fps = request.fps.coerceAtLeast(1).toLong()
        val bitsPerFrame = if (request.mode == EliteVideoPipelineMode.RAW) {
            width * height * request.storageBitDepth.coerceAtLeast(12).toLong()
        } else if (request.mode == EliteVideoPipelineMode.NPV10 && request.payloadFormat?.isRawPacked == true) {
            Npv10ContainerPolicy.estimatedRawPackedPayloadBytesPerFrame(request.width, request.height) * Byte.SIZE_BITS
        } else {
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                request.width,
                request.height,
                request.payloadFormat ?: Npv10PayloadFormat.P010_LE
            ) * Byte.SIZE_BITS
        }
        return (bitsPerFrame * fps).coerceToBitrateInt()
    }

    private fun String.isWideGamut(): Boolean {
        val key = lowercase(Locale.US)
        return key.contains("2020") ||
            key.contains("2100") ||
            key.contains("p3") ||
            key.contains("wide") ||
            key.contains("log") ||
            key.contains("neo") ||
            key.contains("aces")
    }

    private fun Long.coerceToBitrateInt(): Int =
        coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
