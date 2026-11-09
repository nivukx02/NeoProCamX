package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaPlan
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tanh

internal data class RecordingColorEncodingConfig(
    val colorStandard: Int,
    val colorTransfer: Int,
    val colorRange: Int,
    val requiresTenBit: Boolean,
    val main10Requested: Boolean
)

internal data class RecordingPreparedVideoPlan(
    val effectiveBitrate: Int,
    val colorEncoding: RecordingColorEncodingConfig,
    val useTenBit: Boolean,
    val simulatedTenBitFallback: Boolean,
    val activeBitDepthLabel: String,
    val dynamicRangeLabel: String,
    val qualityTier: RecordingCodecCatalog.QualityTier,
    val useTwelveBit: Boolean = false,
    val iFrameIntervalSeconds: Float,
    val preferredBitrateModes: List<Int>,
    val operatingRate: Int?,
    val profile: Int?,
    val level: Int?,
    val encoderQualityTelemetry: RecordingEncoderQualityTelemetry = RecordingEncoderQualityTelemetry.EMPTY,
    val sdr10Mastering: Boolean = false,
    val nLogMastering: Boolean = false,
    val allIntraMastering: Boolean = false,
    val contentAdaptiveBitrateGuard: Boolean = false,
    val qpTuningEnabled: Boolean = false,
    val p010Required: Boolean = false,
    val preferredPixelFormat: String? = null
)

internal object RecordingVideoEncoderPlanResolver {

    fun resolveColorEncoding(
        mime: String,
        videoHdrMode: String,
        videoColorSpace: String,
        logEnabled: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): RecordingColorEncodingConfig =
        RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = mime,
            videoHdrMode = videoHdrMode,
            videoColorSpace = videoColorSpace,
            logEnabled = logEnabled,
            sdkInt = sdkInt
        ).colorEncoding

    fun resolvePlan(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        requestedBitrate: Int,
        tenBitEnabled: Boolean,
        colorEncoding: RecordingColorEncodingConfig,
        hardwareTenBitSupported: Boolean,
        av1Main10Profile: Int?,
        hevcMain12Profile: Int? = null,
        sdkInt: Int = Build.VERSION.SDK_INT,
        minimumBitrateFloorFor: (Int, Int) -> Int,
        clampBitrate: (Int) -> Int,
        logEnabled: Boolean = false,
        cinemaMasteringEnabled: Boolean = false,
        manualBitrateSelectionEnabled: Boolean = false,
        computationalCinemaPlan: ComputationalCinemaPlan? = null,
        requestedDynamicRangeLabel: String? = null,
        eliteQualityTier: EliteVideoQualityTier? = null,
        sceneQualityHints: RecordingEncoderSceneQualityHints = RecordingEncoderSceneQualityHints.EMPTY,
        videoDetailProfile: VideoDetailProfile? = null,
        // s6-f02 (CP70/CP71): opt-in reach gates. Defaults reproduce today's plan bit-for-bit;
        // RecordingManager does not pass them, so every live session uses the defaults.
        av1AllIntraRequested: Boolean = false,
        twelveBitRequested: Boolean = false,
        hardwareTwelveBitSupported: Boolean = false
    ): RecordingPreparedVideoPlan {
        val sdrTransferDelivery =
            colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        val normalSdrMastering = eliteQualityTier == EliteVideoQualityTier.ELITE_NORMAL_SDR
        val sdr10Mastering = eliteQualityTier == EliteVideoQualityTier.ELITE_PRO_SDR_10BIT
        val nLogMastering = logEnabled && eliteQualityTier.isNLogTier()
        val manualRateControlledDelivery =
            manualBitrateSelectionEnabled && !cinemaMasteringEnabled
        // s6-f02 (CP70): AV1 all-intra mastering, gated to an AV1 request that does not exist today.
        val av1AllIntraMastering =
            av1AllIntraRequested && mime.equals("video/av01", ignoreCase = true)
        val allIntraMasteringRequested =
            !manualRateControlledDelivery &&
                (normalSdrMastering || sdr10Mastering || nLogMastering || av1AllIntraMastering)
        val sdrRec709Delivery =
            !logEnabled &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT709 &&
                sdrTransferDelivery &&
                !colorEncoding.requiresTenBit &&
                !colorEncoding.main10Requested
        val planRequestsMain10 =
            computationalCinemaPlan?.requestHevcMain10 == true
        val planRequestsHdr =
            computationalCinemaPlan?.requestHdrEncoding == true && !sdrTransferDelivery
        val requestedColorEncoding =
            if (normalSdrMastering) {
                colorEncoding.copy(
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    requiresTenBit = true,
                    main10Requested = true
                )
            } else if (sdr10Mastering) {
                colorEncoding.copy(
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    requiresTenBit = true,
                    main10Requested = true
                )
            } else if (nLogMastering) {
                colorEncoding.copy(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    requiresTenBit = true,
                    main10Requested = true
                )
            } else if (planRequestsMain10 || planRequestsHdr) {
                colorEncoding.copy(
                    requiresTenBit = true,
                    main10Requested = true
                )
            } else {
                colorEncoding
        }
        val sdr10PrimariesLabel =
            if (requestedColorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020) {
                "Rec.2020"
            } else {
                "Rec.709"
            }
        val resolvedRequestedDynamicRangeLabel = requestedDynamicRangeLabel?.takeIf { it.isNotBlank() } ?: when {
            normalSdrMastering -> "Normal SDR-10 ($sdr10PrimariesLabel)"
            sdr10Mastering -> "Pro SDR-10 ($sdr10PrimariesLabel)"
            nLogMastering -> "N-LOG v5 APEX-LOG"
            requestedColorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ->
                Bt2100TransportPolicy.displayLabelForHdrVersion("NDR10+")
            requestedColorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
            logEnabled && requestedColorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ->
                "LOG SDR (Rec.2020)"
            requestedColorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ->
                "Wide Gamut SDR (Rec.2020)"
            else -> "Original SDR (Rec.709)"
        }
        val tenBitRequested =
            requestedColorEncoding.requiresTenBit ||
                requestedColorEncoding.main10Requested ||
                tenBitEnabled
        val useTenBit = tenBitRequested && hardwareTenBitSupported
        // s6-f02 (CP71): HEVC Main12 reach. Honest by construction — requires a real advertised
        // Main12 profile AND a real 12-bit hardware capability; default-off (twelveBitRequested=false).
        val useTwelveBit =
            twelveBitRequested &&
                hardwareTwelveBitSupported &&
                hevcMain12Profile != null &&
                mime.equals("video/hevc", ignoreCase = true) &&
                sdkInt >= Build.VERSION_CODES.M
        val simulatedTenBitFallback =
            tenBitRequested && !hardwareTenBitSupported
        val forceComputationalCinemaTier =
            computationalCinemaPlan != null && computationalCinemaPlan.bitrateMultiplier >= 2.0f
        val forceExtremeDetailCinemaTier =
            videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL
        val qualityTier =
            if (
                normalSdrMastering ||
                    sdr10Mastering ||
                    nLogMastering ||
                    forceComputationalCinemaTier ||
                    forceExtremeDetailCinemaTier
            ) {
                RecordingCodecCatalog.QualityTier.CINEMA
            } else {
                resolveQualityTier(
                    width = width,
                    height = height,
                    fps = fps,
                    tenBitRequested = tenBitRequested,
                    colorEncoding = requestedColorEncoding,
                    logEnabled = logEnabled,
                    cinemaMasteringEnabled = cinemaMasteringEnabled
                )
            }
        val iFrameIntervalSeconds = resolveIFrameIntervalSeconds(
            qualityTier = qualityTier,
            fps = fps,
            allIntraMastering = allIntraMasteringRequested,
            manualRateControlledDelivery = manualRateControlledDelivery
        )
        val effectiveColorEncoding =
            if (requestedColorEncoding.requiresTenBit && !useTenBit) {
                requestedColorEncoding.copy(
                    // Preserve wide-gamut delivery when 10-bit is unavailable so LOG and HDR
                    // intent do not collapse all the way back to Rec.709.
                    colorStandard = if (
                        requestedColorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
                    ) {
                        MediaFormat.COLOR_STANDARD_BT2020
                    } else {
                        MediaFormat.COLOR_STANDARD_BT709
                    },
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    requiresTenBit = false,
                    main10Requested = false
                )
            } else if (useTenBit && !requestedColorEncoding.main10Requested) {
                requestedColorEncoding.copy(main10Requested = true)
            } else {
                requestedColorEncoding
            }
        val baseBitrateFloor = minimumBitrateFloorFor(width, height)
        val fpsScaledFloor = (
            baseBitrateFloor.toFloat() * (fps.coerceAtLeast(24) / 25f)
            ).roundToInt().coerceAtLeast(baseBitrateFloor)
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        // Use the fps-scaled minimum directly as the floor. The previous 8.5x
        // boost forced strict-mode recordings above the user-requested bitrate,
        // overriding the mastering boost computation in the test contract.
        val highEndFloor = fpsScaledFloor

        // Compute the effective bitrate as a single-pass float multiplication
        // and saturate before converting to Int. Max-quality requests can hit
        // the codec ceiling before strict-mode boosts are applied, so the
        // planner must never overflow before the final device clamp.
        val bitPerfectRecordingPolicy = BitPerfectRecordingPolicy()
        val normalSdrBitrateConfig =
            if (normalSdrMastering) bitPerfectRecordingPolicy.resolveNormalSdrAllIntraConfig(width, height, fps) else null
        val sdr10BitrateConfig =
            if (sdr10Mastering) bitPerfectRecordingPolicy.resolveSdr10AllIntraConfig(width, height, fps) else null
        val nLogBitrateConfig =
            if (nLogMastering) bitPerfectRecordingPolicy.resolveNLogAllIntraConfig(width, height) else null
        val masteringBitrateConfig =
            if (manualRateControlledDelivery) null else normalSdrBitrateConfig ?: sdr10BitrateConfig ?: nLogBitrateConfig
        val codecRequestCeilingBps = codecRequestCeilingBps(mime)
        var requestedEffectiveFloat = if (
            !manualRateControlledDelivery && (normalSdrMastering || sdr10Mastering || nLogMastering)
        ) {
            resolveContentAdaptiveBitrate(
                baseBitrate = requestedBitrate,
                spatialComplexity = sceneQualityHints.sceneComplexity,
                motionMagnitude = sceneQualityHints.sceneComplexity
            ).toFloat()
        } else {
            requestedBitrate.toFloat()
        }
        val baseEncoderQualityTelemetry = resolveEncoderQualityTelemetry(
            mime = mime,
            requestedColorEncoding = requestedColorEncoding,
            logEnabled = logEnabled,
            tenBitRequested = tenBitRequested,
            useTenBit = useTenBit,
            av1Main10Profile = av1Main10Profile,
            hevcMain12Profile = hevcMain12Profile,
            useTwelveBit = useTwelveBit,
            sceneQualityHints = sceneQualityHints
        )
        val sourceQualityBudget = RecordingVideoQualityBudgetPolicy.resolveBudgetPlan(
            RecordingVideoQualityBudgetRequest(
                width = width,
                height = height,
                fps = fps,
                codec = when {
                    mime.contains("avc", ignoreCase = true) -> GpuVideoCodec.AVC
                    mime.contains("av01", ignoreCase = true) ||
                        mime.contains("av1", ignoreCase = true) -> GpuVideoCodec.AV1
                    else -> GpuVideoCodec.HEVC
                },
                hdrMode = when (requestedColorEncoding.colorTransfer) {
                    MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ"
                    MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                    else -> "OFF"
                },
                logEnabled = logEnabled,
                logProfile = resolvedRequestedDynamicRangeLabel,
                tenBit = useTenBit,
                colorSpace = if (
                    requestedColorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
                ) {
                    "Rec.2020"
                } else {
                    "Rec.709"
                },
                videoDetailProfile = videoDetailProfile,
                motionRisk = sceneQualityHints.motionRisk,
                noiseRisk = sceneQualityHints.noiseRisk,
                antiMotionBlurEnabled = sceneQualityHints.antiMotionBlurEnabled,
                sourceQualityBudgetMultiplier = sceneQualityHints.sourceQualityBudgetMultiplier,
                sourceQualityTier = sceneQualityHints.sourceQualityTier
            )
        )
        val encoderQualityTelemetry = baseEncoderQualityTelemetry.copy(
            logLines = baseEncoderQualityTelemetry.logLines + (
                "V20 source quality budget " +
                    "tier=${sourceQualityBudget.sourceQualityPressureTier} " +
                    "motion=${String.format(Locale.US, "%.3f", sourceQualityBudget.motionRisk)} " +
                    "noise=${String.format(Locale.US, "%.3f", sourceQualityBudget.noiseRisk)} " +
                    "multiplier=${String.format(Locale.US, "%.3f", sourceQualityBudget.bitratePressureMultiplier)} " +
                    "floor=${sourceQualityBudget.sourceQualityPressureFloorBps} " +
                    "target=${sourceQualityBudget.targetBitrateBps}"
                )
        )
        if (!manualBitrateSelectionEnabled && !cinemaMasteringEnabled && !sdr10Mastering) {
            computationalCinemaPlan?.let { plan ->
                requestedEffectiveFloat *= plan.bitrateMultiplier
            }
            requestedEffectiveFloat *= encoderQualityTelemetry.bitrateMultiplier
            if (simulatedTenBitFallback) {
                requestedEffectiveFloat *= 1.16f
            }
            if (HighEndQualityPolicy.enabled) {
                val masteringBoost =
                    HighEndQualityPolicy.qualityBoost(recording = true, previewFps = 30f) *
                        if (strictHighEnd) 1.22f else 1.10f
                requestedEffectiveFloat *= masteringBoost
            }
        }
        var requestedEffectiveBitrate = requestedEffectiveFloat.toSafeBitrateInt()
        if (!manualBitrateSelectionEnabled && !cinemaMasteringEnabled) {
            requestedEffectiveBitrate = requestedEffectiveBitrate.coerceAtLeast(
                sourceQualityBudget.targetBitrateBps
            )
        }
        if (masteringBitrateConfig != null) {
            val masteringFloorBps = masteringBitrateConfig.minimumBitrateBps
                .coerceAtMost(codecRequestCeilingBps)
            val masteringCeilingBps = masteringBitrateConfig.maximumBitrateBps
                .coerceAtMost(codecRequestCeilingBps)
                .coerceAtLeast(masteringFloorBps)
            requestedEffectiveBitrate = requestedEffectiveBitrate
                .coerceAtLeast(masteringFloorBps.coerceToBitrateInt())
                .coerceAtMost(masteringCeilingBps.coerceToBitrateInt())
        } else if (!manualRateControlledDelivery && !cinemaMasteringEnabled) {
            requestedEffectiveBitrate = requestedEffectiveBitrate.coerceAtLeast(highEndFloor)
        }
        val effectiveBitrate = clampBitrate(requestedEffectiveBitrate)
            .let { clamped ->
                masteringBitrateConfig?.let { config ->
                    clamped.coerceAtMost(
                        config.maximumBitrateBps
                            .coerceAtMost(codecRequestCeilingBps)
                            .coerceToBitrateInt()
                    )
                } ?: clamped
        }
        val dynamicRangeLabel =
            if (normalSdrMastering && useTenBit) {
                "Normal SDR-10 ($sdr10PrimariesLabel 10-bit)"
            } else if (normalSdrMastering) {
                "Normal SDR-10 ($sdr10PrimariesLabel 10-bit unavailable)"
            } else if (sdr10Mastering && useTenBit) {
                "Pro SDR-10 ($sdr10PrimariesLabel 10-bit)"
            } else if (sdr10Mastering) {
                "Pro SDR-10 ($sdr10PrimariesLabel 10-bit unavailable)"
            } else if (nLogMastering && useTenBit) {
                "N-LOG v5 APEX-LOG (Rec.2020 P010 10-bit)"
            } else if (nLogMastering) {
                "N-LOG v5 APEX-LOG (Rec.2020 8-bit fallback)"
            } else {
                RecordingHdrVideoLookPolicy.resolveActiveDynamicRangeLabel(
                    requestedDynamicRangeLabel = resolvedRequestedDynamicRangeLabel,
                    colorEncoding = effectiveColorEncoding,
                    useTenBit = useTenBit,
                    simulatedTenBitFallback = simulatedTenBitFallback
                )
            }
        val resolvedLevel = resolveLevel(mime = mime, width = width, height = height, fps = fps, sdkInt = sdkInt)
            .let { level ->
                if (
                    sdr10Mastering &&
                        mime.equals("video/hevc", ignoreCase = true) &&
                        maxOf(width, height) >= 3840 &&
                        fps >= 60 &&
                        sdkInt >= Build.VERSION_CODES.M
                ) {
                    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61
                } else if (
                    normalSdrMastering &&
                    mime.equals("video/hevc", ignoreCase = true) &&
                    maxOf(width, height) >= 3840 &&
                    fps >= 60 &&
                    sdkInt >= Build.VERSION_CODES.M
                ) {
                    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61
                } else if (
                    nLogMastering &&
                    mime.equals("video/hevc", ignoreCase = true) &&
                    maxOf(width, height) >= 3840 &&
                    fps >= 60 &&
                    sdkInt >= Build.VERSION_CODES.M
                ) {
                    MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61
                } else {
                    level
                }
            }

        return RecordingPreparedVideoPlan(
            effectiveBitrate = effectiveBitrate,
            colorEncoding = effectiveColorEncoding,
            useTenBit = useTenBit,
            simulatedTenBitFallback = simulatedTenBitFallback,
            activeBitDepthLabel = when {
                useTwelveBit -> "Hardware 12-bit"
                useTenBit -> "Hardware 10-bit"
                simulatedTenBitFallback && (normalSdrMastering || sdr10Mastering) -> "Required 10-bit unavailable"
                simulatedTenBitFallback -> "Original 8-bit (10-bit unavailable)"
                else -> "Original 8-bit"
            },
            dynamicRangeLabel = dynamicRangeLabel,
            qualityTier = qualityTier,
            useTwelveBit = useTwelveBit,
            iFrameIntervalSeconds = iFrameIntervalSeconds,
            preferredBitrateModes = resolvePreferredBitrateModes(
                qualityTier = qualityTier,
                sdkInt = sdkInt,
                cinemaMasteringEnabled = cinemaMasteringEnabled || allIntraMasteringRequested,
                manualRateControlledDelivery = manualRateControlledDelivery
            ),
            operatingRate = resolveOperatingRate(width = width, height = height, fps = fps, sdkInt = sdkInt),
            profile = resolveProfile(
                mime = mime,
                useTenBit = useTenBit,
                main10Requested = effectiveColorEncoding.main10Requested,
                av1Main10Profile = av1Main10Profile,
                hevcMain12Profile = hevcMain12Profile,
                sdkInt = sdkInt,
                preferHevcMain12 = useTwelveBit,
                forceHevcMain10 = useTenBit && (
                    normalSdrMastering ||
                        sdr10Mastering ||
                        nLogMastering ||
                        (cinemaMasteringEnabled && tenBitRequested && !sdrRec709Delivery) ||
                        planRequestsMain10
                    )
            ),
            level = resolvedLevel,
            encoderQualityTelemetry = encoderQualityTelemetry,
            sdr10Mastering = sdr10Mastering,
            nLogMastering = nLogMastering,
            allIntraMastering = allIntraMasteringRequested,
            contentAdaptiveBitrateGuard = allIntraMasteringRequested,
            qpTuningEnabled = qualityTier == RecordingCodecCatalog.QualityTier.CINEMA ||
                normalSdrMastering ||
                sdr10Mastering ||
                allIntraMasteringRequested,
            p010Required = (normalSdrMastering || sdr10Mastering || nLogMastering) && !useTwelveBit,
            preferredPixelFormat = when {
                normalSdrMastering -> "P010"
                nLogMastering -> "P010"
                sdr10Mastering -> "P010"
                else -> null
            }
        )
    }

    fun resolveContentAdaptiveBitrate(
        baseBitrate: Int,
        spatialComplexity: Float,
        motionMagnitude: Float,
        kappa: Float = 0.85f,
        gamma: Float = 1.25f
    ): Int {
        val complexity = (
            spatialComplexity.coerceIn(0f, 1f) * 0.60f +
                motionMagnitude.coerceIn(0f, 1f) * 0.40f
            ).coerceIn(0f, 1f)
        val scale = 1.0 + kappa.coerceAtLeast(0f).toDouble() *
            tanh(gamma.coerceAtLeast(0f).toDouble() * complexity.toDouble())
        return (baseBitrate.toDouble() * scale).toSafeBitrateInt()
    }

    fun resolveMacroblockQpDelta(
        localVariance: Float,
        frameVariance: Float,
        delta: Float = 2.0f
    ): Float {
        val ratio = localVariance.coerceAtLeast(1e-6f) / frameVariance.coerceAtLeast(1e-6f)
        return -delta * (ln(ratio.toDouble()) / ln(2.0)).toFloat()
    }

    private fun resolveEncoderQualityTelemetry(
        mime: String,
        requestedColorEncoding: RecordingColorEncodingConfig,
        logEnabled: Boolean,
        tenBitRequested: Boolean,
        useTenBit: Boolean,
        av1Main10Profile: Int?,
        hevcMain12Profile: Int?,
        useTwelveBit: Boolean,
        sceneQualityHints: RecordingEncoderSceneQualityHints
    ): RecordingEncoderQualityTelemetry {
        val sceneComplexity = sceneQualityHints.sceneComplexity.coerceIn(0f, 1f)
        val sceneBoostApplied = sceneComplexity > 0.08f
        val lowLightBoostApplied = sceneQualityHints.lowLight
        val hdrOrLogDelivery =
            logEnabled ||
                requestedColorEncoding.colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        val hdrOrLogBoostApplied = sceneQualityHints.hasSceneSignal && hdrOrLogDelivery

        var multiplier = 1f
        if (sceneBoostApplied) {
            multiplier *= 1f + sceneComplexity * 0.28f
        }
        if (lowLightBoostApplied) {
            multiplier *= 1.22f
        }
        if (hdrOrLogBoostApplied) {
            multiplier *= if (logEnabled) 1.16f else 1.20f
        }
        val capabilityCheck = resolveCapabilityCheck(
            mime = mime,
            tenBitRequested = tenBitRequested,
            useTenBit = useTenBit,
            av1Main10Profile = av1Main10Profile,
            hevcMain12Profile = hevcMain12Profile,
            useTwelveBit = useTwelveBit
        )
        val thermalDowngrade =
            sceneQualityHints.thermalDowngradeActive && !HighEndQualityPolicy.isHighEndQualityActive()
        val logLines = listOf(
            "Encoder quality decision " +
                "sceneComplexity=${String.format(Locale.US, "%.3f", sceneComplexity)} " +
                "estimatedLux=${sceneQualityHints.estimatedLux?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"} " +
                "sceneComplexityBoost=$sceneBoostApplied " +
                "lowLightBoost=$lowLightBoostApplied " +
                "hdrOrLogBoost=$hdrOrLogBoostApplied " +
                "multiplier=${String.format(Locale.US, "%.3f", multiplier)} " +
                "droppedFrames=${sceneQualityHints.droppedFrames} " +
                "thermalDowngrade=$thermalDowngrade " +
                "capabilityCheck=$capabilityCheck"
        )
        return RecordingEncoderQualityTelemetry(
            sceneComplexity = sceneComplexity,
            estimatedLux = sceneQualityHints.estimatedLux,
            sceneComplexityBoostApplied = sceneBoostApplied,
            lowLightBoostApplied = lowLightBoostApplied,
            hdrOrLogBoostApplied = hdrOrLogBoostApplied,
            bitrateMultiplier = multiplier,
            capabilityCheck = capabilityCheck,
            thermalDowngradeActive = thermalDowngrade,
            droppedFrames = sceneQualityHints.droppedFrames,
            logLines = logLines
        )
    }

    private fun resolveCapabilityCheck(
        mime: String,
        tenBitRequested: Boolean,
        useTenBit: Boolean,
        av1Main10Profile: Int?,
        hevcMain12Profile: Int?,
        useTwelveBit: Boolean
    ): String =
        when {
            !tenBitRequested -> "TEN_BIT_NOT_REQUESTED"
            useTwelveBit && mime.equals("video/hevc", ignoreCase = true) && hevcMain12Profile != null ->
                "HEVC_MAIN12_SUPPORTED"
            useTenBit && mime.equals("video/av01", ignoreCase = true) && av1Main10Profile != null ->
                "AV1_MAIN10_SUPPORTED"
            useTenBit && mime.equals("video/hevc", ignoreCase = true) ->
                "HEVC_MAIN10_SUPPORTED"
            useTenBit -> "MAIN10_SUPPORTED"
            else -> "MAIN10_UNSUPPORTED_FALLBACK_TO_8BIT"
        }

    private fun resolveOperatingRate(
        width: Int,
        height: Int,
        fps: Int,
        sdkInt: Int
    ): Int? {
        if (sdkInt < Build.VERSION_CODES.M) return null
        val targetFps = fps.coerceAtLeast(1)
        val pixelCount = width.toLong().coerceAtLeast(1L) * height.toLong().coerceAtLeast(1L)
        // FLAGSHIP: Full sensor operating rate tiers
        return when {
            width >= 8192 -> maxOf(targetFps * 4, 120)  // sensor-native open gate
            width >= 7680 -> maxOf(targetFps * 4, 120)  // 8K
            pixelCount >= 4096L * 3072L -> maxOf(targetFps * 6, 180) // 4:3 4K Main10 HDR headroom
            width >= 5120 -> maxOf(targetFps * 3, 90)   // 5K
            width >= 3840 -> maxOf(targetFps * 3, 90)   // 4K
            targetFps <= 30 -> 30
            else -> maxOf(targetFps * 2, 60)
        }
    }

    private fun resolveProfile(
        mime: String,
        useTenBit: Boolean,
        main10Requested: Boolean,
        av1Main10Profile: Int?,
        hevcMain12Profile: Int?,
        sdkInt: Int,
        preferHevcMain12: Boolean,
        forceHevcMain10: Boolean
    ): Int? =
        when {
            mime == "video/hevc" && sdkInt >= Build.VERSION_CODES.LOLLIPOP -> {
                if (preferHevcMain12 && hevcMain12Profile != null) {
                    hevcMain12Profile
                } else if (
                    sdkInt >= Build.VERSION_CODES.Q &&
                    ((useTenBit && main10Requested) || forceHevcMain10)
                ) {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                } else {
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                }
            }

            mime == "video/av01" && sdkInt >= Build.VERSION_CODES.Q &&
                useTenBit && main10Requested && av1Main10Profile != null -> {
                av1Main10Profile
            }

            mime == "video/avc" && sdkInt >= Build.VERSION_CODES.LOLLIPOP -> {
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            }

            else -> null
        }

    private fun resolveLevel(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        sdkInt: Int
    ): Int? {
        if (sdkInt < Build.VERSION_CODES.M) return null
        val normalizedWidth = maxOf(width, height)
        val normalizedHeight = minOf(width, height).coerceAtLeast(1)
        val pixels = normalizedWidth.toLong() * normalizedHeight.toLong()
        val samplesPerSecond = pixels * fps.coerceAtLeast(1).toLong()
        return when {
            mime.equals("video/hevc", ignoreCase = true) -> resolveHevcLevel(pixels, samplesPerSecond)
            mime.equals("video/avc", ignoreCase = true) -> resolveAvcLevel(pixels, samplesPerSecond)
            mime.equals("video/av01", ignoreCase = true) && sdkInt >= Build.VERSION_CODES.Q ->
                resolveAv1Level(pixels, samplesPerSecond)
            else -> null
        }
    }

    private fun resolveHevcLevel(pixels: Long, samplesPerSecond: Long): Int =
        when {
            samplesPerSecond > 3840L * 2160 * 60 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61
            samplesPerSecond > 3840L * 2160 * 30 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51
            pixels >= 3840L * 2160 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5
            pixels >= 1920L * 1080 -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4
            else -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31
        }

    private fun resolveAvcLevel(pixels: Long, samplesPerSecond: Long): Int =
        when {
            samplesPerSecond > 3840L * 2160 * 60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel62
            samplesPerSecond > 3840L * 2160 * 30 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
            pixels >= 3840L * 2160 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            samplesPerSecond > 1920L * 1080 * 30 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            pixels >= 1920L * 1080 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }

    private fun resolveAv1Level(pixels: Long, samplesPerSecond: Long): Int =
        when {
            samplesPerSecond > 7680L * 4320 * 60 -> MediaCodecInfo.CodecProfileLevel.AV1Level7
            samplesPerSecond > 7680L * 4320 * 30 -> MediaCodecInfo.CodecProfileLevel.AV1Level62
            pixels >= 7680L * 4320 -> MediaCodecInfo.CodecProfileLevel.AV1Level6
            samplesPerSecond > 3840L * 2160 * 30 -> MediaCodecInfo.CodecProfileLevel.AV1Level51
            pixels >= 3840L * 2160 -> MediaCodecInfo.CodecProfileLevel.AV1Level5
            samplesPerSecond > 1920L * 1080 * 30 -> MediaCodecInfo.CodecProfileLevel.AV1Level41
            pixels >= 1920L * 1080 -> MediaCodecInfo.CodecProfileLevel.AV1Level4
            else -> MediaCodecInfo.CodecProfileLevel.AV1Level31
        }

    private fun resolveQualityTier(
        width: Int,
        height: Int,
        fps: Int,
        tenBitRequested: Boolean,
        colorEncoding: RecordingColorEncodingConfig,
        logEnabled: Boolean,
        cinemaMasteringEnabled: Boolean
    ): RecordingCodecCatalog.QualityTier {
        if (cinemaMasteringEnabled) return RecordingCodecCatalog.QualityTier.CINEMA
        val normalizedWidth = maxOf(width, height)
        val normalizedHeight = minOf(width, height).coerceAtLeast(1)
        val pixels = normalizedWidth.toLong() * normalizedHeight.toLong()
        val hdrOrWideGamut =
            colorEncoding.colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO ||
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        val cinemaSignal =
            pixels >= 5120L * 2880L ||
                (pixels >= 3840L * 2160L && fps >= 60) ||
                (fps >= 60 && (tenBitRequested || hdrOrWideGamut || logEnabled)) ||
                (tenBitRequested && hdrOrWideGamut) ||
                (logEnabled && pixels >= 3840L * 2160L)

        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val strictCinemaSignal =
            pixels >= 3840L * 2160L ||
                fps >= 48 ||
                tenBitRequested ||
                hdrOrWideGamut ||
                logEnabled

        // FLAGSHIP: Always use CINEMA tier in strict mode
        return when {
            strictHighEnd -> RecordingCodecCatalog.QualityTier.CINEMA
            HighEndQualityPolicy.enabled && cinemaSignal -> RecordingCodecCatalog.QualityTier.CINEMA
            pixels >= 3840L * 2160L || fps >= 60 || tenBitRequested || hdrOrWideGamut || logEnabled ->
                RecordingCodecCatalog.QualityTier.PROFESSIONAL
            else -> RecordingCodecCatalog.QualityTier.STANDARD
        }
    }

    private fun resolveIFrameIntervalSeconds(
        qualityTier: RecordingCodecCatalog.QualityTier,
        fps: Int,
        allIntraMastering: Boolean = false,
        manualRateControlledDelivery: Boolean = false
    ): Float {
        if (allIntraMastering) return 0f
        if (manualRateControlledDelivery) return 1f
        // Trust the catalog's tier-defined intervals (CINEMA = 0.10 for extreme
        // edit precision). Only cap them downward at high frame rates; never
        // raise them with an artificial 0.25 floor that would override the
        // catalog's intent.
        val baseInterval = RecordingCodecCatalog.iframeInterval(qualityTier)
        return when {
            fps >= 120 -> baseInterval.coerceAtMost(0.25f)
            fps >= 60 && qualityTier == RecordingCodecCatalog.QualityTier.CINEMA ->
                baseInterval.coerceAtMost(0.33f)
            else -> baseInterval
        }
    }

    private fun resolvePreferredBitrateModes(
        qualityTier: RecordingCodecCatalog.QualityTier,
        sdkInt: Int,
        cinemaMasteringEnabled: Boolean,
        manualRateControlledDelivery: Boolean = false
    ): List<Int> {
        if (sdkInt < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        if (manualRateControlledDelivery) {
            return listOf(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }
        if (cinemaMasteringEnabled) {
            return listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val cinemaModes = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        )
        val professionalModes = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        )
        val standardModes = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
        return when (qualityTier) {
            RecordingCodecCatalog.QualityTier.CINEMA -> cinemaModes
            RecordingCodecCatalog.QualityTier.PROFESSIONAL -> professionalModes
            RecordingCodecCatalog.QualityTier.STANDARD -> standardModes
        }
    }

    private fun Float.toSafeBitrateInt(): Int =
        when {
            isNaN() || this <= 0f -> 1
            this >= Int.MAX_VALUE.toFloat() -> Int.MAX_VALUE
            else -> roundToInt().coerceAtLeast(1)
        }

    private fun Double.toSafeBitrateInt(): Int =
        when {
            isNaN() || this <= 0.0 -> 1
            this >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
            else -> roundToInt().coerceAtLeast(1)
        }

    private fun Long.coerceToBitrateInt(): Int =
        coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private fun codecRequestCeilingBps(mime: String): Long =
        when (mime.lowercase(Locale.US)) {
            "video/avc", "avc", "h264", "h.264" -> GpuVideoCodec.AVC
            "video/av01", "video/av1", "av1", "av01" -> GpuVideoCodec.AV1
            else -> GpuVideoCodec.HEVC
        }.let { codec ->
            RecordingCodecCatalog.maxBitrateMbps(codec).toLong() * 1_000_000L
        }

    private fun EliteVideoQualityTier?.isNLogTier(): Boolean =
        this == EliteVideoQualityTier.ELITE_LOG_N_LOG_10BIT ||
            this == EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT ||
            this == EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT ||
            this == EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT ||
            this == EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT
}
