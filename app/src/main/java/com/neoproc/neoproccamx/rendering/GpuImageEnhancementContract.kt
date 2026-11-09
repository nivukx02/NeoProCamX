package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.BeyondImaxEliteProfile
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.camera.DualGainHdrFusionPolicy
import com.neoproc.neoproccamx.color.NormalSdrVideoQualityPolicy
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementPlan
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoDetailRecordClarityPolicy
import com.neoproc.neoproccamx.recording.Npv10ExportEnhancementProfile
import java.util.Locale

enum class GpuSignalFamily(val id: Int) { SDR(1), HDR(2), LOG(3), RAW(4) }

enum class GpuColorSpace(val id: Int) {
    REC709(1),
    REC2020(2),
    NEO_WIDE_GAMUT(3),
    NEO_ACES_AP1(4),
    NEO_ACES_AP0(5)
}

enum class GpuColorScience(val id: Int) {
    REC709_STANDARD(1),
    N_LOG_BASE(10),
    N_LOG_SHADOW(11),
    N_LOG_HIGHLIGHT(12),
    N_LOG_CINEMA(13),
    NDR10_PLUS(20),
    NIVUKX_VISION(21),
    NATURAL(30),
    NEO_ACES_HIGH_END(31)
}

enum class GpuActiveTransport(val id: Int) {
    REC709_SDR(1),
    BT2020_SDR(2),
    BT2020_LOG_SDR(3),
    BT2100_PQ_MAIN10(4),
    ANDROID_PQ_BT2020_MAIN10(4),
    RAW_MONITOR(5)
}

data class GpuColorProfileNormalization(
    val colorSpace: GpuColorSpace,
    val colorScience: GpuColorScience,
    val recommendedTransport: GpuActiveTransport,
    val migrationApplied: Boolean,
    val exportLabel: String
)

data class GpuAuthoritativeColorProfile(
    val colorSpaceName: String,
    val colorScienceName: String,
    val modeOverrideApplied: Boolean
)

/**
 * Makes the normalized render mode authoritative for proprietary NDR colour identity.
 *
 * The general colour-profile preference may legitimately remain "Natural" while the user switches
 * HDR modes.  Letting that asynchronous preference label the GPU packet created an invalid mixed state
 * such as NATURAL + BT.2020 SDR inside an active NIVUKX/PQ session.  Resolve the two names together at
 * the render boundary so preview, realtime compute and recording cannot observe different identities.
 */
object GpuAuthoritativeColorProfilePolicy {
    fun resolve(
        normalizedHdrMode: String?,
        requestedColorSpaceName: String?,
        requestedColorScienceName: String?
    ): GpuAuthoritativeColorProfile {
        val modeKey = normalizedHdrMode.orEmpty()
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9]+"), "")
        return when (modeKey) {
            "NDR10", "NDR10PLUS" -> GpuAuthoritativeColorProfile(
                colorSpaceName = "Rec.2020",
                colorScienceName = "NDR10+",
                modeOverrideApplied = true
            )
            "NIVUKX", "NIVUKXVISION" -> GpuAuthoritativeColorProfile(
                colorSpaceName = "Rec.2020",
                colorScienceName = "NIVUKX Vision",
                modeOverrideApplied = true
            )
            // null/blank means this legacy caller did not supply mode authority. Preserve its
            // explicit profile exactly; sanitization is valid only when a real non-NDR mode owns
            // the packet (OFF/LOG/RAW/other normalized delivery mode).
            "" -> requestedProfile(
                requestedColorSpaceName = requestedColorSpaceName,
                requestedColorScienceName = requestedColorScienceName
            )
            else -> resolveNonNdrProfile(
                requestedColorSpaceName = requestedColorSpaceName,
                requestedColorScienceName = requestedColorScienceName
            )
        }
    }

    /**
     * A colour-profile preference is persisted independently from the active HDR mode. During a
     * mode transition it can therefore still contain an NDR/NIVUKX label after the render owner has
     * already committed OFF, LOG, or RAW. NDR identity must never escape that mode boundary: doing
     * so produces an OFF-correction/NDR-science packet which the atomic runtime correctly rejects.
     *
     * Preserve every valid non-NDR request byte-for-byte. Only remove a stale project-NDR identity;
     * keep its requested gamut (Rec.2020 remains Rec.2020) and select the neutral non-NDR science.
     * The real NDR/NIVUKX branches above remain unchanged and authoritative.
     */
    private fun resolveNonNdrProfile(
        requestedColorSpaceName: String?,
        requestedColorScienceName: String?
    ): GpuAuthoritativeColorProfile {
        val requestedSpace = requestedColorSpaceName.orEmpty()
        val requestedScience = requestedColorScienceName.orEmpty()
        val staleSpaceIdentity = containsProjectNdrIdentity(requestedSpace)
        val staleScienceIdentity = containsProjectNdrIdentity(requestedScience)
        if (!staleSpaceIdentity && !staleScienceIdentity) {
            return requestedProfile(
                requestedColorSpaceName = requestedSpace,
                requestedColorScienceName = requestedScience
            )
        }

        val sanitizedSpace = if (staleSpaceIdentity) {
            val spaceKey = normalizedIdentityKey(requestedSpace)
            when {
                spaceKey.contains("709") -> "Rec.709"
                // All currently supported project NDR/NIVUKX spaces are Rec.2020. Retain that
                // gamut when removing the stale mode suffix rather than silently narrowing it.
                else -> "Rec.2020"
            }
        } else {
            requestedSpace
        }
        return GpuAuthoritativeColorProfile(
            colorSpaceName = sanitizedSpace,
            colorScienceName = if (staleScienceIdentity) "Natural" else requestedScience,
            modeOverrideApplied = true
        )
    }

    private fun requestedProfile(
        requestedColorSpaceName: String?,
        requestedColorScienceName: String?
    ): GpuAuthoritativeColorProfile = GpuAuthoritativeColorProfile(
        colorSpaceName = requestedColorSpaceName.orEmpty(),
        colorScienceName = requestedColorScienceName.orEmpty(),
        modeOverrideApplied = false
    )

    private fun containsProjectNdrIdentity(value: String): Boolean {
        val key = normalizedIdentityKey(value)
        return key.contains("NDR10") || key.contains("NIVUKX")
    }

    private fun normalizedIdentityKey(value: String): String = value
        .uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9]+"), "")
}

object GpuColorProfileNormalizer {
    fun normalize(colorSpaceName: String?, colorScienceName: String?): GpuColorProfileNormalization {
        val colorSpaceKey = normalizedProfileText(colorSpaceName.orEmpty())
        val colorScienceKey = normalizedProfileText(colorScienceName.orEmpty())
        val combined = listOf(colorSpaceKey, colorScienceKey)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
        val scienceSource = colorScienceKey.ifBlank { combined }
        val colorSpaceMigrated = containsExternalWideGamutColorSpace(colorSpaceKey)
        val logScienceMigrated = containsExternalLogScienceToken(combined)
        val migrated = colorSpaceMigrated || logScienceMigrated
        val colorScience = when {
            containsPhrase(combined, "neo aces") ||
                containsPhrase(combined, "acescg") ||
                containsPhrase(combined, "aces ap1") ||
                containsPhrase(combined, "aces ap0") ||
                containsPhrase(combined, "aces") -> GpuColorScience.NEO_ACES_HIGH_END
            containsPhrase(combined, "nivukx") -> GpuColorScience.NIVUKX_VISION
            containsPhrase(combined, "ndr10") || containsPhrase(combined, "ndr10+") -> GpuColorScience.NDR10_PLUS
            containsPhrase(scienceSource, "n log cinema") || logScienceMigrated -> GpuColorScience.N_LOG_CINEMA
            containsPhrase(scienceSource, "n log shadow") -> GpuColorScience.N_LOG_SHADOW
            containsPhrase(scienceSource, "n log highlight") -> GpuColorScience.N_LOG_HIGHLIGHT
            containsPhrase(scienceSource, "n log") || containsPhrase(scienceSource, "log") -> GpuColorScience.N_LOG_BASE
            containsPhrase(scienceSource, "natural") -> GpuColorScience.NATURAL
            else -> GpuColorScience.REC709_STANDARD
        }
        val colorSpace = when {
            containsPhrase(colorSpaceKey, "neo aces ap0") || containsPhrase(colorSpaceKey, "aces ap0") ->
                GpuColorSpace.NEO_ACES_AP0
            containsPhrase(colorSpaceKey, "neo aces") ||
                containsPhrase(colorSpaceKey, "acescg") ||
                containsPhrase(colorSpaceKey, "aces ap1") ||
                containsPhrase(colorSpaceKey, "aces") -> GpuColorSpace.NEO_ACES_AP1
            containsPhrase(colorSpaceKey, "ndr10") ||
                containsPhrase(colorSpaceKey, "ndr10+") ||
                containsPhrase(colorSpaceKey, "nivukx") ||
                containsPhrase(colorSpaceKey, "apex") -> GpuColorSpace.REC2020
            containsPhrase(colorSpaceKey, "2020") || containsPhrase(colorSpaceKey, "2100") -> GpuColorSpace.REC2020
            containsPhrase(colorSpaceKey, "neo wide") ||
                containsPhrase(colorSpaceKey, "neogamut") ||
                containsPhrase(colorSpaceKey, "wide gamut") ||
                colorSpaceMigrated -> GpuColorSpace.NEO_WIDE_GAMUT
            else -> GpuColorSpace.REC709
        }
        val transport = when (colorScience) {
            GpuColorScience.NDR10_PLUS,
            GpuColorScience.NIVUKX_VISION -> GpuActiveTransport.BT2100_PQ_MAIN10
            GpuColorScience.N_LOG_BASE,
            GpuColorScience.N_LOG_SHADOW,
            GpuColorScience.N_LOG_HIGHLIGHT,
            GpuColorScience.N_LOG_CINEMA -> GpuActiveTransport.BT2020_LOG_SDR
            else -> if (
                colorSpace == GpuColorSpace.REC2020 ||
                colorSpace == GpuColorSpace.NEO_WIDE_GAMUT ||
                colorSpace == GpuColorSpace.NEO_ACES_AP1 ||
                colorSpace == GpuColorSpace.NEO_ACES_AP0
            ) {
                GpuActiveTransport.BT2020_SDR
            } else {
                GpuActiveTransport.REC709_SDR
            }
        }
        return GpuColorProfileNormalization(
            colorSpace = colorSpace,
            colorScience = colorScience,
            recommendedTransport = transport,
            migrationApplied = migrated,
            exportLabel = "${colorSpace.name}:${colorScience.name}"
        )
    }

    private fun normalizedProfileText(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9+]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun containsPhrase(value: String, phrase: String): Boolean {
        if (value.isBlank()) return false
        val normalizedPhrase = normalizedProfileText(phrase)
        if (normalizedPhrase.isBlank()) return false
        return Regex("(^|\\s)${Regex.escape(normalizedPhrase)}($|\\s)")
            .containsMatchIn(value)
    }

    private fun containsExternalWideGamutColorSpace(value: String): Boolean =
        listOf(
            "display p3",
            "dci p3",
            "p3",
            "p3 d65",
            "external wide gamut",
            "external cinema gamut",
            "wide gamut rgb"
        ).any { containsPhrase(value, it) }

    private fun containsExternalLogScienceToken(value: String): Boolean =
        listOf(
            "external log",
            "external cinema log",
            "external cinema gamut",
            "legacy log",
            "legacy cinema log"
        ).any { containsPhrase(value, it) }
}

object GpuSignalFamilyResolver {
    fun resolve(
        mode: String,
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        rawEnabled: Boolean,
        profile: String
    ): GpuSignalFamily {
        val key = listOf(mode, profile)
            .joinToString(separator = " ")
            .lowercase(Locale.US)
        return when {
            rawEnabled || key.contains("raw") -> GpuSignalFamily.RAW
            logEnabled || key.contains("n-log") || key.contains("log") -> GpuSignalFamily.LOG
            hdrEnabled || key.contains("ndr10") || key.contains("nivukx") || key.contains("hdr") -> GpuSignalFamily.HDR
            else -> GpuSignalFamily.SDR
        }
    }
}

data class GpuImageEnhancementUniforms(
    val signalFamily: GpuSignalFamily,
    val colorSpace: GpuColorSpace,
    val colorScience: GpuColorScience,
    val activeTransport: GpuActiveTransport,
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val shadowBoost: Float,
    val sharpening: Float,
    val warmth: Float,
    val contrast: Float,
    val saturation: Float,
    val highlightCompression: Float,
    val logStrength: Float,
    val videoHdrFusionStrength: Float,
    val shadowChromaDeadband: Float,
    val aliasSafeMaxDetailDelta: Float,
    val fineTextureThreshold: Float,
    val mediumEdgeThreshold: Float,
    val strongEdgeThreshold: Float,
    val fineTextureDetailGain: Float,
    val mediumDetailGain: Float,
    val strongDetailGain: Float,
    val temporalLumaBlend: Float,
    val temporalChromaBlend: Float,
    val iso: Float,
    val noiseMetric: Float,
    val localChromaSpikeSmoothing: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val skinDetailProtection: Float,
    val migrationApplied: Boolean,
    val dualGainFusionEnabled: Boolean,
    val dualGainMotionMaskEnabled: Boolean,
    val dualGainKGain: Float,
    val dualGainNoiseFloor: Float,
    val dualGainSaturationThreshold: Float,
    val dualGainTimestampSkew: Float,
    val sensorShotNoiseCoefficient: Float,
    val sensorReadNoiseCoefficient: Float,
    val detailProfileId: Float,
    val subjectMaskAvailable: Float,
    val subjectDetailGain: Float,
    val subjectMaskFeather: Float,
    val v20ZeroGrainClarityActive: Boolean = false,
    val v20ZeroGrainLock: Float = 0f,
    val v20RgbGrainQuarantine: Float = 0f,
    val v20MotionBlurRejection: Float = 0f,
    val v20MicroDetailHonesty: Float = 0f,
    val v20ZeroGrainClaritySignature: String = "",
    // AI Motion Compensation: explicitly-gated temporal blend for the offscreen-EGL record
    // shader. NON-ZERO only when the user turned AI Motion on (heuristic/neural active). It is
    // deliberately NOT part of toFloatArray() (that array is the checksummed Vulkan/NPV10 ABI);
    // the offscreen bridge reads this field directly.
    val aiMotionTemporalBlend: Float = 0f,
    val signature: String,
    // Not part of the native float ABI. Characteristics/fallback models describe a sensor-wide
    // envelope, not the current exposure; only a CaptureResult noise profile may drive live caps.
    val sensorNoiseModelPerFrame: Boolean = false
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        signalFamily.id.toFloat(),
        colorSpace.id.toFloat(),
        colorScience.id.toFloat(),
        activeTransport.id.toFloat(),
        temporalBlend,
        spatialDenoise,
        chromaDenoise,
        shadowBoost,
        sharpening,
        warmth,
        contrast,
        saturation,
        highlightCompression,
        logStrength,
        videoHdrFusionStrength,
        shadowChromaDeadband,
        aliasSafeMaxDetailDelta,
        fineTextureThreshold,
        mediumEdgeThreshold,
        strongEdgeThreshold,
        fineTextureDetailGain,
        mediumDetailGain,
        strongDetailGain,
        temporalLumaBlend,
        temporalChromaBlend,
        (iso / 12_800f).coerceIn(0f, 1f),
        noiseMetric.coerceIn(
            0f,
            if (signalFamily == GpuSignalFamily.SDR) 1f else 10f
        ),
        localChromaSpikeSmoothing,
        sharpeningFloor,
        sharpeningCeiling,
        skinDetailProtection,
        if (migrationApplied) 1f else 0f,
        if (dualGainFusionEnabled) 1f else 0f,
        if (dualGainMotionMaskEnabled) 1f else 0f,
        (dualGainKGain / 64f).coerceIn(0f, 1f),
        (dualGainNoiseFloor / 0.20f).coerceIn(0f, 1f),
        ((dualGainSaturationThreshold - 0.72f) / (0.995f - 0.72f)).coerceIn(0f, 1f),
        dualGainTimestampSkew.coerceIn(0f, 1f),
        sensorShotNoiseCoefficient.coerceIn(0f, 0.25f),
        sensorReadNoiseCoefficient.coerceIn(0f, 0.10f),
        detailProfileId,
        subjectMaskAvailable,
        subjectDetailGain.coerceIn(0f, 1f),
        subjectMaskFeather.coerceIn(0f, 1f)
    )

    companion object {
        private const val STRICT_GPU_SPATIAL_DENOISE_FLOOR = 0.22f
        private const val STRICT_GPU_SDR_SPATIAL_DENOISE_FLOOR = 0.18f
        private const val STRICT_GPU_LOG_SPATIAL_DENOISE_FLOOR = 0.16f
        private const val STRICT_GPU_CHROMA_DENOISE_FLOOR = 0.34f
        // SDR recorded-file colour richness: the record shader desaturates flat/shadow chroma by up to
        // uChromaDenoise * 0.30 (chromaGuard). Pinning this at ~1.0 muted SDR colour in flat regions
        // ("recorded video looks low quality / washed"). Hold a lower floor so SDR keeps its colour while
        // still removing shadow chroma speckle (effective flat-area desat ceiling ~0.55*0.30 = 0.165).
        // LOG/RAW/HDR keep the full 1.0 floor (their heavier shadow noise still wants max chroma-NR).
        private const val STRICT_GPU_SDR_CHROMA_DENOISE_FLOOR = 0.28f
        private const val STRICT_GPU_SHARPENING_FLOOR = 0.30f
        private const val STRICT_GPU_SDR_SHARPENING_FLOOR = 0.34f
        private const val STRICT_GPU_LOG_SHARPENING_FLOOR = 0.24f
        private const val STRICT_GPU_NOISE_METRIC_FLOOR = 0.08f
        private const val STRICT_GPU_SDR_NOISE_METRIC_FLOOR = 0.06f
        private const val ADAPTIVE_SDR_DETAIL_GAIN_CEILING = 1.0f
        private const val NON_SDR_DETAIL_GAIN_CEILING = 10.0f

        internal fun fromSceneProfile(
            profile: SceneRenderProfile,
            signalFamily: GpuSignalFamily,
            colorSpaceName: String,
            colorScienceName: String,
            activeTransport: GpuActiveTransport? = null,
            exportProfile: Npv10ExportEnhancementProfile? = null,
            iso: Float = 100f,
            noiseMetric: Float = profile.noiseMetrics.lumaNoise,
            localChromaSpikeSmoothing: Float = 0f,
            sharpeningFloor: Float = 0f,
            sharpeningCeiling: Float = 0f,
            skinDetailProtection: Float = 0f,
            sensorShotNoiseCoefficient: Float = BeyondImaxEliteProfile.SHOT_NOISE_COEFFICIENT,
            sensorReadNoiseCoefficient: Float = BeyondImaxEliteProfile.READ_NOISE_COEFFICIENT,
            sensorNoiseModelPerFrame: Boolean = false,
            videoDetailProfile: VideoDetailProfile? = null,
            subjectDetailPlan: SubjectDetailEnhancementPlan? = null,
            forceNormalSdrRealtimeLock: Boolean = false,
            sdrQualityIntent: SdrQualityIntent? = null,
            aiRecordEnhancementActive: Boolean = false,
            aiMeasuredPerceptualSharpness: Float = 0f,
            aiMeasuredNoiseLevel: Float = 0f,
            normalizedHdrMode: String? = null
        ): GpuImageEnhancementUniforms {
            val authoritativeProfile = GpuAuthoritativeColorProfilePolicy.resolve(
                normalizedHdrMode = normalizedHdrMode,
                requestedColorSpaceName = colorSpaceName,
                requestedColorScienceName = colorScienceName
            )
            val normalized = GpuColorProfileNormalizer.normalize(
                authoritativeProfile.colorSpaceName,
                authoritativeProfile.colorScienceName
            )
            val transport = if (forceNormalSdrRealtimeLock && signalFamily == GpuSignalFamily.SDR) {
                if (normalized.colorSpace == GpuColorSpace.REC709) {
                    GpuActiveTransport.REC709_SDR
                } else {
                    GpuActiveTransport.BT2020_SDR
                }
            } else {
                activeTransport ?: normalized.recommendedTransport
            }
            val resolvedSdrQualityIntent = sdrQualityIntent
                ?: SdrQualityIntent.NORMAL.takeIf { forceNormalSdrRealtimeLock }
            val normalSdrRealtimeLock = resolvedSdrQualityIntent != null &&
                signalFamily == GpuSignalFamily.SDR &&
                (transport == GpuActiveTransport.REC709_SDR || transport == GpuActiveTransport.BT2020_SDR)
            val adaptiveSdrQuality = resolvedSdrQualityIntent?.takeIf { normalSdrRealtimeLock }?.let { intent ->
                NormalSdrVideoQualityPolicy.resolve(
                    intent = intent,
                    temporalBlend = profile.temporalBlend,
                    spatialDenoise = profile.spatialDenoise,
                    chromaDenoise = profile.chromaDenoise,
                    shadowBoost = profile.shadowBoost,
                    sharpening = profile.sharpening,
                    warmth = profile.warmth,
                    contrast = profile.contrast,
                    saturation = profile.saturation,
                    highlightCompression = profile.highlightCompression,
                    noiseMetric = noiseMetric,
                    localChromaSpikeSmoothing = localChromaSpikeSmoothing,
                    sharpeningFloor = sharpeningFloor,
                    sharpeningCeiling = sharpeningCeiling,
                    skinDetailProtection = skinDetailProtection
                )
            }
            val resolvedExportProfile = exportProfile ?: resolveRealtimeExportProfile(
                signalFamily = signalFamily,
                colorSpaceName = authoritativeProfile.colorSpaceName,
                colorScienceName = authoritativeProfile.colorScienceName,
                activeTransport = transport
            )
            val controlledSubjectPlan = subjectDetailPlan?.takeIf { it.profile == videoDetailProfile }
            val strictGpuFloors = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
            val spatialFloor = if (strictGpuFloors) {
                when (signalFamily) {
                    GpuSignalFamily.LOG -> STRICT_GPU_LOG_SPATIAL_DENOISE_FLOOR
                    GpuSignalFamily.SDR -> STRICT_GPU_SDR_SPATIAL_DENOISE_FLOOR
                    GpuSignalFamily.HDR -> STRICT_GPU_SPATIAL_DENOISE_FLOOR
                    GpuSignalFamily.RAW -> 0f
                }
            } else {
                0f
            }
            val sharpeningFloorForSignal = if (strictGpuFloors) {
                when (signalFamily) {
                    GpuSignalFamily.LOG -> STRICT_GPU_LOG_SHARPENING_FLOOR
                    GpuSignalFamily.SDR -> STRICT_GPU_SDR_SHARPENING_FLOOR
                    GpuSignalFamily.HDR -> STRICT_GPU_SHARPENING_FLOOR
                    GpuSignalFamily.RAW -> 0f
                }
            } else {
                0f
            }
            val selectedDetailProfileId = when (videoDetailProfile) {
                VideoDetailProfile.FOCUSED_DETAIL -> 1f
                VideoDetailProfile.NATURAL_DETAIL -> 2f
                VideoDetailProfile.EXTREME_DETAIL -> 3f
                null -> 0f
            }
            val realtimeDetailGainCeiling = if (normalSdrRealtimeLock) {
                ADAPTIVE_SDR_DETAIL_GAIN_CEILING
            } else {
                NON_SDR_DETAIL_GAIN_CEILING
            }
            val adaptiveSubjectDetailBudget = adaptiveSdrQuality?.let { quality ->
                val skinProtectionAttenuation =
                    1f - quality.skinDetailProtection.coerceIn(0f, 1f) * 0.5f
                (quality.sharpeningHeadroom * skinProtectionAttenuation).coerceIn(0f, 1f)
            } ?: 1f
            val requestedSubjectDetailGain = controlledSubjectPlan
                ?.additiveDetailGain
                ?.takeIf { it.isFinite() }
                ?: 0f
            val baseTemporalBlend = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).temporalBlend
            } else {
                profile.temporalBlend.coerceIn(0f, 1f)
            }
            val baseSpatialDenoise = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).spatialDenoise
            } else {
                maxOf(profile.spatialDenoise, spatialFloor).coerceIn(0f, 1f)
            }
            val baseChromaDenoise = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).chromaDenoise
            } else {
                maxOf(
                    profile.chromaDenoise,
                    if (strictGpuFloors) {
                        when (signalFamily) {
                            GpuSignalFamily.SDR -> STRICT_GPU_SDR_CHROMA_DENOISE_FLOOR
                            GpuSignalFamily.HDR,
                            GpuSignalFamily.LOG -> STRICT_GPU_CHROMA_DENOISE_FLOOR
                            GpuSignalFamily.RAW -> 0f
                        }
                    } else {
                        0f
                    }
                ).coerceIn(0f, 1f)
            }
            val baseSharpening = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).sharpening
            } else {
                maxOf(profile.sharpening, sharpeningFloorForSignal).coerceIn(0f, 1f)
            }
            val baseLocalChromaSpikeSmoothing = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).localChromaSpikeSmoothing
            } else {
                localChromaSpikeSmoothing.coerceIn(0f, 1f)
            }
            val baseSharpeningFloor = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).sharpeningFloor
            } else {
                sharpeningFloor.coerceIn(0f, 1f)
            }
            val baseSharpeningCeiling = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).sharpeningCeiling
            } else {
                sharpeningCeiling.coerceIn(0f, 1f)
            }
            val baseSkinDetailProtection = if (normalSdrRealtimeLock) {
                requireNotNull(adaptiveSdrQuality).skinDetailProtection
            } else {
                skinDetailProtection.coerceIn(0f, 1f)
            }
            val baseSubjectDetailGain = requestedSubjectDetailGain.coerceIn(
                0f,
                adaptiveSubjectDetailBudget
            )
            val v20ClarityPlan = V20ZeroGrainClarityPolicy.resolve(
                V20ZeroGrainClarityRequest(
                    signalFamily = signalFamily,
                    strictHighEnd = strictGpuFloors,
                    normalSdrRealtimeLock = normalSdrRealtimeLock,
                    profile = profile,
                    noiseMetric = noiseMetric,
                    iso = iso,
                    localChromaSpikeSmoothing = baseLocalChromaSpikeSmoothing,
                    sharpeningFloor = baseSharpeningFloor,
                    sharpeningCeiling = baseSharpeningCeiling,
                    skinDetailProtection = baseSkinDetailProtection,
                    videoDetailProfile = videoDetailProfile,
                    subjectMaskAvailable = controlledSubjectPlan?.subjectMaskPresent == true,
                    temporalBlend = baseTemporalBlend,
                    spatialDenoise = baseSpatialDenoise,
                    chromaDenoise = baseChromaDenoise,
                    sharpening = baseSharpening,
                    subjectDetailGain = baseSubjectDetailGain
                )
            )
            // F-DETAIL: make FOCUSED/NATURAL/EXTREME genuinely diverge on recorded pixels,
            // bounded by the same halo floor/ceiling the clarity plan already enforces.
            // FOCUSED/null returns the plan sharpening unchanged (default byte-identical).
            val detailRecordSharpening = VideoDetailRecordClarityPolicy.resolveRecordSharpening(
                base = v20ClarityPlan.sharpening,
                profile = videoDetailProfile,
                floor = v20ClarityPlan.sharpeningFloor,
                ceiling = v20ClarityPlan.sharpeningCeiling
            )
            // F-AIENH: route the measured AI enhancement (sharpness deficit + noise) into
            // the live record sharpen/denoise uniforms. Disabled -> base values unchanged.
            val aiRecordBoost = AiEnhancementRecordBoostPolicy.resolve(
                enabled = aiRecordEnhancementActive,
                measuredPerceptualSharpness = aiMeasuredPerceptualSharpness,
                measuredNoiseLevel = aiMeasuredNoiseLevel,
                baseSharpening = detailRecordSharpening,
                baseSpatialDenoise = v20ClarityPlan.spatialDenoise,
                sharpeningCeiling = v20ClarityPlan.sharpeningCeiling,
                skinDetailProtection = baseSkinDetailProtection
            )
            val directGalleryQualityLiftActive = normalSdrRealtimeLock &&
                resolvedSdrQualityIntent == SdrQualityIntent.NORMAL &&
                signalFamily == GpuSignalFamily.SDR
            val detailLift = when (videoDetailProfile) {
                VideoDetailProfile.NATURAL_DETAIL -> 0.055f
                VideoDetailProfile.FOCUSED_DETAIL -> 0.070f
                VideoDetailProfile.EXTREME_DETAIL -> 0.085f
                null -> 0.055f
            }
            val safeRecordBoostSharpening = aiRecordBoost.boostedSharpening.finiteOrZero().coerceIn(0f, 1f)
            val safeRecordBoostSpatialDenoise = aiRecordBoost.boostedSpatialDenoise.finiteOrZero().coerceIn(0f, 1f)
            val safeClaritySharpeningCeiling = v20ClarityPlan.sharpeningCeiling.finiteOrZero().coerceIn(
                safeRecordBoostSharpening,
                1f
            )
            val safeClarityChromaDenoise = v20ClarityPlan.chromaDenoise.finiteOrZero().coerceIn(0f, 1f)
            val safeClarityChromaSmoothing = v20ClarityPlan.localChromaSpikeSmoothing.finiteOrZero().coerceIn(0f, 1f)
            val directGallerySharpening = if (directGalleryQualityLiftActive) {
                (safeRecordBoostSharpening + detailLift)
                    .coerceIn(safeRecordBoostSharpening, safeClaritySharpeningCeiling)
            } else {
                safeRecordBoostSharpening
            }
            val directGallerySpatialDenoise = if (directGalleryQualityLiftActive) {
                val noisePressure = maxOf(
                    noiseMetric.finiteOrZero().coerceIn(0f, 1f),
                    aiMeasuredNoiseLevel.finiteOrZero().coerceIn(0f, 1f)
                )
                val cap = maxOf(safeRecordBoostSpatialDenoise, 0.86f)
                (safeRecordBoostSpatialDenoise + 0.040f + noisePressure * 0.050f).coerceAtMost(cap)
            } else {
                safeRecordBoostSpatialDenoise
            }
            val directGalleryChromaDenoise = if (directGalleryQualityLiftActive) {
                val cap = maxOf(safeClarityChromaDenoise, 0.72f)
                (safeClarityChromaDenoise + 0.035f).coerceAtMost(cap)
            } else {
                safeClarityChromaDenoise
            }
            val directGallerySaturation = if (directGalleryQualityLiftActive) {
                val baseSaturation = requireNotNull(adaptiveSdrQuality).saturation
                val cap = maxOf(baseSaturation, 1.16f)
                (baseSaturation + 0.045f).coerceAtMost(cap)
            } else {
                if (normalSdrRealtimeLock) requireNotNull(adaptiveSdrQuality).saturation else profile.saturation.coerceIn(0f, 2f)
            }
            val directGalleryContrast = if (directGalleryQualityLiftActive) {
                val baseContrast = requireNotNull(adaptiveSdrQuality).contrast
                val cap = maxOf(baseContrast, 1.14f)
                (baseContrast + 0.035f).coerceAtMost(cap)
            } else {
                if (normalSdrRealtimeLock) requireNotNull(adaptiveSdrQuality).contrast else profile.contrast.coerceIn(0f, 2f)
            }
            val directGalleryLocalChromaSmoothing = if (directGalleryQualityLiftActive) {
                val cap = maxOf(safeClarityChromaSmoothing, 0.76f)
                (safeClarityChromaSmoothing + 0.040f).coerceAtMost(cap)
            } else {
                safeClarityChromaSmoothing
            }
            val directGalleryFineTextureGain = if (directGalleryQualityLiftActive) {
                val base = resolvedExportProfile.fineTextureDetailGain.toFloat()
                val cap = maxOf(base, realtimeDetailGainCeiling)
                (base + 0.070f).coerceAtMost(cap)
            } else {
                resolvedExportProfile.fineTextureDetailGain.toFloat().coerceIn(0f, realtimeDetailGainCeiling)
            }
            val directGalleryMediumDetailGain = if (directGalleryQualityLiftActive) {
                val base = resolvedExportProfile.mediumDetailGain.toFloat()
                val cap = maxOf(base, realtimeDetailGainCeiling)
                (base + 0.055f).coerceAtMost(cap)
            } else {
                resolvedExportProfile.mediumDetailGain.toFloat().coerceIn(0f, realtimeDetailGainCeiling)
            }
            val directGalleryStrongDetailGain = if (directGalleryQualityLiftActive) {
                val base = resolvedExportProfile.strongDetailGain.toFloat()
                val cap = maxOf(base, realtimeDetailGainCeiling)
                (base + 0.040f).coerceAtMost(cap)
            } else {
                resolvedExportProfile.strongDetailGain.toFloat().coerceIn(0f, realtimeDetailGainCeiling)
            }
            // Profile sharpening consumes part of the same halo-safe headroom as subject detail.
            // Recompute after profile + AI record tuning so the two lanes cannot overbook it.
            val finalSubjectDetailBudget = (
                (v20ClarityPlan.sharpeningCeiling - directGallerySharpening)
                    .coerceAtLeast(0f) *
                    (1f - baseSkinDetailProtection.coerceIn(0f, 1f) * 0.5f)
                ).coerceIn(0f, 1f)
            val finalSubjectDetailGain = if (normalSdrRealtimeLock) {
                v20ClarityPlan.subjectDetailGain.coerceIn(0f, finalSubjectDetailBudget)
            } else {
                v20ClarityPlan.subjectDetailGain
            }
            val uniforms = GpuImageEnhancementUniforms(
                signalFamily = signalFamily,
                colorSpace = normalized.colorSpace,
                colorScience = normalized.colorScience,
                activeTransport = transport,
                temporalBlend = v20ClarityPlan.temporalBlend,
                spatialDenoise = directGallerySpatialDenoise,
                chromaDenoise = directGalleryChromaDenoise,
                shadowBoost = if (normalSdrRealtimeLock) {
                    requireNotNull(adaptiveSdrQuality).shadowBoost
                } else {
                    profile.shadowBoost.coerceIn(0f, 1f)
                },
                // Detail profiles add intent; they must not disable the baseline
                // edge-aware luma detail lane. Focused detail still receives an
                // additional subject-mask gain through uSubjectDetail. The per-profile
                // record divergence + AI-enhancement boost are folded in via aiRecordBoost
                // (FOCUSED + AI-off == the previous v20ClarityPlan.sharpening exactly).
                sharpening = directGallerySharpening,
                warmth = if (normalSdrRealtimeLock) {
                    requireNotNull(adaptiveSdrQuality).warmth
                } else {
                    profile.warmth.coerceIn(-1f, 1f)
                },
                contrast = directGalleryContrast,
                saturation = directGallerySaturation,
                highlightCompression = if (normalSdrRealtimeLock) {
                    requireNotNull(adaptiveSdrQuality).highlightCompression
                } else {
                    profile.highlightCompression.coerceIn(0f, 1f)
                },
                logStrength = if (normalSdrRealtimeLock) 0f else profile.logStrength.coerceIn(0f, 1f),
                videoHdrFusionStrength = if (normalSdrRealtimeLock) {
                    0f
                } else {
                    profile.videoHdrFusionStrength.coerceIn(0f, 1f)
                },
                shadowChromaDeadband = resolvedExportProfile.shadowChromaDeadband / 1023f,
                aliasSafeMaxDetailDelta = resolvedExportProfile.aliasSafeMaxDetailDelta / 1023f,
                fineTextureThreshold = resolvedExportProfile.fineTextureThreshold / 1023f,
                mediumEdgeThreshold = resolvedExportProfile.mediumEdgeThreshold / 1023f,
                strongEdgeThreshold = resolvedExportProfile.strongEdgeThreshold / 1023f,
                fineTextureDetailGain = directGalleryFineTextureGain,
                mediumDetailGain = directGalleryMediumDetailGain,
                strongDetailGain = directGalleryStrongDetailGain,
                temporalLumaBlend = resolvedExportProfile.temporalLumaBlend.toFloat().coerceIn(0f, 1f),
                temporalChromaBlend = resolvedExportProfile.temporalChromaBlend.toFloat().coerceIn(0f, 1f),
                iso = iso.coerceAtLeast(0f),
                noiseMetric = if (normalSdrRealtimeLock) {
                    requireNotNull(adaptiveSdrQuality).noiseMetric
                } else {
                    maxOf(
                        noiseMetric,
                        if (strictGpuFloors) {
                            when (signalFamily) {
                                GpuSignalFamily.SDR -> STRICT_GPU_SDR_NOISE_METRIC_FLOOR
                                GpuSignalFamily.HDR,
                                GpuSignalFamily.LOG -> STRICT_GPU_NOISE_METRIC_FLOOR
                                GpuSignalFamily.RAW -> 0f
                            }
                        } else {
                            0f
                        }
                    ).coerceIn(0f, 1f)
                },
                localChromaSpikeSmoothing = directGalleryLocalChromaSmoothing,
                sharpeningFloor = v20ClarityPlan.sharpeningFloor,
                sharpeningCeiling = v20ClarityPlan.sharpeningCeiling,
                skinDetailProtection = baseSkinDetailProtection,
                migrationApplied = normalized.migrationApplied,
                dualGainFusionEnabled = profile.dualGainFusionEnabled,
                dualGainMotionMaskEnabled = profile.dualGainMotionMaskEnabled,
                dualGainKGain = profile.dualGainKGain.coerceIn(1f, 64f),
                dualGainNoiseFloor = profile.dualGainNoiseFloor.coerceIn(0f, 0.20f),
                dualGainSaturationThreshold = profile.dualGainSaturationThreshold.coerceIn(0.72f, 0.995f),
                dualGainTimestampSkew = if (profile.dualGainTimestampSkewNs == Long.MAX_VALUE) {
                    1f
                } else {
                    (profile.dualGainTimestampSkewNs.toFloat() / DualGainHdrFusionPolicy.MAX_TIMESTAMP_SKEW_NS.toFloat()).coerceIn(0f, 1f)
                },
                sensorShotNoiseCoefficient = sensorShotNoiseCoefficient.coerceIn(0f, 0.25f),
                sensorReadNoiseCoefficient = sensorReadNoiseCoefficient.coerceIn(0f, 0.10f),
                detailProfileId = selectedDetailProfileId,
                subjectMaskAvailable = if (controlledSubjectPlan?.subjectMaskPresent == true) 1f else 0f,
                subjectDetailGain = finalSubjectDetailGain,
                subjectMaskFeather = controlledSubjectPlan?.maskFeather?.coerceIn(0f, 1f) ?: 0f,
                v20ZeroGrainClarityActive = v20ClarityPlan.active,
                v20ZeroGrainLock = v20ClarityPlan.zeroGrainLock,
                v20RgbGrainQuarantine = v20ClarityPlan.rgbGrainQuarantine,
                v20MotionBlurRejection = v20ClarityPlan.motionBlurRejection,
                v20MicroDetailHonesty = v20ClarityPlan.microDetailHonesty,
                v20ZeroGrainClaritySignature = v20ClarityPlan.signature,
                aiMotionTemporalBlend = profile.aiMotionTemporalBlend.coerceIn(0f, 1f),
                signature = "",
                sensorNoiseModelPerFrame = sensorNoiseModelPerFrame
            )
            val signature = listOf(
                "gpu-v1",
                "signal=${signalFamily.name}",
                "space=${normalized.colorSpace.name}",
                "science=${normalized.colorScience.name}",
                "transport=${transport.name}",
                "zenith=ZENITH_COSMIC_PLANCK",
                "strictProfile=${BeyondImaxEliteProfile.PROFILE_NAME}",
                "strictChecksum=${BeyondImaxEliteProfile.CANONICAL_UNIFORM_CHECKSUM}",
                "baselineProfile=${BeyondImaxEliteProfile.BASELINE_PROFILE_NAME}",
                "dualGain=${if (uniforms.dualGainFusionEnabled) "PUBLIC_CAMERA2_DUAL_GAIN" else "inactive"}",
                "dualGainMotionMask=${uniforms.dualGainMotionMaskEnabled}",
                "dualGainGuardNs=${DualGainHdrFusionPolicy.MAX_TIMESTAMP_SKEW_NS}",
                "noiseModel=heteroscedastic",
                "shotNoise=${uniforms.sensorShotNoiseCoefficient.format4()}",
                "readNoise=${uniforms.sensorReadNoiseCoefficient.format4()}",
                "sensorNoisePerFrame=${uniforms.sensorNoiseModelPerFrame}",
                "rawNoiseMetric=${noiseMetric.format4()}",
                "strictGpuFloors=$strictGpuFloors",
                "normalSdrRealtimeLock=$normalSdrRealtimeLock",
                "forceNormalSdrRealtimeLock=$forceNormalSdrRealtimeLock",
                "sdrQualityIntent=${resolvedSdrQualityIntent?.name ?: "NONE"}",
                "directGalleryQualityLift=$directGalleryQualityLiftActive",
                "sdrQualityPolicy=${adaptiveSdrQuality?.signature ?: "inactive"}",
                "detailProfile=${videoDetailProfile?.name ?: "LEGACY"}",
                "subjectMask=${uniforms.subjectMaskAvailable > 0.5f}",
                "subjectDetailBudget=${finalSubjectDetailBudget.format4()}",
                "subjectDetailGain=${uniforms.subjectDetailGain.format4()}",
                "v20ZeroGrainClarity=${uniforms.v20ZeroGrainClarityActive}",
                "v20ZeroGrainLock=${uniforms.v20ZeroGrainLock.format4()}",
                "v20RgbGrainQuarantine=${uniforms.v20RgbGrainQuarantine.format4()}",
                "v20MotionBlurRejection=${uniforms.v20MotionBlurRejection.format4()}",
                "v20MicroDetailHonesty=${uniforms.v20MicroDetailHonesty.format4()}",
                "v20Signature=${uniforms.v20ZeroGrainClaritySignature}",
                "externalProfile=false",
                "vendor=false",
                "aiMotionTemporal=${uniforms.aiMotionTemporalBlend.format4()}",
                "packet=${uniforms.toFloatArray().formatPacket()}"
            ).joinToString(separator = "|")
            return uniforms.copy(signature = signature)
        }

        private fun resolveRealtimeExportProfile(
            signalFamily: GpuSignalFamily,
            colorSpaceName: String,
            colorScienceName: String,
            activeTransport: GpuActiveTransport
        ): Npv10ExportEnhancementProfile {
            val key = listOf(colorSpaceName, colorScienceName, activeTransport.name)
                .joinToString(separator = "|")
                .lowercase(Locale.US)
            return when {
                key.contains("nivukx") -> Npv10ExportEnhancementProfile.NIVUKX_VISION
                signalFamily == GpuSignalFamily.LOG || key.contains("log") ->
                    Npv10ExportEnhancementProfile.LOG_VIDEO
                key.contains("ndr10") ||
                    activeTransport == GpuActiveTransport.BT2100_PQ_MAIN10 ||
                    activeTransport == GpuActiveTransport.ANDROID_PQ_BT2020_MAIN10 ->
                    Npv10ExportEnhancementProfile.NDR10_PLUS
                signalFamily == GpuSignalFamily.SDR ||
                    activeTransport == GpuActiveTransport.REC709_SDR ||
                    activeTransport == GpuActiveTransport.BT2020_SDR ||
                    key.contains("rec709") ||
                    key.contains("rec.709") ||
                    key.contains("bt709") ||
                    key.contains("rec2020") ||
                    key.contains("rec.2020") ||
                    key.contains("bt2020_sdr") ||
                    key.contains("normal") ||
                    key.contains("natural") ->
                    Npv10ExportEnhancementProfile.SDR_VIDEO
                else -> Npv10ExportEnhancementProfile.DEFAULT
            }
        }
    }
}

private fun Float.format4(): String = String.format(Locale.US, "%.4f", this)

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

private fun FloatArray.formatPacket(): String =
    mapIndexed { index, value -> "f$index=${value.format4()}" }
        .joinToString(separator = ",")
