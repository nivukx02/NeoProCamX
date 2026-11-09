package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.camera.Camera2NoiseCleanupProfile

internal enum class VideoImageSignalFamily {
    SDR,
    HDR,
    LOG,
    RAW
}

internal enum class VideoImagePipelineStage {
    SENSOR_RAW,
    DEMOSAIC,
    AUTO_WHITE_BALANCE,
    COLOR_CORRECTION_MATRIX,
    QUALITY_EVIDENCE_GATE,
    NOISE_PRESSURE_METER,
    RGB_CHROMA_INSTABILITY_METER,
    V11_CONFIDENCE_MATRIX,
    V12_SENSOR_LIMIT_QUALITY_GATE,
    STRICT_SDR_10BIT_NOISE_LOCK,
    SENSOR_NOISE_PROFILE,
    READ_NOISE_FLOOR_ESTIMATOR,
    SENSOR_GAIN_NOISE_CLAMP,
    NOISE_REDUCTION,
    MOTION_AWARE_TEMPORAL_DENOISE,
    MOTION_COMPENSATED_MULTI_FRAME_FUSION,
    CHROMA_NOISE_SUPPRESSION,
    CHROMA_TEMPORAL_STABILIZATION,
    SHADOW_CHROMA_LOCK,
    RGB_DANCING_NOISE_LOCK,
    RGB_VARIANCE_QUARANTINE,
    LOW_LIGHT_CHROMA_FREEZE,
    SHADOW_CHROMA_DEADBAND,
    SHADOW_CHROMA_NEUTRALITY_LOCK,
    HDR_MERGE,
    TONE_MAPPING,
    EDGE_CONFIDENCE_MAP,
    SUBPIXEL_EDGE_DETECTION,
    MOTION_GHOST_GUARD,
    MOTION_SAFE_TEMPORAL_FILTER,
    MOTION_SMEAR_RISK_METER,
    TEMPORAL_STABILITY_CONFIDENCE,
    MOTION_ADAPTIVE_DETAIL_HOLD,
    QUALITY_CONFLICT_RESOLVER,
    TEMPORAL_MOTION_SMEAR_GUARD,
    TEXTURE_EDGE_CONFIDENCE_FUSION,
    STRICT_SDR_10BIT_DETAIL_RECOVERY,
    V13_EDGE_MICRO_CONTRAST_LIFT,
    FINE_TEXTURE_DETAIL_BOOST,
    HALO_SAFE_SHARPENING_LIFT,
    CHROMA_EDGE_SEPARATION_MAP,
    EDGE_AWARE_DETAIL_RECONSTRUCTION,
    NOISY_EDGE_SHARPENING_SUPPRESSOR,
    EDGE_HALO_RISK_METER,
    DETAIL_ARTIFACT_RISK_GATE,
    EDGE_HALO_SUPPRESSION,
    HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION,
    TEXTURE_PRESERVATION_GUARD,
    SKIN_TEXTURE_PRESERVATION,
    MICRO_CONTRAST_DETAIL_RESTORE,
    ADAPTIVE_DETAIL_RECONSTRUCTION,
    ALIAS_SAFE_DETAIL_RECONSTRUCTION,
    MOIRE_ALIAS_GUARD,
    RESOLUTION_HONEST_DETAIL_BOOST,
    DETAIL_CONFIDENCE_GATE,
    CLEAN_DETAIL_ESCALATOR,
    GAMMA_CONVERSION,
    LOG_TRANSFER,
    PREVIEW_RECORDING_PARITY_EVIDENCE,
    RECORDING_PREVIEW_DETAIL_PARITY,
    PERCEPTUAL_RESOLUTION_EVIDENCE,
    PERCEPTUAL_UPSCALE_TRUTH_GUARD,
    PERCEPTUAL_DETAIL_SCALER,
    ENCODER_STRESS_METER,
    ENCODER_ARTIFACT_SUPPRESSION,
    SCENE_ADAPTIVE_ENCODER_GUARD,
    EVIDENCE_CALIBRATED_OUTPUT_CLAMP,
    CODEC_DETAIL_PRESERVATION_GATE,
    FINAL_TRUTHFUL_QUALITY_AUDIT,
    SCENE_COMPLEXITY_BITRATE_GUARD,
    BLACK_LEVEL_CALIBRATION,
    RAW_METADATA,
    RAW_CONTAINER_OUTPUT,
    OUTPUT
}

internal data class VideoImageQualityPipelineRequest(
    val signalFamily: VideoImageSignalFamily,
    val recording: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val estimatedLux: Float,
    val motionScore: Float,
    val normalizedNoiseFloor: Float,
    val flatRegionStdDev: Float,
    val realIsoNoiseFloor: Float,
    val edgeDensity: Float,
    val perceptualSharpness: Float,
    val dynamicRangeScore: Float,
    val highlightRatio: Float,
    val strictHighEnd: Boolean,
    val videoDetailProfile: VideoDetailProfile? = null
)

internal data class VideoImageQualityPipelinePlan(
    val pipelineOrder: List<VideoImagePipelineStage>,
    val processedIspRequired: Boolean,
    val hdrMergeRequired: Boolean,
    val logTransferRequired: Boolean,
    val rawCalibrationRequired: Boolean,
    val cameraNoiseCleanupProfile: Camera2NoiseCleanupProfile,
    val temporalDenoiseFloor: Float,
    val temporalDenoiseCeiling: Float,
    val spatialDenoiseFloor: Float,
    val chromaDenoiseFloor: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val contrastFloor: Float,
    val contrastCeiling: Float,
    val saturationFloor: Float,
    val saturationCeiling: Float,
    val shadowBoostCeiling: Float,
    val highlightCompressionFloor: Float,
    val videoHdrFusionFloor: Float,
    val videoHdrFusionCeiling: Float,
    val logStrengthFloor: Float,
    val motionCompensatedFusionFloor: Float,
    val chromaTemporalStabilityFloor: Float,
    val perceptualSuperResolutionFloor: Float,
    val haloGuardStrength: Float,
    val edgeConfidenceMapFloor: Float,
    val texturePreservationGuard: Float,
    val adaptiveDetailReconstructionFloor: Float,
    val encoderArtifactSuppressionFloor: Float,
    val sensorNoiseProfileFloor: Float,
    val shadowChromaLockFloor: Float,
    val subpixelEdgeDetectionFloor: Float,
    val aliasSafeDetailReconstructionFloor: Float,
    val sceneComplexityBitrateGuardFloor: Float,
    val rgbDancingNoiseLockFloor: Float,
    val lowLightChromaFreezeFloor: Float,
    val motionGhostGuardFloor: Float,
    val edgeHaloSuppressionFloor: Float,
    val microContrastDetailRestoreFloor: Float,
    val resolutionHonestDetailBoostFloor: Float,
    val rgbVarianceQuarantineFloor: Float,
    val shadowChromaDeadbandFloor: Float,
    val motionSafeTemporalFloor: Float,
    val skinTexturePreservationFloor: Float,
    val moireAliasGuardFloor: Float,
    val detailConfidenceGateFloor: Float,
    val qualityConflictResolverFloor: Float,
    val noisyEdgeSharpeningSuppressorFloor: Float,
    val temporalMotionSmearGuardFloor: Float,
    val cleanDetailEscalatorFloor: Float,
    val sceneAdaptiveEncoderGuardFloor: Float,
    val qualityEvidenceGateFloor: Float,
    val noisePressureEvidenceFloor: Float,
    val rgbChromaInstabilityEvidenceFloor: Float,
    val motionSmearEvidenceFloor: Float,
    val edgeHaloRiskEvidenceFloor: Float,
    val encoderStressEvidenceFloor: Float,
    val previewRecordingParityEvidenceFloor: Float,
    val perceptualResolutionEvidenceFloor: Float,
    val cleanDetailEvidenceFloor: Float,
    val evidenceCalibratedOutputClampFloor: Float,
    val v11ConfidenceMatrixFloor: Float,
    val sensorGainNoiseClampFloor: Float,
    val temporalStabilityConfidenceFloor: Float,
    val chromaEdgeSeparationFloor: Float,
    val detailArtifactRiskGateFloor: Float,
    val perceptualUpscaleTruthGuardFloor: Float,
    val codecDetailPreservationGateFloor: Float,
    val v12SensorLimitQualityGateFloor: Float,
    val readNoiseFloorEstimatorFloor: Float,
    val shadowChromaNeutralityLockFloor: Float,
    val textureEdgeConfidenceFusionFloor: Float,
    val motionAdaptiveDetailHoldFloor: Float,
    val perceptualDetailScalerFloor: Float,
    val edgeMicroContrastLiftFloor: Float,
    val fineTextureDetailBoostFloor: Float,
    val haloSafeSharpeningLiftFloor: Float,
    val recordingPreviewDetailParityFloor: Float,
    val finalTruthfulQualityAuditFloor: Float,
    val nativeResolutionTruth: String,
    val ditherCeiling: Float
)

internal object VideoImageQualityPipelinePolicy {

    fun resolve(request: VideoImageQualityPipelineRequest): VideoImageQualityPipelinePlan {
        val signal = request.signalFamily
        val noise = noisePressure(request)
        val rgbGrain = rgbGrainPressure(request, noise)
        val motion = request.motionScore.coerceIn(0f, 1f)
        val staticness = (1f - motion).coerceIn(0f, 1f)
        val detailConfidence = detailConfidence(request)
        val lowLight = lowLightPressure(request.estimatedLux)
        val strictSdrElite = signal == VideoImageSignalFamily.SDR && request.strictHighEnd
        val strictSdrSoftDetailRecovery =
            strictSdrElite &&
                motion <= 0.55f &&
                (
                    request.perceptualSharpness < 0.46f ||
                        detailConfidence < 0.36f ||
                        request.edgeDensity.coerceIn(0f, 1f) < 0.12f
                    )
        val strictSdrNoiseRecovery =
            strictSdrElite &&
                (
                    noise >= 0.22f ||
                        rgbGrain >= 0.20f ||
                        lowLight >= 0.12f ||
                        strictSdrSoftDetailRecovery
                    )
        val cleanTexturedSignal =
            request.strictHighEnd &&
                noise <= 0.22f &&
                motion <= 0.18f &&
                detailConfidence >= 0.42f
        val strictBoost = if (request.strictHighEnd) 1f else 0f
        val recordingBoost = if (request.recording) 1f else 0f
        val longEdge = maxOf(request.width, request.height).coerceAtLeast(1)
        val lowResolutionDetailLift = lowResolutionDetailLift(longEdge)
        val highConfidenceDetailGate = if (noise <= 0.18f) {
            (
                ((detailConfidence - 0.50f) / 0.30f).coerceIn(0f, 1f) *
                    ((request.edgeDensity.coerceIn(0f, 1f) - 0.12f) / 0.20f).coerceIn(0f, 1f) *
                    ((request.perceptualSharpness.coerceIn(0f, 1f) - 0.44f) / 0.34f).coerceIn(0f, 1f) *
                    (1f - motion * 0.25f).coerceIn(0.76f, 1f)
                ).coerceIn(0f, 1f)
        } else {
            0f
        }
        val strictSdrRecoveredDetailFloor = if (strictSdrElite) {
            if (strictSdrNoiseRecovery) {
                (
                    0.52f +
                        detailConfidence * 0.18f +
                        request.edgeDensity.coerceIn(0f, 1f) * 0.12f +
                        lowResolutionDetailLift * 0.28f +
                        recordingBoost * 0.06f +
                        (if (strictSdrSoftDetailRecovery) 0.06f else 0f) -
                        noise * 0.035f -
                        motion * 0.035f
                    ).coerceIn(0.50f, if (request.recording) 0.74f else 0.68f)
            } else {
                (
                    0.46f +
                        detailConfidence * 0.14f +
                        request.edgeDensity.coerceIn(0f, 1f) * 0.08f +
                        lowResolutionDetailLift * 0.24f +
                        recordingBoost * 0.04f -
                        noise * 0.05f -
                        motion * 0.04f
                    ).coerceIn(0.42f, if (request.recording) 0.66f else 0.60f)
            }
        } else {
            0f
        }

        if (signal == VideoImageSignalFamily.RAW) {
            return rawPlan(
                request = request,
                noise = noise,
                rgbGrain = rgbGrain,
                detailConfidence = detailConfidence,
                lowLight = lowLight,
                motion = motion,
                staticness = staticness
            )
        }

        val extremeDetailProfile =
            request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL &&
                signal != VideoImageSignalFamily.RAW
        val extremeCleanDetailGate = if (extremeDetailProfile) {
            (
                ((request.edgeDensity.coerceIn(0f, 1f) - 0.18f) / 0.22f).coerceIn(0f, 1f) *
                    (1f - noise * 0.72f).coerceIn(0f, 1f) *
                    (0.45f + detailConfidence * 0.55f).coerceIn(0f, 1f)
                ).coerceIn(0f, 1f)
        } else {
            0f
        }
        val extremeNoiseCleanupGate = if (extremeDetailProfile) {
            (noise * 0.72f + rgbGrain * 0.28f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val modeChromaBias = when (signal) {
            VideoImageSignalFamily.HDR -> 0.06f
            VideoImageSignalFamily.LOG -> 0.04f
            VideoImageSignalFamily.SDR -> 0f
            VideoImageSignalFamily.RAW -> 0f
        }
        val temporalCeiling = when {
            motion >= 0.50f -> 0.54f
            motion >= 0.30f -> 0.54f
            else -> if (request.strictHighEnd) 0.78f else 0.66f
        }
        val extremeTemporalFloor = if (extremeDetailProfile) {
            (0.20f + extremeNoiseCleanupGate * 0.34f - motion * 0.16f).coerceIn(0f, 0.56f)
        } else {
            0f
        }
        val extremeSpatialFloor = if (extremeDetailProfile && extremeNoiseCleanupGate > 0.22f) {
            (0.30f + extremeNoiseCleanupGate * 0.42f).coerceIn(0f, 0.72f)
        } else {
            0f
        }
        val extremeChromaFloor = if (extremeDetailProfile && extremeNoiseCleanupGate > 0.22f) {
            (0.46f + extremeNoiseCleanupGate * 0.38f).coerceIn(0f, 0.84f)
        } else {
            0f
        }
        val extremeSharpeningFloor = if (extremeDetailProfile) {
            (0.64f + extremeCleanDetailGate * 0.22f - extremeNoiseCleanupGate * 0.18f).coerceIn(0f, 0.86f)
        } else {
            0f
        }
        val extremeEdgeMicroContrastFloor = if (extremeDetailProfile) {
            (0.50f + extremeCleanDetailGate * 0.28f - extremeNoiseCleanupGate * 0.16f).coerceIn(0f, 0.78f)
        } else {
            0f
        }
        val extremeFineTextureDetailFloor = if (extremeDetailProfile) {
            (0.50f + extremeCleanDetailGate * 0.26f - extremeNoiseCleanupGate * 0.14f).coerceIn(0f, 0.76f)
        } else {
            0f
        }
        val extremeHaloSafeFloor = if (extremeDetailProfile) {
            (0.50f + extremeCleanDetailGate * 0.28f - extremeNoiseCleanupGate * 0.20f).coerceIn(0f, 0.86f)
        } else {
            0f
        }
        val extremeRecordingPreviewParityFloor = if (extremeDetailProfile) {
            (0.62f + extremeCleanDetailGate * 0.12f - extremeNoiseCleanupGate * 0.08f).coerceIn(0f, 0.78f)
        } else {
            0f
        }
        val zeroGrainClarity = zeroGrainClarityDecision(
            request = request,
            signal = signal,
            noise = noise,
            rgbGrain = rgbGrain,
            motion = motion,
            temporalCeiling = temporalCeiling,
            strictBoost = strictBoost
        )
        val baseTemporalFloor = (
            0.18f +
                noise * 0.18f * staticness +
                rgbGrain * 0.08f * staticness +
                recordingBoost * 0.07f +
                strictBoost * 0.04f +
                if (signal == VideoImageSignalFamily.LOG) 0.02f else 0f
            ).coerceIn(0.12f, temporalCeiling)
        val temporalFloor = maxOf(
            if (strictSdrNoiseRecovery) {
                val strictSdrTemporalFloor = minOf(if (request.recording) 0.34f else 0.30f, temporalCeiling)
                maxOf(
                    baseTemporalFloor,
                    (
                        0.28f +
                            noise * 0.24f +
                            rgbGrain * 0.12f +
                            recordingBoost * 0.035f -
                            detailConfidence * 0.08f -
                            motion * 0.16f
                        ).coerceIn(strictSdrTemporalFloor, temporalCeiling)
                )
            } else {
                baseTemporalFloor
            },
            extremeTemporalFloor.coerceAtMost(temporalCeiling),
            zeroGrainClarity.temporalDenoiseFloor.coerceAtMost(temporalCeiling)
        )
        val baseSpatialFloor = (
            0.20f +
                noise * 0.36f +
                rgbGrain * 0.12f +
                lowLight * 0.08f +
                recordingBoost * 0.03f +
                strictBoost * 0.015f +
                modeChromaBias * 0.50f
            ).coerceIn(
                if (cleanTexturedSignal) 0.16f else if (request.strictHighEnd) 0.20f else 0.16f,
                if (cleanTexturedSignal) 0.48f else if (request.strictHighEnd) 0.78f else 0.72f
            )
        val spatialFloor = maxOf(
            if (strictSdrNoiseRecovery) {
                maxOf(
                    baseSpatialFloor,
                    (
                        0.30f +
                            noise * 0.34f +
                            rgbGrain * 0.14f +
                            lowLight * 0.08f +
                            recordingBoost * 0.03f -
                            detailConfidence * 0.10f
                        ).coerceIn(if (request.recording) 0.32f else 0.28f, 0.82f)
                )
            } else {
                baseSpatialFloor
            },
            extremeSpatialFloor,
            zeroGrainClarity.spatialDenoiseFloor
        )
        val baseChromaFloor = (
            0.28f +
                noise * 0.34f +
                rgbGrain * 0.20f +
                recordingBoost * 0.03f +
                strictBoost * 0.02f +
                modeChromaBias
            ).coerceIn(
                if (cleanTexturedSignal) 0.22f else if (request.strictHighEnd) 0.28f else 0.20f,
                if (cleanTexturedSignal) 0.58f else if (request.strictHighEnd) 0.88f else 0.82f
            )
        val chromaFloor = maxOf(
            if (strictSdrNoiseRecovery) {
                maxOf(
                    baseChromaFloor,
                    (
                        0.44f +
                            noise * 0.28f +
                            rgbGrain * 0.18f +
                            lowLight * 0.06f -
                            detailConfidence * 0.05f
                        ).coerceIn(0.42f, 0.92f)
                )
            } else {
                baseChromaFloor
            },
            extremeChromaFloor,
            zeroGrainClarity.chromaDenoiseFloor
        )

        val cleanDetailCeilingLift = if (request.strictHighEnd && highConfidenceDetailGate > 0f) {
            (
                detailConfidence * 0.055f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.035f +
                    staticness * 0.020f +
                    recordingBoost * 0.005f
                ).coerceIn(
                    0f,
                    when (signal) {
                        VideoImageSignalFamily.SDR -> 0.18f
                        VideoImageSignalFamily.HDR -> 0.08f
                        VideoImageSignalFamily.LOG -> 0.12f
                        VideoImageSignalFamily.RAW -> 0f
                    }
                )
                .let { it * highConfidenceDetailGate }
        } else {
            0f
        }
        val eliteSdrTextureGate = if (signal == VideoImageSignalFamily.SDR && request.strictHighEnd) {
            (
                ((request.perceptualSharpness.coerceIn(0f, 1f) - 0.84f) / 0.04f).coerceIn(0f, 1f) *
                    ((request.edgeDensity.coerceIn(0f, 1f) - 0.38f) / 0.02f).coerceIn(0f, 1f) *
                    ((detailConfidence - 0.88f) / 0.04f).coerceIn(0f, 1f) *
                    highConfidenceDetailGate
                ).coerceIn(0f, 1f)
        } else {
            0f
        }
        val baseSharpeningCeiling = when (signal) {
            VideoImageSignalFamily.HDR -> (
                0.58f - noise * 0.08f - rgbGrain * 0.04f - lowLight * 0.02f - recordingBoost * 0.01f +
                    cleanDetailCeilingLift
                ).coerceIn(if (request.strictHighEnd) (if (noise > 0.65f) 0.28f else 0.58f) else 0.28f, if (request.strictHighEnd) (if (noise > 0.65f) 0.48f else 0.92f) else (if (cleanDetailCeilingLift > 0f) 0.66f else 0.58f))
            VideoImageSignalFamily.LOG -> when {
                request.recording -> (
                    0.54f - noise * 0.04f - rgbGrain * 0.02f +
                        cleanDetailCeilingLift
                    ).coerceIn(if (request.strictHighEnd) (if (noise > 0.65f) 0.18f else 0.48f) else 0.24f, if (request.strictHighEnd) (if (noise > 0.65f) 0.34f else 0.88f) else (if (cleanDetailCeilingLift > 0f) 0.64f else 0.54f))
                request.strictHighEnd -> (
                    0.50f - noise * 0.04f - rgbGrain * 0.02f +
                        cleanDetailCeilingLift
                    ).coerceIn(if (noise > 0.65f) 0.18f else 0.48f, if (noise > 0.65f) 0.34f else 0.88f)
                else -> 0.18f
            }
            VideoImageSignalFamily.SDR -> (
                0.70f - noise * 0.06f - rgbGrain * 0.02f - lowLight * 0.008f +
                    cleanDetailCeilingLift +
                    eliteSdrTextureGate * 0.095f +
                    recordingBoost * 0.015f * eliteSdrTextureGate
                ).coerceIn(
                    if (request.strictHighEnd) (if (noise > 0.65f) 0.30f else 0.58f) else 0.30f,
                    if (request.strictHighEnd) (if (noise > 0.65f) 0.48f else 0.92f) else when {
                        noise >= 0.65f -> 0.42f
                        eliteSdrTextureGate > 0.75f && request.recording -> 0.92f
                        eliteSdrTextureGate > 0.75f -> 0.88f
                        cleanDetailCeilingLift > 0f -> 0.82f
                        else -> 0.74f
                    }
            )
            VideoImageSignalFamily.RAW -> 0f
        }
        val sharpeningCeiling = minOf(baseSharpeningCeiling, zeroGrainClarity.sharpeningCeilingCap)
        val strictCleanDetailLift = if (request.strictHighEnd && request.recording && noise < 0.24f) {
            (
                detailConfidence * 0.06f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.03f +
                    (1f - noise).coerceIn(0f, 1f) * 0.015f
                ).coerceIn(0f, 0.09f)
        } else {
            0f
        }
        val strictSdrCleanDetailLift = if (
            signal == VideoImageSignalFamily.SDR &&
            request.strictHighEnd &&
            highConfidenceDetailGate > 0f &&
            noise < 0.18f
        ) {
            (
                detailConfidence * 0.12f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.07f +
                    (1f - noise).coerceIn(0f, 1f) * 0.045f +
                    staticness * 0.025f +
                    recordingBoost * 0.035f
                ).coerceIn(0f, if (request.recording) 0.18f else 0.185f)
                .let { it * highConfidenceDetailGate }
        } else {
            0f
        }
        val requestedSharpeningFloor = if (request.strictHighEnd) {
            if (signal == VideoImageSignalFamily.LOG) {
                if (noise > 0.65f) 0.18f else 0.48f
            } else {
                if (noise > 0.65f) 0.28f else 0.58f
            }
        } else {
            if (signal == VideoImageSignalFamily.LOG && !request.recording) {
                0.14f
            } else if (signal == VideoImageSignalFamily.LOG) {
                0.18f
            } else {
                0.22f
            }
        }
        val sharpeningFloor = maxOf(
            (
                0.24f +
                    detailConfidence * 0.30f +
                    strictCleanDetailLift +
                    strictSdrCleanDetailLift +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.10f -
                    noise * 0.03f -
                    motion * 0.012f
                ).coerceIn(
                    minOf(requestedSharpeningFloor, sharpeningCeiling),
                    sharpeningCeiling
                ),
            extremeSharpeningFloor.coerceAtMost(sharpeningCeiling)
        )
        val perceptualSuperResolutionFloor = maxOf(
            (
                0.12f +
                    detailConfidence * 0.18f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.08f -
                    noise * 0.06f -
                    motion * 0.03f +
                    strictBoost * 0.05f
                ).coerceIn(0.06f, if (signal == VideoImageSignalFamily.LOG) 0.30f else 0.42f),
            strictSdrRecoveredDetailFloor * 0.74f
        )
        val edgeConfidenceMapFloor = (
            0.16f +
                detailConfidence * 0.22f +
                request.edgeDensity.coerceIn(0f, 1f) * 0.12f -
                noise * 0.03f +
                strictBoost * 0.04f
            ).coerceIn(0.12f, 0.52f)
        val texturePreservationGuard = (
            0.70f +
                noise * 0.13f +
                rgbGrain * 0.06f +
                strictBoost * 0.06f -
                detailConfidence * 0.03f
            ).coerceIn(0.66f, 0.96f)
        val adaptiveDetailReconstructionFloor = maxOf(
            perceptualSuperResolutionFloor,
            strictSdrRecoveredDetailFloor,
            (
                0.18f +
                    detailConfidence * 0.18f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.12f -
                    noise * 0.035f -
                    motion * 0.02f +
                    strictBoost * 0.07f +
                    lowResolutionDetailLift * 0.32f
                ).coerceIn(0.08f, if (signal == VideoImageSignalFamily.LOG) 0.38f else 0.52f)
        )
        val encoderArtifactSuppressionFloor = (
            0.22f +
                recordingBoost * 0.08f +
                strictBoost * 0.07f +
                (if (request.fps >= 60) 0.04f else 0f) +
                (if (longEdge <= 2048) 0.06f else 0f) +
                noise * 0.04f
            ).coerceIn(0.20f, 0.56f)
        val sensorNoiseProfileFloor = (
            0.42f +
                noise * 0.30f +
                rgbGrain * 0.14f +
                lowLight * 0.06f +
                strictBoost * 0.05f
            ).coerceIn(0.34f, 0.98f)
        val shadowChromaLockFloor = (
            chromaTemporalLockBase(chromaFloor, rgbGrain, lowLight) +
                strictBoost * 0.035f +
                if (signal == VideoImageSignalFamily.LOG) 0.015f else 0f
            ).coerceIn(chromaFloor, maxOf(chromaFloor, 0.99995f))
        val subpixelEdgeDetectionFloor = (
            0.14f +
                detailConfidence * 0.22f +
                request.edgeDensity.coerceIn(0f, 1f) * 0.14f -
                noise * 0.045f +
                strictBoost * 0.05f
            ).coerceIn(0.12f, 0.54f)
        val aliasSafeDetailReconstructionFloor = maxOf(
            adaptiveDetailReconstructionFloor,
            strictSdrRecoveredDetailFloor,
            (
                0.20f +
                    detailConfidence * 0.18f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.10f -
                    noise * 0.02f -
                    motion * 0.025f +
                    strictBoost * 0.08f +
                    lowResolutionDetailLift * 0.30f
                ).coerceIn(0.12f, if (signal == VideoImageSignalFamily.LOG) 0.42f else 0.56f)
        )
        val sceneComplexityBitrateGuardFloor = (
            0.28f +
                recordingBoost * 0.08f +
                strictBoost * 0.08f +
                request.edgeDensity.coerceIn(0f, 1f) * 0.08f +
                request.dynamicRangeScore.coerceIn(0f, 1f) * 0.05f +
                (if (request.fps >= 60) 0.04f else 0f) +
                (if (longEdge <= 2048) 0.04f else 0f)
            ).coerceIn(0.24f, 0.64f)
        val rgbDancingNoiseLockFloor = (
            chromaFloor +
                rgbGrain * 0.07f +
                noise * 0.03f +
                lowLight * 0.025f +
                strictBoost * 0.025f
            ).coerceIn(
                chromaFloor,
                maxOf(chromaFloor, if (request.strictHighEnd) 0.99998f else 0.94f)
            )
        val rgbVarianceQuarantineFloor = (
            rgbDancingNoiseLockFloor +
                rgbGrain * 0.035f +
                noise * 0.018f +
                lowLight * 0.018f +
                strictBoost * 0.012f
            ).coerceIn(
                rgbDancingNoiseLockFloor,
                maxOf(rgbDancingNoiseLockFloor, if (request.strictHighEnd) 0.99999f else 0.96f)
            )
        val lowLightChromaFreezeFloor = (
            maxOf(shadowChromaLockFloor, rgbDancingNoiseLockFloor) +
                lowLight * 0.04f +
                noise * 0.015f +
                strictBoost * 0.015f
            ).coerceIn(
                chromaFloor,
                maxOf(chromaFloor, if (request.strictHighEnd) 0.99998f else 0.94f)
            )
        val shadowChromaDeadbandFloor = (
            maxOf(lowLightChromaFreezeFloor, rgbVarianceQuarantineFloor) +
                lowLight * 0.025f +
                noise * 0.012f +
                strictBoost * 0.010f
            ).coerceIn(
                chromaFloor,
                maxOf(chromaFloor, if (request.strictHighEnd) 0.99999f else 0.96f)
            )
        val motionGhostGuardFloor = (
            0.46f +
                motion * 0.24f +
                noise * 0.18f +
                rgbGrain * 0.06f +
                recordingBoost * 0.04f +
                strictBoost * 0.08f
            ).coerceIn(0.32f, 0.92f)
        val motionSafeTemporalCeiling = when {
            motion >= 0.30f -> minOf(temporalCeiling, 0.54f)
            motion >= 0.18f -> minOf(temporalCeiling, 0.62f)
            else -> temporalCeiling
        }
        val motionSafeTemporalFloor = maxOf(
            temporalFloor,
            (
                0.30f +
                    noise * 0.24f +
                    rgbGrain * 0.08f +
                    recordingBoost * 0.06f +
                    strictBoost * 0.08f -
                    motion * 0.04f
                ).coerceIn(0.16f, motionSafeTemporalCeiling)
        )
        val edgeHaloSuppressionFloor = (
            haloGuardStrength(noise, rgbGrain, strictBoost) +
                noise * 0.08f +
                rgbGrain * 0.04f +
                lowLight * 0.03f +
                strictBoost * 0.04f +
                zeroGrainClarity.noiseSuppressionGate * 0.04f
            ).coerceIn(0.68f, 0.98f)
        val skinTexturePreservationFloor = (
            texturePreservationGuard +
                0.07f +
                strictBoost * 0.04f -
                noise * 0.02f -
                motion * 0.01f
            ).coerceIn(0.74f, 0.98f)
        val microContrastDetailRestoreFloor = maxOf(
            (
                0.18f +
                    detailConfidence * 0.18f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.10f -
                    noise * 0.04f -
                    motion * 0.02f +
                    strictBoost * 0.07f
                ).coerceIn(0.08f, if (signal == VideoImageSignalFamily.LOG) 0.40f else 0.54f),
            strictSdrRecoveredDetailFloor * 0.88f
        )
        val resolutionHonestDetailBoostFloor = maxOf(
            adaptiveDetailReconstructionFloor,
            strictSdrRecoveredDetailFloor,
            (
                0.20f +
                    detailConfidence * 0.20f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.12f -
                    noise * 0.025f -
                    motion * 0.015f +
                strictBoost * 0.08f +
                lowResolutionDetailLift
            ).coerceIn(0.12f, if (signal == VideoImageSignalFamily.LOG) 0.48f else 0.66f)
        )
        val moireAliasGuardFloor = (
            aliasSafeDetailReconstructionFloor +
                edgeHaloSuppressionFloor * 0.45f +
                texturePreservationGuard * 0.15f +
                strictBoost * 0.05f -
                motion * 0.02f
            ).coerceIn(0.60f, 0.98f)
        val detailConfidenceGateFloor = maxOf(
            (
                0.16f +
                    detailConfidence * 0.24f +
                    request.edgeDensity.coerceIn(0f, 1f) * 0.12f -
                    noise * 0.04f -
                    motion * 0.015f +
                    strictBoost * 0.08f +
                    lowResolutionDetailLift * 0.08f
                ).coerceIn(0.16f, if (signal == VideoImageSignalFamily.LOG) 0.50f else 0.62f),
            strictSdrRecoveredDetailFloor * 0.90f
        )
        val qualityConflictResolverFloor = (
            noise * 0.44f +
                rgbGrain * 0.30f +
                motion * 0.12f +
                edgeHaloSuppressionFloor * 0.10f +
                strictBoost * 0.10f +
                zeroGrainClarity.noiseSuppressionGate * 0.06f
            ).coerceIn(0.18f, 0.98f)
        val noisyEdgeSharpeningSuppressorFloor = (
            qualityConflictResolverFloor +
                noise * 0.08f +
                rgbGrain * 0.05f +
                lowLight * 0.03f +
                zeroGrainClarity.noiseSuppressionGate * 0.04f
            ).coerceIn(qualityConflictResolverFloor, 0.99f)
        val temporalMotionSmearGuardFloor = (
            0.42f +
                motion * 0.58f +
                noise * 0.08f +
                strictBoost * 0.04f
            ).coerceIn(0.30f, 0.99f)
        val cleanDetailEscalatorFloor = maxOf(
            (
                detailConfidenceGateFloor +
                    detailConfidence * 0.10f +
                    (1f - noise).coerceIn(0f, 1f) * 0.08f -
                    motion * 0.02f +
                    strictBoost * 0.03f
                ).coerceIn(0.12f, if (signal == VideoImageSignalFamily.LOG) 0.56f else 0.68f),
            strictSdrRecoveredDetailFloor * 0.94f
        )
        val sceneAdaptiveEncoderGuardFloor = maxOf(
            sceneComplexityBitrateGuardFloor,
            (
                0.34f +
                    noise * 0.12f +
                    motion * 0.08f +
                    request.dynamicRangeScore.coerceIn(0f, 1f) * 0.08f +
                    (if (signal == VideoImageSignalFamily.LOG) 0.04f else 0f) +
                    (if (signal == VideoImageSignalFamily.HDR) 0.05f else 0f) +
                    (if (request.fps >= 60) 0.04f else 0f) +
                    (if (longEdge <= 2048) 0.04f else 0f) +
                    strictBoost * 0.04f
                ).coerceIn(0.30f, 0.78f)
        )
        val noisePressureEvidenceFloor = (
            noise * 0.70f +
                rgbGrain * 0.18f +
                lowLight * 0.08f +
                strictBoost * 0.04f
            ).coerceIn(0.08f, 0.995f)
        val rgbChromaInstabilityEvidenceFloor = maxOf(
            rgbVarianceQuarantineFloor,
            (
                rgbGrain * 0.62f +
                    noise * 0.25f +
                    lowLight * 0.08f +
                    strictBoost * 0.05f
                ).coerceIn(0.08f, 0.99999f)
        )
        val motionSmearEvidenceFloor = (
            0.30f +
                motion * 0.76f +
                temporalMotionSmearGuardFloor * 0.14f +
                strictBoost * 0.03f
            ).coerceIn(0.18f, 0.99f)
        val edgeHaloRiskEvidenceFloor = maxOf(
            edgeHaloSuppressionFloor,
            (
                noisyEdgeSharpeningSuppressorFloor * 0.55f +
                    moireAliasGuardFloor * 0.28f +
                    noise * 0.10f +
                    strictBoost * 0.04f
                ).coerceIn(0.20f, 0.99f)
        )
        val encoderStressEvidenceFloor = maxOf(
            sceneAdaptiveEncoderGuardFloor,
            (
                0.32f +
                    noise * 0.12f +
                    motion * 0.10f +
                    request.dynamicRangeScore.coerceIn(0f, 1f) * 0.10f +
                    (if (request.fps >= 60) 0.06f else 0f) +
                    (if (signal == VideoImageSignalFamily.HDR || signal == VideoImageSignalFamily.LOG) 0.05f else 0f) +
                    strictBoost * 0.04f
                ).coerceIn(0.24f, 0.82f)
        )
        val previewRecordingParityEvidenceFloor = (
            0.72f +
                recordingBoost * 0.10f +
                strictBoost * 0.08f +
                if (signal == VideoImageSignalFamily.LOG) 0.03f else 0.04f
            ).coerceIn(0.70f, 0.98f)
        val perceptualResolutionEvidenceFloor = (
            resolutionHonestDetailBoostFloor +
                lowResolutionDetailLift * 0.55f +
                detailConfidence * 0.05f
            ).coerceIn(0.10f, if (signal == VideoImageSignalFamily.LOG) 0.60f else 0.74f)
        val cleanDetailEvidenceFloor = maxOf(
            (
                cleanDetailEscalatorFloor * 0.62f +
                    detailConfidence * 0.18f +
                    (1f - noise).coerceIn(0f, 1f) * 0.10f +
                    strictBoost * 0.04f -
                    motion * 0.04f
                ).coerceIn(0.10f, if (signal == VideoImageSignalFamily.LOG) 0.62f else 0.74f),
            strictSdrRecoveredDetailFloor * 0.86f
        )
        val qualityEvidenceGateFloor = maxOf(
            noisePressureEvidenceFloor,
            rgbChromaInstabilityEvidenceFloor,
            motionSmearEvidenceFloor * 0.92f,
            edgeHaloRiskEvidenceFloor,
            encoderStressEvidenceFloor
        ).coerceIn(0.20f, 0.99f)
        val evidenceCalibratedOutputClampFloor = maxOf(
            qualityEvidenceGateFloor,
            previewRecordingParityEvidenceFloor,
            if (noise > 0.50f) {
                noisyEdgeSharpeningSuppressorFloor
            } else {
                cleanDetailEvidenceFloor * 0.74f
            }
        ).coerceIn(0.20f, 0.99f)
        val sensorGainNoiseClampFloor = (
            sensorNoiseProfileFloor * 0.38f +
                noise * 0.34f +
                rgbGrain * 0.19f +
                lowLight * 0.08f +
                strictBoost * 0.04f
            ).coerceIn(0.18f, 0.995f)
        val temporalStabilityConfidenceFloor = (
            motionSmearEvidenceFloor * 0.62f +
                motion * 0.28f +
                motionGhostGuardFloor * 0.08f +
                strictBoost * 0.04f
            ).coerceIn(0.18f, 0.98f)
        val chromaEdgeSeparationFloor = (
            edgeConfidenceMapFloor * 0.36f +
                subpixelEdgeDetectionFloor * 0.24f +
                detailConfidenceGateFloor * 0.20f +
                cleanDetailEvidenceFloor * 0.16f +
                (1f - noise).coerceIn(0f, 1f) * 0.10f +
                strictBoost * 0.04f -
                rgbGrain * 0.06f
            ).coerceIn(0.10f, if (signal == VideoImageSignalFamily.LOG) 0.62f else 0.74f)
        val detailArtifactRiskGateFloor = maxOf(
            noisyEdgeSharpeningSuppressorFloor,
            (
                qualityEvidenceGateFloor * 0.42f +
                    edgeHaloRiskEvidenceFloor * 0.24f +
                    moireAliasGuardFloor * 0.14f +
                    noise * 0.12f +
                    (1f - chromaEdgeSeparationFloor).coerceIn(0f, 1f) * 0.08f +
                    strictBoost * 0.04f
                ).coerceIn(0.18f, 0.99f)
        )
        val perceptualUpscaleTruthGuardFloor = (
            perceptualResolutionEvidenceFloor * 0.62f +
                lowResolutionDetailLift * 0.70f +
                resolutionHonestDetailBoostFloor * 0.18f +
                strictBoost * 0.04f
            ).coerceIn(0.12f, if (signal == VideoImageSignalFamily.LOG) 0.70f else 0.82f)
        val codecDetailPreservationGateFloor = maxOf(
            encoderStressEvidenceFloor,
            (
                sceneAdaptiveEncoderGuardFloor * 0.58f +
                    cleanDetailEvidenceFloor * 0.24f +
                    detailConfidenceGateFloor * 0.16f +
                    (if (request.fps >= 60) 0.05f else 0f) +
                    (if (longEdge <= 2048) 0.05f else 0f) +
                    strictBoost * 0.06f
                ).coerceIn(0.20f, 0.84f)
        )
        val v11ConfidenceMatrixFloor = maxOf(
            qualityEvidenceGateFloor,
            sensorGainNoiseClampFloor,
            temporalStabilityConfidenceFloor * 0.94f,
            detailArtifactRiskGateFloor,
            perceptualUpscaleTruthGuardFloor * 0.86f,
            codecDetailPreservationGateFloor
        ).coerceIn(0.20f, 0.995f)
        val readNoiseFloorEstimatorFloor = (
            sensorGainNoiseClampFloor * 0.42f +
                noise * 0.34f +
                rgbGrain * 0.16f +
                lowLight * 0.08f +
                strictBoost * 0.04f
            ).coerceIn(0.18f, 0.995f)
        val shadowChromaNeutralityLockFloor = maxOf(
            shadowChromaDeadbandFloor,
            (
                shadowChromaDeadbandFloor * 0.58f +
                    rgbChromaInstabilityEvidenceFloor * 0.22f +
                    readNoiseFloorEstimatorFloor * 0.14f +
                    lowLight * 0.06f +
                    strictBoost * 0.03f
                ).coerceIn(0.20f, 0.99999f)
        )
        val textureEdgeConfidenceFusionFloor = maxOf(
            (
                chromaEdgeSeparationFloor * 0.36f +
                    detailConfidenceGateFloor * 0.22f +
                    cleanDetailEvidenceFloor * 0.18f +
                    texturePreservationGuard * 0.14f +
                    (1f - noise).coerceIn(0f, 1f) * 0.08f +
                    strictBoost * 0.04f -
                    detailArtifactRiskGateFloor * 0.06f
                ).coerceIn(0.10f, if (signal == VideoImageSignalFamily.LOG) 0.64f else 0.78f),
            strictSdrRecoveredDetailFloor * 0.88f
        )
        val motionAdaptiveDetailHoldFloor = (
            temporalStabilityConfidenceFloor * 0.52f +
                motionSmearEvidenceFloor * 0.26f +
                motion * 0.22f +
                strictBoost * 0.04f
            ).coerceIn(0.18f, 0.98f)
        val perceptualDetailScalerFloor = maxOf(
            (
                perceptualUpscaleTruthGuardFloor * 0.44f +
                    textureEdgeConfidenceFusionFloor * 0.26f +
                    resolutionHonestDetailBoostFloor * 0.18f +
                    cleanDetailEvidenceFloor * 0.12f +
                    detailConfidence * 0.04f +
                    lowResolutionDetailLift * 0.62f +
                    strictBoost * 0.04f
                ).coerceIn(0.12f, if (signal == VideoImageSignalFamily.LOG) 0.72f else 0.84f),
            strictSdrRecoveredDetailFloor * 0.92f
        )
        val zeroGrainMicroDetailGate = zeroGrainProtectedMicroDetailGate(
            request = request,
            noise = noise,
            rgbGrain = rgbGrain,
            motion = motion,
            detailConfidence = detailConfidence,
            highConfidenceDetailGate = highConfidenceDetailGate,
            detailArtifactRiskGateFloor = detailArtifactRiskGateFloor
        )
        val edgeMicroContrastLiftFloor = maxOf(
            ((
                textureEdgeConfidenceFusionFloor * 0.35f +
                    cleanDetailEvidenceFloor * 0.26f +
                    chromaEdgeSeparationFloor * 0.20f +
                    detailConfidence * 0.14f +
                    zeroGrainMicroDetailGate * 0.08f +
                    strictBoost * 0.05f -
                    detailArtifactRiskGateFloor * 0.10f -
                    noise * 0.10f
                ) * highConfidenceDetailGate).coerceIn(0.05f, if (signal == VideoImageSignalFamily.LOG) 0.62f else 0.78f),
            extremeEdgeMicroContrastFloor
        )
        val fineTextureDetailBoostFloor = maxOf(
            ((
                cleanDetailEvidenceFloor * 0.30f +
                    textureEdgeConfidenceFusionFloor * 0.28f +
                    skinTexturePreservationFloor * 0.16f +
                    detailConfidenceGateFloor * 0.14f +
                    zeroGrainMicroDetailGate * 0.07f +
                    strictBoost * 0.05f -
                    detailArtifactRiskGateFloor * 0.08f -
                    noise * 0.08f -
                    motion * 0.03f +
                    highConfidenceDetailGate * 0.025f
                ) * highConfidenceDetailGate).coerceIn(0.05f, if (signal == VideoImageSignalFamily.LOG) 0.60f else 0.76f),
            extremeFineTextureDetailFloor
        )
        val haloSafeSharpeningLiftFloor = maxOf(
            ((
                edgeMicroContrastLiftFloor * 0.42f +
                    fineTextureDetailBoostFloor * 0.28f +
                    perceptualDetailScalerFloor * 0.22f +
                    zeroGrainMicroDetailGate * 0.05f +
                    strictBoost * 0.04f -
                    noise * 0.06f -
                    detailArtifactRiskGateFloor * 0.08f
                ) * (0.20f + highConfidenceDetailGate * 0.80f)).coerceIn(0.05f, if (signal == VideoImageSignalFamily.LOG) 0.50f else 0.68f),
            extremeHaloSafeFloor
        )
        val recordingPreviewDetailParityFloor = maxOf(
            (
                haloSafeSharpeningLiftFloor * 0.55f +
                    previewRecordingParityEvidenceFloor * 0.12f +
                    cleanDetailEvidenceFloor * 0.20f +
                    textureEdgeConfidenceFusionFloor * 0.13f +
                    strictSdrCleanDetailLift * 0.18f
                ).coerceIn(0.10f, 0.78f),
            if (strictSdrElite) {
                (
                    0.62f +
                        recordingBoost * 0.04f -
                        noise * 0.06f -
                        motion * 0.04f
                    ).coerceIn(0.54f, 0.74f)
            } else {
                0f
            },
            extremeRecordingPreviewParityFloor
        )
        val finalTruthfulQualityAuditFloor = maxOf(
            v11ConfidenceMatrixFloor,
            readNoiseFloorEstimatorFloor,
            shadowChromaNeutralityLockFloor,
            motionAdaptiveDetailHoldFloor * 0.92f,
            detailArtifactRiskGateFloor,
            codecDetailPreservationGateFloor,
            perceptualDetailScalerFloor * 0.82f,
            haloSafeSharpeningLiftFloor
        ).coerceIn(0.20f, 0.995f)
        val v12SensorLimitQualityGateFloor = maxOf(
            finalTruthfulQualityAuditFloor,
            readNoiseFloorEstimatorFloor,
            shadowChromaNeutralityLockFloor,
            sensorGainNoiseClampFloor
        ).coerceIn(0.20f, 0.995f)

        return VideoImageQualityPipelinePlan(
            pipelineOrder = pipelineOrder(signal),
            processedIspRequired = true,
            hdrMergeRequired = signal == VideoImageSignalFamily.HDR,
            logTransferRequired = signal == VideoImageSignalFamily.LOG,
            rawCalibrationRequired = false,
            cameraNoiseCleanupProfile = Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT,
            temporalDenoiseFloor = temporalFloor,
            temporalDenoiseCeiling = temporalCeiling,
            spatialDenoiseFloor = spatialFloor,
            chromaDenoiseFloor = chromaFloor,
            sharpeningFloor = sharpeningFloor,
            sharpeningCeiling = sharpeningCeiling,
            contrastFloor = contrastFloor(signal, request),
            contrastCeiling = contrastCeiling(signal),
            saturationFloor = saturationFloor(signal),
            saturationCeiling = saturationCeiling(signal),
            shadowBoostCeiling = shadowBoostCeiling(signal, lowLight),
            highlightCompressionFloor = highlightCompressionFloor(signal, request, noise),
            videoHdrFusionFloor = if (signal == VideoImageSignalFamily.HDR) {
                (0.58f + request.dynamicRangeScore.coerceIn(0f, 1f) * 0.16f).coerceIn(0.58f, 0.82f)
            } else {
                0f
            },
            videoHdrFusionCeiling = if (signal == VideoImageSignalFamily.HDR) 1f else 0f,
            logStrengthFloor = if (signal == VideoImageSignalFamily.LOG) {
                if (request.strictHighEnd) 0.88f else 0.68f
            } else {
                0f
            },
            motionCompensatedFusionFloor = (
                0.22f +
                    noise * 0.22f * staticness +
                    rgbGrain * 0.10f * staticness +
                    recordingBoost * 0.06f +
                    strictBoost * 0.08f
                ).coerceIn(0.18f, if (motion > 0.48f) 0.42f else 0.72f),
            chromaTemporalStabilityFloor = (
                chromaFloor + 0.035f + rgbGrain * 0.06f + strictBoost * 0.02f
                ).coerceIn(
                    chromaFloor,
                    maxOf(chromaFloor, if (request.strictHighEnd) 0.99998f else 0.92f)
                ),
            perceptualSuperResolutionFloor = perceptualSuperResolutionFloor,
            haloGuardStrength = (
                0.62f + noise * 0.16f + rgbGrain * 0.08f + strictBoost * 0.05f
                ).coerceIn(0.60f, 0.92f),
            edgeConfidenceMapFloor = edgeConfidenceMapFloor,
            texturePreservationGuard = texturePreservationGuard,
            adaptiveDetailReconstructionFloor = adaptiveDetailReconstructionFloor,
            encoderArtifactSuppressionFloor = encoderArtifactSuppressionFloor,
            sensorNoiseProfileFloor = sensorNoiseProfileFloor,
            shadowChromaLockFloor = shadowChromaLockFloor,
            subpixelEdgeDetectionFloor = subpixelEdgeDetectionFloor,
            aliasSafeDetailReconstructionFloor = aliasSafeDetailReconstructionFloor,
            sceneComplexityBitrateGuardFloor = sceneComplexityBitrateGuardFloor,
            rgbDancingNoiseLockFloor = rgbDancingNoiseLockFloor,
            lowLightChromaFreezeFloor = lowLightChromaFreezeFloor,
            motionGhostGuardFloor = motionGhostGuardFloor,
            edgeHaloSuppressionFloor = edgeHaloSuppressionFloor,
            microContrastDetailRestoreFloor = microContrastDetailRestoreFloor,
            resolutionHonestDetailBoostFloor = resolutionHonestDetailBoostFloor,
            rgbVarianceQuarantineFloor = rgbVarianceQuarantineFloor,
            shadowChromaDeadbandFloor = shadowChromaDeadbandFloor,
            motionSafeTemporalFloor = motionSafeTemporalFloor,
            skinTexturePreservationFloor = skinTexturePreservationFloor,
            moireAliasGuardFloor = moireAliasGuardFloor,
            detailConfidenceGateFloor = detailConfidenceGateFloor,
            qualityConflictResolverFloor = qualityConflictResolverFloor,
            noisyEdgeSharpeningSuppressorFloor = noisyEdgeSharpeningSuppressorFloor,
            temporalMotionSmearGuardFloor = temporalMotionSmearGuardFloor,
            cleanDetailEscalatorFloor = cleanDetailEscalatorFloor,
            sceneAdaptiveEncoderGuardFloor = sceneAdaptiveEncoderGuardFloor,
            qualityEvidenceGateFloor = qualityEvidenceGateFloor,
            noisePressureEvidenceFloor = noisePressureEvidenceFloor,
            rgbChromaInstabilityEvidenceFloor = rgbChromaInstabilityEvidenceFloor,
            motionSmearEvidenceFloor = motionSmearEvidenceFloor,
            edgeHaloRiskEvidenceFloor = edgeHaloRiskEvidenceFloor,
            encoderStressEvidenceFloor = encoderStressEvidenceFloor,
            previewRecordingParityEvidenceFloor = previewRecordingParityEvidenceFloor,
            perceptualResolutionEvidenceFloor = perceptualResolutionEvidenceFloor,
            cleanDetailEvidenceFloor = cleanDetailEvidenceFloor,
            evidenceCalibratedOutputClampFloor = evidenceCalibratedOutputClampFloor,
            v11ConfidenceMatrixFloor = v11ConfidenceMatrixFloor,
            sensorGainNoiseClampFloor = sensorGainNoiseClampFloor,
            temporalStabilityConfidenceFloor = temporalStabilityConfidenceFloor,
            chromaEdgeSeparationFloor = chromaEdgeSeparationFloor,
            detailArtifactRiskGateFloor = detailArtifactRiskGateFloor,
            perceptualUpscaleTruthGuardFloor = perceptualUpscaleTruthGuardFloor,
            codecDetailPreservationGateFloor = codecDetailPreservationGateFloor,
            v12SensorLimitQualityGateFloor = v12SensorLimitQualityGateFloor,
            readNoiseFloorEstimatorFloor = readNoiseFloorEstimatorFloor,
            shadowChromaNeutralityLockFloor = shadowChromaNeutralityLockFloor,
            textureEdgeConfidenceFusionFloor = textureEdgeConfidenceFusionFloor,
            motionAdaptiveDetailHoldFloor = motionAdaptiveDetailHoldFloor,
            perceptualDetailScalerFloor = perceptualDetailScalerFloor,
            edgeMicroContrastLiftFloor = edgeMicroContrastLiftFloor,
            fineTextureDetailBoostFloor = fineTextureDetailBoostFloor,
            haloSafeSharpeningLiftFloor = haloSafeSharpeningLiftFloor,
            recordingPreviewDetailParityFloor = recordingPreviewDetailParityFloor,
            finalTruthfulQualityAuditFloor = finalTruthfulQualityAuditFloor,
            nativeResolutionTruth = NATIVE_RESOLUTION_UNCHANGED,
            ditherCeiling = if (request.recording || signal != VideoImageSignalFamily.LOG) 0f else 0.08f
        )
    }

    private fun rawPlan(
        request: VideoImageQualityPipelineRequest,
        noise: Float,
        rgbGrain: Float,
        detailConfidence: Float,
        lowLight: Float,
        motion: Float,
        staticness: Float
    ): VideoImageQualityPipelinePlan =
        VideoImageQualityPipelinePlan(
            pipelineOrder = pipelineOrder(VideoImageSignalFamily.RAW),
            processedIspRequired = false,
            hdrMergeRequired = false,
            logTransferRequired = false,
            rawCalibrationRequired = true,
            cameraNoiseCleanupProfile = Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL,
            temporalDenoiseFloor = (
                0.16f + noise * 0.12f * staticness + if (request.recording) 0.06f else 0f
                ).coerceIn(0f, 0.54f),
            temporalDenoiseCeiling = when {
                motion >= 0.50f -> 0.48f
                motion >= 0.30f -> 0.54f
                else -> 0.66f
            },
            spatialDenoiseFloor = (
                0.28f + noise * 0.44f + rgbGrain * 0.18f + lowLight * 0.04f + if (request.recording) 0.05f else 0f
                ).coerceIn(0.16f, 0.995f),
            chromaDenoiseFloor = (
                0.42f + noise * 0.36f + rgbGrain * 0.23f + if (request.recording) 0.04f else 0f
                ).coerceIn(0.20f, 0.9997f),
            sharpeningFloor = (
                0.14f + detailConfidence * 0.13f + request.edgeDensity.coerceIn(0f, 1f) * 0.05f - noise * 0.03f
                ).coerceIn(
                    if (request.strictHighEnd) 0.58f else 0.10f,
                    if (request.strictHighEnd) 0.92f else 0.36f
                ),
            sharpeningCeiling = (
                0.38f - noise * 0.08f - rgbGrain * 0.03f
                ).coerceIn(
                    if (request.strictHighEnd) 0.58f else 0.22f,
                    if (request.strictHighEnd) 0.92f else 0.38f
                ),
            contrastFloor = 0.86f,
            contrastCeiling = 1.0f,
            saturationFloor = 0.82f,
            saturationCeiling = 1.0f,
            shadowBoostCeiling = 0.20f + noise * 0.10f,
            highlightCompressionFloor = (
                0.14f + request.dynamicRangeScore.coerceIn(0f, 1f) * 0.12f
                ).coerceIn(0.14f, 0.30f),
            videoHdrFusionFloor = 0f,
            videoHdrFusionCeiling = 0f,
            logStrengthFloor = 0f,
            motionCompensatedFusionFloor = (
                0.12f + noise * 0.12f * staticness + if (request.recording) 0.04f else 0f
                ).coerceIn(0f, 0.42f),
            chromaTemporalStabilityFloor = (
                0.34f + rgbGrain * 0.28f + noise * 0.20f
                ).coerceIn(0.20f, 0.92f),
            perceptualSuperResolutionFloor = 0f,
            haloGuardStrength = 0f,
            edgeConfidenceMapFloor = 0f,
            texturePreservationGuard = 0f,
            adaptiveDetailReconstructionFloor = 0f,
            encoderArtifactSuppressionFloor = 0f,
            sensorNoiseProfileFloor = 0f,
            shadowChromaLockFloor = 0f,
            subpixelEdgeDetectionFloor = 0f,
            aliasSafeDetailReconstructionFloor = 0f,
            sceneComplexityBitrateGuardFloor = 0f,
            rgbDancingNoiseLockFloor = 0f,
            lowLightChromaFreezeFloor = 0f,
            motionGhostGuardFloor = 0f,
            edgeHaloSuppressionFloor = 0f,
            microContrastDetailRestoreFloor = 0f,
            resolutionHonestDetailBoostFloor = 0f,
            rgbVarianceQuarantineFloor = 0f,
            shadowChromaDeadbandFloor = 0f,
            motionSafeTemporalFloor = 0f,
            skinTexturePreservationFloor = 0f,
            moireAliasGuardFloor = 0f,
            detailConfidenceGateFloor = 0f,
            qualityConflictResolverFloor = 0f,
            noisyEdgeSharpeningSuppressorFloor = 0f,
            temporalMotionSmearGuardFloor = 0f,
            cleanDetailEscalatorFloor = 0f,
            sceneAdaptiveEncoderGuardFloor = 0f,
            qualityEvidenceGateFloor = 0f,
            noisePressureEvidenceFloor = 0f,
            rgbChromaInstabilityEvidenceFloor = 0f,
            motionSmearEvidenceFloor = 0f,
            edgeHaloRiskEvidenceFloor = 0f,
            encoderStressEvidenceFloor = 0f,
            previewRecordingParityEvidenceFloor = 0f,
            perceptualResolutionEvidenceFloor = 0f,
            cleanDetailEvidenceFloor = 0f,
            evidenceCalibratedOutputClampFloor = 0f,
            v11ConfidenceMatrixFloor = 0f,
            sensorGainNoiseClampFloor = 0f,
            temporalStabilityConfidenceFloor = 0f,
            chromaEdgeSeparationFloor = 0f,
            detailArtifactRiskGateFloor = 0f,
            perceptualUpscaleTruthGuardFloor = 0f,
            codecDetailPreservationGateFloor = 0f,
            v12SensorLimitQualityGateFloor = 0f,
            readNoiseFloorEstimatorFloor = 0f,
            shadowChromaNeutralityLockFloor = 0f,
            textureEdgeConfidenceFusionFloor = 0f,
            motionAdaptiveDetailHoldFloor = 0f,
            perceptualDetailScalerFloor = 0f,
            edgeMicroContrastLiftFloor = 0f,
            fineTextureDetailBoostFloor = 0f,
            haloSafeSharpeningLiftFloor = 0f,
            recordingPreviewDetailParityFloor = 0f,
            finalTruthfulQualityAuditFloor = 0f,
            nativeResolutionTruth = NATIVE_RESOLUTION_UNCHANGED,
            ditherCeiling = 0f
        )

    private fun pipelineOrder(signal: VideoImageSignalFamily): List<VideoImagePipelineStage> =
        when (signal) {
            VideoImageSignalFamily.HDR -> listOf(
                VideoImagePipelineStage.SENSOR_RAW,
                VideoImagePipelineStage.DEMOSAIC,
                VideoImagePipelineStage.AUTO_WHITE_BALANCE,
                VideoImagePipelineStage.COLOR_CORRECTION_MATRIX,
                VideoImagePipelineStage.QUALITY_EVIDENCE_GATE,
                VideoImagePipelineStage.NOISE_PRESSURE_METER,
                VideoImagePipelineStage.RGB_CHROMA_INSTABILITY_METER,
                VideoImagePipelineStage.V11_CONFIDENCE_MATRIX,
                VideoImagePipelineStage.V12_SENSOR_LIMIT_QUALITY_GATE,
                VideoImagePipelineStage.SENSOR_NOISE_PROFILE,
                VideoImagePipelineStage.READ_NOISE_FLOOR_ESTIMATOR,
                VideoImagePipelineStage.SENSOR_GAIN_NOISE_CLAMP,
                VideoImagePipelineStage.NOISE_REDUCTION,
                VideoImagePipelineStage.MOTION_AWARE_TEMPORAL_DENOISE,
                VideoImagePipelineStage.MOTION_COMPENSATED_MULTI_FRAME_FUSION,
                VideoImagePipelineStage.CHROMA_NOISE_SUPPRESSION,
                VideoImagePipelineStage.CHROMA_TEMPORAL_STABILIZATION,
                VideoImagePipelineStage.SHADOW_CHROMA_LOCK,
                VideoImagePipelineStage.RGB_DANCING_NOISE_LOCK,
                VideoImagePipelineStage.RGB_VARIANCE_QUARANTINE,
                VideoImagePipelineStage.LOW_LIGHT_CHROMA_FREEZE,
                VideoImagePipelineStage.SHADOW_CHROMA_DEADBAND,
                VideoImagePipelineStage.SHADOW_CHROMA_NEUTRALITY_LOCK,
                VideoImagePipelineStage.HDR_MERGE,
                VideoImagePipelineStage.TONE_MAPPING,
                VideoImagePipelineStage.EDGE_CONFIDENCE_MAP,
                VideoImagePipelineStage.SUBPIXEL_EDGE_DETECTION,
                VideoImagePipelineStage.MOTION_GHOST_GUARD,
                VideoImagePipelineStage.MOTION_SAFE_TEMPORAL_FILTER,
                VideoImagePipelineStage.MOTION_SMEAR_RISK_METER,
                VideoImagePipelineStage.TEMPORAL_STABILITY_CONFIDENCE,
                VideoImagePipelineStage.MOTION_ADAPTIVE_DETAIL_HOLD,
                VideoImagePipelineStage.QUALITY_CONFLICT_RESOLVER,
                VideoImagePipelineStage.TEMPORAL_MOTION_SMEAR_GUARD,
                VideoImagePipelineStage.TEXTURE_EDGE_CONFIDENCE_FUSION,
                VideoImagePipelineStage.V13_EDGE_MICRO_CONTRAST_LIFT,
                VideoImagePipelineStage.FINE_TEXTURE_DETAIL_BOOST,
                VideoImagePipelineStage.HALO_SAFE_SHARPENING_LIFT,
                VideoImagePipelineStage.CHROMA_EDGE_SEPARATION_MAP,
                VideoImagePipelineStage.EDGE_AWARE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.NOISY_EDGE_SHARPENING_SUPPRESSOR,
                VideoImagePipelineStage.EDGE_HALO_RISK_METER,
                VideoImagePipelineStage.DETAIL_ARTIFACT_RISK_GATE,
                VideoImagePipelineStage.EDGE_HALO_SUPPRESSION,
                VideoImagePipelineStage.HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION,
                VideoImagePipelineStage.TEXTURE_PRESERVATION_GUARD,
                VideoImagePipelineStage.SKIN_TEXTURE_PRESERVATION,
                VideoImagePipelineStage.MICRO_CONTRAST_DETAIL_RESTORE,
                VideoImagePipelineStage.ADAPTIVE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.ALIAS_SAFE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.MOIRE_ALIAS_GUARD,
                VideoImagePipelineStage.RESOLUTION_HONEST_DETAIL_BOOST,
                VideoImagePipelineStage.DETAIL_CONFIDENCE_GATE,
                VideoImagePipelineStage.CLEAN_DETAIL_ESCALATOR,
                VideoImagePipelineStage.GAMMA_CONVERSION,
                VideoImagePipelineStage.PREVIEW_RECORDING_PARITY_EVIDENCE,
                VideoImagePipelineStage.RECORDING_PREVIEW_DETAIL_PARITY,
                VideoImagePipelineStage.PERCEPTUAL_RESOLUTION_EVIDENCE,
                VideoImagePipelineStage.PERCEPTUAL_UPSCALE_TRUTH_GUARD,
                VideoImagePipelineStage.PERCEPTUAL_DETAIL_SCALER,
                VideoImagePipelineStage.ENCODER_STRESS_METER,
                VideoImagePipelineStage.ENCODER_ARTIFACT_SUPPRESSION,
                VideoImagePipelineStage.SCENE_ADAPTIVE_ENCODER_GUARD,
                VideoImagePipelineStage.EVIDENCE_CALIBRATED_OUTPUT_CLAMP,
                VideoImagePipelineStage.CODEC_DETAIL_PRESERVATION_GATE,
                VideoImagePipelineStage.FINAL_TRUTHFUL_QUALITY_AUDIT,
                VideoImagePipelineStage.SCENE_COMPLEXITY_BITRATE_GUARD,
                VideoImagePipelineStage.OUTPUT
            )
            VideoImageSignalFamily.SDR -> listOf(
                VideoImagePipelineStage.SENSOR_RAW,
                VideoImagePipelineStage.DEMOSAIC,
                VideoImagePipelineStage.AUTO_WHITE_BALANCE,
                VideoImagePipelineStage.COLOR_CORRECTION_MATRIX,
                VideoImagePipelineStage.QUALITY_EVIDENCE_GATE,
                VideoImagePipelineStage.NOISE_PRESSURE_METER,
                VideoImagePipelineStage.RGB_CHROMA_INSTABILITY_METER,
                VideoImagePipelineStage.V11_CONFIDENCE_MATRIX,
                VideoImagePipelineStage.V12_SENSOR_LIMIT_QUALITY_GATE,
                VideoImagePipelineStage.STRICT_SDR_10BIT_NOISE_LOCK,
                VideoImagePipelineStage.SENSOR_NOISE_PROFILE,
                VideoImagePipelineStage.READ_NOISE_FLOOR_ESTIMATOR,
                VideoImagePipelineStage.SENSOR_GAIN_NOISE_CLAMP,
                VideoImagePipelineStage.NOISE_REDUCTION,
                VideoImagePipelineStage.MOTION_AWARE_TEMPORAL_DENOISE,
                VideoImagePipelineStage.MOTION_COMPENSATED_MULTI_FRAME_FUSION,
                VideoImagePipelineStage.CHROMA_NOISE_SUPPRESSION,
                VideoImagePipelineStage.CHROMA_TEMPORAL_STABILIZATION,
                VideoImagePipelineStage.SHADOW_CHROMA_LOCK,
                VideoImagePipelineStage.RGB_DANCING_NOISE_LOCK,
                VideoImagePipelineStage.RGB_VARIANCE_QUARANTINE,
                VideoImagePipelineStage.LOW_LIGHT_CHROMA_FREEZE,
                VideoImagePipelineStage.SHADOW_CHROMA_DEADBAND,
                VideoImagePipelineStage.SHADOW_CHROMA_NEUTRALITY_LOCK,
                VideoImagePipelineStage.TONE_MAPPING,
                VideoImagePipelineStage.EDGE_CONFIDENCE_MAP,
                VideoImagePipelineStage.SUBPIXEL_EDGE_DETECTION,
                VideoImagePipelineStage.MOTION_GHOST_GUARD,
                VideoImagePipelineStage.MOTION_SAFE_TEMPORAL_FILTER,
                VideoImagePipelineStage.MOTION_SMEAR_RISK_METER,
                VideoImagePipelineStage.TEMPORAL_STABILITY_CONFIDENCE,
                VideoImagePipelineStage.MOTION_ADAPTIVE_DETAIL_HOLD,
                VideoImagePipelineStage.QUALITY_CONFLICT_RESOLVER,
                VideoImagePipelineStage.TEMPORAL_MOTION_SMEAR_GUARD,
                VideoImagePipelineStage.TEXTURE_EDGE_CONFIDENCE_FUSION,
                VideoImagePipelineStage.STRICT_SDR_10BIT_DETAIL_RECOVERY,
                VideoImagePipelineStage.V13_EDGE_MICRO_CONTRAST_LIFT,
                VideoImagePipelineStage.FINE_TEXTURE_DETAIL_BOOST,
                VideoImagePipelineStage.HALO_SAFE_SHARPENING_LIFT,
                VideoImagePipelineStage.CHROMA_EDGE_SEPARATION_MAP,
                VideoImagePipelineStage.EDGE_AWARE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.NOISY_EDGE_SHARPENING_SUPPRESSOR,
                VideoImagePipelineStage.EDGE_HALO_RISK_METER,
                VideoImagePipelineStage.DETAIL_ARTIFACT_RISK_GATE,
                VideoImagePipelineStage.EDGE_HALO_SUPPRESSION,
                VideoImagePipelineStage.HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION,
                VideoImagePipelineStage.TEXTURE_PRESERVATION_GUARD,
                VideoImagePipelineStage.SKIN_TEXTURE_PRESERVATION,
                VideoImagePipelineStage.MICRO_CONTRAST_DETAIL_RESTORE,
                VideoImagePipelineStage.ADAPTIVE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.ALIAS_SAFE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.MOIRE_ALIAS_GUARD,
                VideoImagePipelineStage.RESOLUTION_HONEST_DETAIL_BOOST,
                VideoImagePipelineStage.DETAIL_CONFIDENCE_GATE,
                VideoImagePipelineStage.CLEAN_DETAIL_ESCALATOR,
                VideoImagePipelineStage.GAMMA_CONVERSION,
                VideoImagePipelineStage.PREVIEW_RECORDING_PARITY_EVIDENCE,
                VideoImagePipelineStage.RECORDING_PREVIEW_DETAIL_PARITY,
                VideoImagePipelineStage.PERCEPTUAL_RESOLUTION_EVIDENCE,
                VideoImagePipelineStage.PERCEPTUAL_UPSCALE_TRUTH_GUARD,
                VideoImagePipelineStage.PERCEPTUAL_DETAIL_SCALER,
                VideoImagePipelineStage.ENCODER_STRESS_METER,
                VideoImagePipelineStage.ENCODER_ARTIFACT_SUPPRESSION,
                VideoImagePipelineStage.SCENE_ADAPTIVE_ENCODER_GUARD,
                VideoImagePipelineStage.EVIDENCE_CALIBRATED_OUTPUT_CLAMP,
                VideoImagePipelineStage.CODEC_DETAIL_PRESERVATION_GATE,
                VideoImagePipelineStage.FINAL_TRUTHFUL_QUALITY_AUDIT,
                VideoImagePipelineStage.SCENE_COMPLEXITY_BITRATE_GUARD,
                VideoImagePipelineStage.OUTPUT
            )
            VideoImageSignalFamily.LOG -> listOf(
                VideoImagePipelineStage.SENSOR_RAW,
                VideoImagePipelineStage.DEMOSAIC,
                VideoImagePipelineStage.AUTO_WHITE_BALANCE,
                VideoImagePipelineStage.COLOR_CORRECTION_MATRIX,
                VideoImagePipelineStage.QUALITY_EVIDENCE_GATE,
                VideoImagePipelineStage.NOISE_PRESSURE_METER,
                VideoImagePipelineStage.RGB_CHROMA_INSTABILITY_METER,
                VideoImagePipelineStage.V11_CONFIDENCE_MATRIX,
                VideoImagePipelineStage.V12_SENSOR_LIMIT_QUALITY_GATE,
                VideoImagePipelineStage.SENSOR_NOISE_PROFILE,
                VideoImagePipelineStage.READ_NOISE_FLOOR_ESTIMATOR,
                VideoImagePipelineStage.SENSOR_GAIN_NOISE_CLAMP,
                VideoImagePipelineStage.NOISE_REDUCTION,
                VideoImagePipelineStage.MOTION_AWARE_TEMPORAL_DENOISE,
                VideoImagePipelineStage.MOTION_COMPENSATED_MULTI_FRAME_FUSION,
                VideoImagePipelineStage.CHROMA_NOISE_SUPPRESSION,
                VideoImagePipelineStage.CHROMA_TEMPORAL_STABILIZATION,
                VideoImagePipelineStage.SHADOW_CHROMA_LOCK,
                VideoImagePipelineStage.RGB_DANCING_NOISE_LOCK,
                VideoImagePipelineStage.RGB_VARIANCE_QUARANTINE,
                VideoImagePipelineStage.LOW_LIGHT_CHROMA_FREEZE,
                VideoImagePipelineStage.SHADOW_CHROMA_DEADBAND,
                VideoImagePipelineStage.SHADOW_CHROMA_NEUTRALITY_LOCK,
                VideoImagePipelineStage.EDGE_CONFIDENCE_MAP,
                VideoImagePipelineStage.SUBPIXEL_EDGE_DETECTION,
                VideoImagePipelineStage.MOTION_GHOST_GUARD,
                VideoImagePipelineStage.MOTION_SAFE_TEMPORAL_FILTER,
                VideoImagePipelineStage.MOTION_SMEAR_RISK_METER,
                VideoImagePipelineStage.TEMPORAL_STABILITY_CONFIDENCE,
                VideoImagePipelineStage.MOTION_ADAPTIVE_DETAIL_HOLD,
                VideoImagePipelineStage.QUALITY_CONFLICT_RESOLVER,
                VideoImagePipelineStage.TEMPORAL_MOTION_SMEAR_GUARD,
                VideoImagePipelineStage.TEXTURE_EDGE_CONFIDENCE_FUSION,
                VideoImagePipelineStage.V13_EDGE_MICRO_CONTRAST_LIFT,
                VideoImagePipelineStage.FINE_TEXTURE_DETAIL_BOOST,
                VideoImagePipelineStage.HALO_SAFE_SHARPENING_LIFT,
                VideoImagePipelineStage.CHROMA_EDGE_SEPARATION_MAP,
                VideoImagePipelineStage.EDGE_AWARE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.NOISY_EDGE_SHARPENING_SUPPRESSOR,
                VideoImagePipelineStage.EDGE_HALO_RISK_METER,
                VideoImagePipelineStage.DETAIL_ARTIFACT_RISK_GATE,
                VideoImagePipelineStage.EDGE_HALO_SUPPRESSION,
                VideoImagePipelineStage.HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION,
                VideoImagePipelineStage.TEXTURE_PRESERVATION_GUARD,
                VideoImagePipelineStage.SKIN_TEXTURE_PRESERVATION,
                VideoImagePipelineStage.MICRO_CONTRAST_DETAIL_RESTORE,
                VideoImagePipelineStage.ADAPTIVE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.ALIAS_SAFE_DETAIL_RECONSTRUCTION,
                VideoImagePipelineStage.MOIRE_ALIAS_GUARD,
                VideoImagePipelineStage.RESOLUTION_HONEST_DETAIL_BOOST,
                VideoImagePipelineStage.DETAIL_CONFIDENCE_GATE,
                VideoImagePipelineStage.CLEAN_DETAIL_ESCALATOR,
                VideoImagePipelineStage.LOG_TRANSFER,
                VideoImagePipelineStage.PREVIEW_RECORDING_PARITY_EVIDENCE,
                VideoImagePipelineStage.RECORDING_PREVIEW_DETAIL_PARITY,
                VideoImagePipelineStage.PERCEPTUAL_RESOLUTION_EVIDENCE,
                VideoImagePipelineStage.PERCEPTUAL_UPSCALE_TRUTH_GUARD,
                VideoImagePipelineStage.PERCEPTUAL_DETAIL_SCALER,
                VideoImagePipelineStage.ENCODER_STRESS_METER,
                VideoImagePipelineStage.ENCODER_ARTIFACT_SUPPRESSION,
                VideoImagePipelineStage.SCENE_ADAPTIVE_ENCODER_GUARD,
                VideoImagePipelineStage.EVIDENCE_CALIBRATED_OUTPUT_CLAMP,
                VideoImagePipelineStage.CODEC_DETAIL_PRESERVATION_GATE,
                VideoImagePipelineStage.FINAL_TRUTHFUL_QUALITY_AUDIT,
                VideoImagePipelineStage.SCENE_COMPLEXITY_BITRATE_GUARD,
                VideoImagePipelineStage.OUTPUT
            )
            VideoImageSignalFamily.RAW -> listOf(
                VideoImagePipelineStage.SENSOR_RAW,
                VideoImagePipelineStage.BLACK_LEVEL_CALIBRATION,
                VideoImagePipelineStage.RAW_METADATA,
                VideoImagePipelineStage.RAW_CONTAINER_OUTPUT
            )
        }

    private data class ZeroGrainClarityDecision(
        val noiseSuppressionGate: Float,
        val temporalDenoiseFloor: Float,
        val spatialDenoiseFloor: Float,
        val chromaDenoiseFloor: Float,
        val sharpeningCeilingCap: Float
    )

    private fun zeroGrainClarityDecision(
        request: VideoImageQualityPipelineRequest,
        signal: VideoImageSignalFamily,
        noise: Float,
        rgbGrain: Float,
        motion: Float,
        temporalCeiling: Float,
        strictBoost: Float
    ): ZeroGrainClarityDecision {
        if (signal == VideoImageSignalFamily.RAW || !request.recording) {
            return ZeroGrainClarityDecision(
                noiseSuppressionGate = 0f,
                temporalDenoiseFloor = 0f,
                spatialDenoiseFloor = 0f,
                chromaDenoiseFloor = 0f,
                sharpeningCeilingCap = 1f
            )
        }

        val noiseGate = ((noise - 0.38f) / 0.34f).coerceIn(0f, 1f)
        val chromaGate = ((rgbGrain - 0.28f) / 0.32f).coerceIn(0f, 1f)
        val zeroGrainGate = maxOf(noiseGate, chromaGate)
        if (zeroGrainGate <= 0f) {
            return ZeroGrainClarityDecision(
                noiseSuppressionGate = 0f,
                temporalDenoiseFloor = 0f,
                spatialDenoiseFloor = 0f,
                chromaDenoiseFloor = 0f,
                sharpeningCeilingCap = 1f
            )
        }

        val temporalMotionCap = when {
            motion >= 0.50f -> minOf(temporalCeiling, 0.50f)
            motion >= 0.30f -> minOf(temporalCeiling, 0.54f)
            motion >= 0.18f -> minOf(temporalCeiling, 0.62f)
            motion >= 0.10f -> minOf(temporalCeiling, 0.66f)
            else -> minOf(temporalCeiling, 0.72f)
        }
        val temporalFloor = (
            0.42f +
                zeroGrainGate * 0.24f +
                strictBoost * 0.04f +
                (if (signal == VideoImageSignalFamily.LOG) 0.02f else 0f) -
                motion * 0.08f
            ).coerceIn(0f, temporalMotionCap)
        // Keep luma texture available to the edge/detail stages. Near-unity spatial
        // filtering erases distant foliage, brick and text before reconstruction can
        // classify it. Chroma can remain stronger because it carries substantially
        // less perceived fine detail, but it is still bounded to avoid color smearing.
        val spatialFloor = (
            0.42f +
                zeroGrainGate * 0.30f +
                strictBoost * 0.025f +
                if (signal == VideoImageSignalFamily.HDR) 0.015f else 0f
            ).coerceIn(0f, if (request.strictHighEnd) 0.82f else 0.78f)
        val chromaFloor = (
            0.58f +
                zeroGrainGate * 0.24f +
                chromaGate * 0.06f +
                strictBoost * 0.015f +
                if (signal == VideoImageSignalFamily.LOG || signal == VideoImageSignalFamily.HDR) 0.015f else 0f
            ).coerceIn(0f, if (request.strictHighEnd) 0.92f else 0.88f)
        val sharpeningCap = (
            0.46f -
                zeroGrainGate * 0.12f -
                chromaGate * 0.02f -
                motion * 0.06f
            ).coerceIn(if (signal == VideoImageSignalFamily.LOG) 0.18f else 0.30f, 1f)

        return ZeroGrainClarityDecision(
            noiseSuppressionGate = zeroGrainGate,
            temporalDenoiseFloor = temporalFloor,
            spatialDenoiseFloor = spatialFloor,
            chromaDenoiseFloor = chromaFloor,
            sharpeningCeilingCap = sharpeningCap
        )
    }

    private fun zeroGrainProtectedMicroDetailGate(
        request: VideoImageQualityPipelineRequest,
        noise: Float,
        rgbGrain: Float,
        motion: Float,
        detailConfidence: Float,
        highConfidenceDetailGate: Float,
        detailArtifactRiskGateFloor: Float
    ): Float {
        val textureGuard = (
            ((detailConfidence - 0.70f) / 0.18f).coerceIn(0f, 1f) *
                ((request.edgeDensity.coerceIn(0f, 1f) - 0.24f) / 0.16f).coerceIn(0f, 1f) *
                ((request.perceptualSharpness.coerceIn(0f, 1f) - 0.68f) / 0.18f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        val noiseGuard = (
            ((0.18f - noise) / 0.16f).coerceIn(0f, 1f) *
                ((0.16f - rgbGrain) / 0.14f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        val motionGuard = ((0.18f - motion) / 0.18f).coerceIn(0f, 1f)
        val artifactGuard = ((0.82f - detailArtifactRiskGateFloor) / 0.24f).coerceIn(0f, 1f)

        return (
            highConfidenceDetailGate *
                textureGuard *
                noiseGuard *
                motionGuard *
                artifactGuard
            ).coerceIn(0f, 1f)
    }

    private fun noisePressure(request: VideoImageQualityPipelineRequest): Float {
        val lowLight = lowLightPressure(request.estimatedLux)
        val measured = request.normalizedNoiseFloor.coerceIn(0f, 1f)
        val flat = ((request.flatRegionStdDev - 2f) / 13f).coerceIn(0f, 1f)
        val iso = ((request.realIsoNoiseFloor - 400f) / 32_000f).coerceIn(0f, 1f)
        return maxOf(lowLight * 0.92f, measured, flat, iso).coerceIn(0f, 1f)
    }

    private fun rgbGrainPressure(
        request: VideoImageQualityPipelineRequest,
        noisePressure: Float
    ): Float {
        val flat = ((request.flatRegionStdDev - 3f) / 14f).coerceIn(0f, 1f)
        val iso = ((request.realIsoNoiseFloor - 1_600f) / 28_000f).coerceIn(0f, 1f)
        return (
            noisePressure * 0.48f +
                flat * 0.26f +
                iso * 0.22f +
                lowLightPressure(request.estimatedLux) * 0.04f
            ).coerceIn(0f, 1f)
    }

    private fun chromaTemporalLockBase(
        chromaFloor: Float,
        rgbGrainPressure: Float,
        lowLightPressure: Float
    ): Float =
        chromaFloor + 0.035f + rgbGrainPressure * 0.065f + lowLightPressure * 0.035f

    private fun detailConfidence(request: VideoImageQualityPipelineRequest): Float =
        (
            (request.edgeDensity / 0.34f).coerceIn(0f, 1f) * 0.46f +
                request.perceptualSharpness.coerceIn(0f, 1f) * 0.54f
            ).coerceIn(0f, 1f)

    private fun lowLightPressure(estimatedLux: Float): Float =
        ((18f - estimatedLux) / 18f).coerceIn(0f, 1f)

    private fun lowResolutionDetailLift(longEdge: Int): Float =
        when {
            longEdge <= 1280 -> 0.14f
            longEdge <= 2048 -> 0.10f
            longEdge <= 2560 -> 0.06f
            else -> 0f
        }

    private fun haloGuardStrength(noise: Float, rgbGrain: Float, strictBoost: Float): Float =
        (
            0.62f + noise * 0.16f + rgbGrain * 0.08f + strictBoost * 0.05f
        ).coerceIn(0.60f, 0.92f)

    private fun contrastFloor(
        signal: VideoImageSignalFamily,
        request: VideoImageQualityPipelineRequest
    ): Float =
        when (signal) {
            VideoImageSignalFamily.HDR -> 0.88f
            VideoImageSignalFamily.LOG -> 0.48f
            VideoImageSignalFamily.SDR -> if (request.strictHighEnd) 0.94f else 0.88f
            VideoImageSignalFamily.RAW -> 0.86f
        }

    private fun contrastCeiling(signal: VideoImageSignalFamily): Float =
        when (signal) {
            VideoImageSignalFamily.HDR -> 1.03f
            VideoImageSignalFamily.LOG -> 0.76f
            VideoImageSignalFamily.SDR -> 1.22f
            VideoImageSignalFamily.RAW -> 1.0f
        }

    private fun saturationFloor(signal: VideoImageSignalFamily): Float =
        when (signal) {
            VideoImageSignalFamily.HDR -> 0.90f
            VideoImageSignalFamily.LOG -> 0.52f
            VideoImageSignalFamily.SDR -> 0.94f
            VideoImageSignalFamily.RAW -> 0.82f
        }

    private fun saturationCeiling(signal: VideoImageSignalFamily): Float =
        when (signal) {
            VideoImageSignalFamily.HDR -> 1.04f
            VideoImageSignalFamily.LOG -> 0.82f
            VideoImageSignalFamily.SDR -> 1.18f
            VideoImageSignalFamily.RAW -> 1.0f
        }

    private fun shadowBoostCeiling(
        signal: VideoImageSignalFamily,
        lowLightPressure: Float
    ): Float =
        when (signal) {
            VideoImageSignalFamily.HDR -> (0.38f + lowLightPressure * 0.10f).coerceIn(0.38f, 0.50f)
            VideoImageSignalFamily.LOG -> 0.58f
            VideoImageSignalFamily.SDR -> (0.48f + lowLightPressure * 0.20f).coerceIn(0.48f, 0.74f)
            VideoImageSignalFamily.RAW -> 0.30f
        }

    private fun highlightCompressionFloor(
        signal: VideoImageSignalFamily,
        request: VideoImageQualityPipelineRequest,
        noisePressure: Float
    ): Float =
        when (signal) {
            VideoImageSignalFamily.HDR -> (
                0.28f +
                    request.highlightRatio.coerceIn(0f, 1f) * 0.25f +
                    request.dynamicRangeScore.coerceIn(0f, 1f) * 0.08f +
                    noisePressure * 0.05f
                ).coerceIn(0.28f, 0.52f)
            VideoImageSignalFamily.LOG -> 0.38f
            VideoImageSignalFamily.SDR -> 0.16f
            VideoImageSignalFamily.RAW -> 0.14f
        }

    private const val NATIVE_RESOLUTION_UNCHANGED = "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED"
}
