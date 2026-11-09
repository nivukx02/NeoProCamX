package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.camera.Camera2NoiseCleanupProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoImageQualityPipelinePolicyTest {

    @Test
    fun hdrUsesRequiredProcessedPipelineOrder() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())

        assertEquals(
            listOf(
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
            ),
            plan.pipelineOrder
        )
        assertTrue(plan.processedIspRequired)
        assertTrue(plan.hdrMergeRequired)
        assertFalse(plan.logTransferRequired)
        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.cameraNoiseCleanupProfile)
    }

    @Test
    fun logStaysSeparateFromHdrAndKeepsFlatGradingSignal() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.LOG)
        )

        assertFalse(plan.hdrMergeRequired)
        assertTrue(plan.logTransferRequired)
        assertFalse(plan.pipelineOrder.contains(VideoImagePipelineStage.HDR_MERGE))
        assertTrue(plan.pipelineOrder.contains(VideoImagePipelineStage.LOG_TRANSFER))
        assertEquals(0f, plan.videoHdrFusionCeiling, 0.0001f)
        assertTrue("contrast=${plan.contrastCeiling}", plan.contrastCeiling <= 0.78f)
        assertTrue("saturation=${plan.saturationCeiling}", plan.saturationCeiling <= 0.84f)
    }

    @Test
    fun rawKeepsTruthfulSensorPipelineWithoutProcessedIsp() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        )

        assertEquals(
            listOf(
                VideoImagePipelineStage.SENSOR_RAW,
                VideoImagePipelineStage.BLACK_LEVEL_CALIBRATION,
                VideoImagePipelineStage.RAW_METADATA,
                VideoImagePipelineStage.RAW_CONTAINER_OUTPUT
            ),
            plan.pipelineOrder
        )
        assertFalse(plan.processedIspRequired)
        assertFalse(plan.hdrMergeRequired)
        assertFalse(plan.logTransferRequired)
        assertTrue(plan.rawCalibrationRequired)
        assertEquals(Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL, plan.cameraNoiseCleanupProfile)
    }

    @Test
    fun severeMovingRgbGrainRaisesChromaAndSpatialDenoiseButBoundsSharpening() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())

        assertTrue("temporal=${plan.temporalDenoiseFloor}", plan.temporalDenoiseFloor >= 0.42f)
        assertTrue("spatial=${plan.spatialDenoiseFloor}", plan.spatialDenoiseFloor in 0.68f..0.82f)
        assertTrue("chroma=${plan.chromaDenoiseFloor}", plan.chromaDenoiseFloor in 0.80f..0.92f)
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.48f)
        assertTrue("highlight=${plan.highlightCompressionFloor}", plan.highlightCompressionFloor >= 0.38f)
    }

    @Test
    fun encodedModesIncludeEliteNoiseAndEdgeAwareDetailStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("MOTION_AWARE_TEMPORAL_DENOISE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("CHROMA_NOISE_SUPPRESSION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("EDGE_AWARE_DETAIL_RECONSTRUCTION"))
            assertTrue(
                "$signal stages=$stageNames",
                stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION") < stageNames.indexOf("OUTPUT")
            )
        }

        val raw = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW))
        val rawStageNames = raw.pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("EDGE_AWARE_DETAIL_RECONSTRUCTION"))
    }

    @Test
    fun strictEncodedModesIncludeEliteV2MultiFrameChromaStabilityAndSuperResolutionStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("MOTION_COMPENSATED_MULTI_FRAME_FUSION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("CHROMA_TEMPORAL_STABILIZATION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION"))
            assertTrue(
                "$signal chroma stability must run after chroma suppression",
                stageNames.indexOf("CHROMA_TEMPORAL_STABILIZATION") >
                    stageNames.indexOf("CHROMA_NOISE_SUPPRESSION")
            )
            assertTrue(
                "$signal super resolution must run after edge reconstruction",
                stageNames.indexOf("HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION") >
                    stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION")
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("HALO_GUARDED_PERCEPTUAL_SUPER_RESOLUTION"))
    }

    @Test
    fun strictEncodedModesIncludeEliteV3MeasuredCinemaTextureAndEncoderArtifactStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("EDGE_CONFIDENCE_MAP"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("TEXTURE_PRESERVATION_GUARD"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("ADAPTIVE_DETAIL_RECONSTRUCTION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("ENCODER_ARTIFACT_SUPPRESSION"))
            assertTrue(
                "$signal edge confidence must be built after temporal chroma stabilization",
                stageNames.indexOf("EDGE_CONFIDENCE_MAP") > stageNames.indexOf("CHROMA_TEMPORAL_STABILIZATION")
            )
            assertTrue(
                "$signal texture guard must run before adaptive detail reconstruction",
                stageNames.indexOf("TEXTURE_PRESERVATION_GUARD") <
                    stageNames.indexOf("ADAPTIVE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal encoder artifact suppression must run before output",
                stageNames.indexOf("ENCODER_ARTIFACT_SUPPRESSION") < stageNames.indexOf("OUTPUT")
            )
            if (stageNames.contains("SCENE_COMPLEXITY_BITRATE_GUARD")) {
                assertTrue(
                    "$signal encoder artifact suppression must feed the scene complexity guard",
                    stageNames.indexOf("ENCODER_ARTIFACT_SUPPRESSION") <
                        stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD")
                )
            }
            assertTrue("chroma stability=${plan.chromaTemporalStabilityFloor}", plan.chromaTemporalStabilityFloor >= 0.9999f)
            assertTrue("halo guard=${plan.haloGuardStrength}", plan.haloGuardStrength >= 0.84f)
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("EDGE_CONFIDENCE_MAP"))
        assertFalse(rawStageNames.contains("TEXTURE_PRESERVATION_GUARD"))
        assertFalse(rawStageNames.contains("ADAPTIVE_DETAIL_RECONSTRUCTION"))
        assertFalse(rawStageNames.contains("ENCODER_ARTIFACT_SUPPRESSION"))
    }

    @Test
    fun strictEncodedModesIncludeEliteV4CinemaFidelitySensorShadowSubpixelAndBitrateStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("SENSOR_NOISE_PROFILE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SHADOW_CHROMA_LOCK"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SUBPIXEL_EDGE_DETECTION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("ALIAS_SAFE_DETAIL_RECONSTRUCTION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SCENE_COMPLEXITY_BITRATE_GUARD"))
            assertTrue(
                "$signal sensor noise profile must run before denoise",
                stageNames.indexOf("SENSOR_NOISE_PROFILE") < stageNames.indexOf("NOISE_REDUCTION")
            )
            assertTrue(
                "$signal shadow chroma lock must run after temporal chroma stabilization",
                stageNames.indexOf("SHADOW_CHROMA_LOCK") > stageNames.indexOf("CHROMA_TEMPORAL_STABILIZATION")
            )
            assertTrue(
                "$signal subpixel detection must feed alias-safe reconstruction",
                stageNames.indexOf("SUBPIXEL_EDGE_DETECTION") < stageNames.indexOf("ALIAS_SAFE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal scene complexity guard must run last before output",
                stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD") == stageNames.indexOf("OUTPUT") - 1
            )
            val shadowLock = planFieldFloat(plan, "shadowChromaLockFloor")
            val subpixel = planFieldFloat(plan, "subpixelEdgeDetectionFloor")
            val aliasGuard = planFieldFloat(plan, "aliasSafeDetailReconstructionFloor")
            val bitrateGuard = planFieldFloat(plan, "sceneComplexityBitrateGuardFloor")
            assertTrue("shadow lock=$shadowLock", shadowLock >= 0.92f)
            assertTrue("subpixel=$subpixel", subpixel >= 0.16f)
            assertTrue("alias guard=$aliasGuard", aliasGuard >= plan.adaptiveDetailReconstructionFloor)
            assertTrue("bitrate guard=$bitrateGuard", bitrateGuard >= 0.34f)
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("SENSOR_NOISE_PROFILE"))
        assertFalse(rawStageNames.contains("SHADOW_CHROMA_LOCK"))
        assertFalse(rawStageNames.contains("SUBPIXEL_EDGE_DETECTION"))
        assertFalse(rawStageNames.contains("ALIAS_SAFE_DETAIL_RECONSTRUCTION"))
        assertFalse(rawStageNames.contains("SCENE_COMPLEXITY_BITRATE_GUARD"))
    }

    @Test
    fun strictEncodedModesIncludeEliteV7RgbNoiseGhostHaloAndHonestDetailStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("RGB_DANCING_NOISE_LOCK"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("LOW_LIGHT_CHROMA_FREEZE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("MOTION_GHOST_GUARD"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("MICRO_CONTRAST_DETAIL_RESTORE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("EDGE_HALO_SUPPRESSION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("RESOLUTION_HONEST_DETAIL_BOOST"))
            assertTrue(
                "$signal RGB lock must run after shadow chroma lock",
                stageNames.indexOf("RGB_DANCING_NOISE_LOCK") > stageNames.indexOf("SHADOW_CHROMA_LOCK")
            )
            assertTrue(
                "$signal ghost guard must run before detail reconstruction",
                stageNames.indexOf("MOTION_GHOST_GUARD") < stageNames.indexOf("ADAPTIVE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal halo suppression must run after edge-aware detail",
                stageNames.indexOf("EDGE_HALO_SUPPRESSION") > stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal honest detail boost must feed encoder artifact suppression",
                stageNames.indexOf("RESOLUTION_HONEST_DETAIL_BOOST") <
                    stageNames.indexOf("ENCODER_ARTIFACT_SUPPRESSION")
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("RGB_DANCING_NOISE_LOCK"))
        assertFalse(rawStageNames.contains("LOW_LIGHT_CHROMA_FREEZE"))
        assertFalse(rawStageNames.contains("MOTION_GHOST_GUARD"))
        assertFalse(rawStageNames.contains("RESOLUTION_HONEST_DETAIL_BOOST"))
    }

    @Test
    fun severeLowLightV7LocksRgbNoiseAndGhostingWhileReducingHaloRisk() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val rgbLock = planFieldFloat(plan, "rgbDancingNoiseLockFloor")
        val lowLightChromaFreeze = planFieldFloat(plan, "lowLightChromaFreezeFloor")
        val motionGhostGuard = planFieldFloat(plan, "motionGhostGuardFloor")
        val edgeHaloSuppression = planFieldFloat(plan, "edgeHaloSuppressionFloor")

        assertTrue("rgbLock=$rgbLock", rgbLock >= 0.995f)
        assertTrue("lowLightChromaFreeze=$lowLightChromaFreeze", lowLightChromaFreeze >= 0.995f)
        assertTrue("motionGhostGuard=$motionGhostGuard", motionGhostGuard >= 0.70f)
        assertTrue("edgeHaloSuppression=$edgeHaloSuppression", edgeHaloSuppression >= 0.94f)
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.48f)
    }

    @Test
    fun lowResolutionV7RaisesHonestDetailAndCompressionGuardsWithoutFakingResolution() {
        val lowResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 1024, height = 576)
        )
        val highResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 3840, height = 2160)
        )
        val lowHonestDetail = planFieldFloat(lowResolution, "resolutionHonestDetailBoostFloor")
        val highHonestDetail = planFieldFloat(highResolution, "resolutionHonestDetailBoostFloor")

        assertTrue("lowHonestDetail=$lowHonestDetail highHonestDetail=$highHonestDetail", lowHonestDetail > highHonestDetail)
        assertTrue("lowHonestDetail=$lowHonestDetail", lowHonestDetail >= 0.50f)
        assertTrue(
            "low encoder guard=${lowResolution.encoderArtifactSuppressionFloor} high=${highResolution.encoderArtifactSuppressionFloor}",
            lowResolution.encoderArtifactSuppressionFloor > highResolution.encoderArtifactSuppressionFloor
        )
        assertTrue(
            "low detail=${lowResolution.adaptiveDetailReconstructionFloor} high=${highResolution.adaptiveDetailReconstructionFloor}",
            lowResolution.adaptiveDetailReconstructionFloor >= highResolution.adaptiveDetailReconstructionFloor
        )
    }

    @Test
    fun strictEncodedModesIncludeEliteV8MeasuredCleanDetailStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("RGB_VARIANCE_QUARANTINE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SHADOW_CHROMA_DEADBAND"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("MOTION_SAFE_TEMPORAL_FILTER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SKIN_TEXTURE_PRESERVATION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("MOIRE_ALIAS_GUARD"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("DETAIL_CONFIDENCE_GATE"))
            assertTrue(
                "$signal variance quarantine must run after RGB dancing lock",
                stageNames.indexOf("RGB_VARIANCE_QUARANTINE") > stageNames.indexOf("RGB_DANCING_NOISE_LOCK")
            )
            assertTrue(
                "$signal shadow deadband must run before detail stages",
                stageNames.indexOf("SHADOW_CHROMA_DEADBAND") < stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal moire guard must run before honest detail boost",
                stageNames.indexOf("MOIRE_ALIAS_GUARD") < stageNames.indexOf("RESOLUTION_HONEST_DETAIL_BOOST")
            )
            assertTrue(
                "$signal confidence gate must run before output",
                stageNames.indexOf("DETAIL_CONFIDENCE_GATE") < stageNames.indexOf("OUTPUT")
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("RGB_VARIANCE_QUARANTINE"))
        assertFalse(rawStageNames.contains("SHADOW_CHROMA_DEADBAND"))
        assertFalse(rawStageNames.contains("DETAIL_CONFIDENCE_GATE"))
    }

    @Test
    fun severeLowLightV8QuarantinesRgbVarianceAndProtectsTextureFromSmear() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val rgbVariance = planFieldFloat(plan, "rgbVarianceQuarantineFloor")
        val shadowDeadband = planFieldFloat(plan, "shadowChromaDeadbandFloor")
        val motionSafeTemporal = planFieldFloat(plan, "motionSafeTemporalFloor")
        val skinTexture = planFieldFloat(plan, "skinTexturePreservationFloor")
        val moireGuard = planFieldFloat(plan, "moireAliasGuardFloor")

        assertTrue("rgbVariance=$rgbVariance", rgbVariance >= 0.998f)
        assertTrue("shadowDeadband=$shadowDeadband", shadowDeadband >= 0.998f)
        assertTrue("motionSafeTemporal=$motionSafeTemporal", motionSafeTemporal in 0.50f..plan.temporalDenoiseCeiling)
        assertTrue("skinTexture=$skinTexture", skinTexture >= 0.92f)
        assertTrue("moireGuard=$moireGuard", moireGuard >= 0.90f)
    }

    @Test
    fun cleanDetailedV8RaisesDetailConfidenceWithoutBreakingNativeResolutionTruth() {
        val clean = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest())
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR))
        val cleanDetailGate = planFieldFloat(clean, "detailConfidenceGateFloor")
        val noisyDetailGate = planFieldFloat(noisy, "detailConfidenceGateFloor")
        val cleanTexture = planFieldFloat(clean, "skinTexturePreservationFloor")

        assertTrue("cleanDetailGate=$cleanDetailGate noisyDetailGate=$noisyDetailGate", cleanDetailGate > noisyDetailGate)
        assertTrue("cleanDetailGate=$cleanDetailGate", cleanDetailGate >= 0.48f)
        assertTrue("cleanTexture=$cleanTexture", cleanTexture >= 0.84f)
        assertTrue(clean.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun strictEncodedModesIncludeEliteV9ConflictResolverAndEncoderGuardStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("QUALITY_CONFLICT_RESOLVER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("NOISY_EDGE_SHARPENING_SUPPRESSOR"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("TEMPORAL_MOTION_SMEAR_GUARD"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("CLEAN_DETAIL_ESCALATOR"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SCENE_ADAPTIVE_ENCODER_GUARD"))
            assertTrue(
                "$signal conflict resolver must run before detail reconstruction",
                stageNames.indexOf("QUALITY_CONFLICT_RESOLVER") < stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal noisy edge suppressor must run before halo suppression",
                stageNames.indexOf("NOISY_EDGE_SHARPENING_SUPPRESSOR") < stageNames.indexOf("EDGE_HALO_SUPPRESSION")
            )
            assertTrue(
                "$signal scene adaptive encoder guard must feed scene complexity guard",
                stageNames.indexOf("SCENE_ADAPTIVE_ENCODER_GUARD") <
                    stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD")
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("QUALITY_CONFLICT_RESOLVER"))
        assertFalse(rawStageNames.contains("SCENE_ADAPTIVE_ENCODER_GUARD"))
    }

    @Test
    fun noisyV9SuppressesOversharpeningAndRaisesEncoderGuard() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val conflict = planFieldFloat(plan, "qualityConflictResolverFloor")
        val noisyEdgeSuppressor = planFieldFloat(plan, "noisyEdgeSharpeningSuppressorFloor")
        val sceneAdaptiveEncoder = planFieldFloat(plan, "sceneAdaptiveEncoderGuardFloor")

        assertTrue("conflict=$conflict", conflict >= 0.92f)
        assertTrue("noisyEdgeSuppressor=$noisyEdgeSuppressor", noisyEdgeSuppressor >= 0.92f)
        assertTrue(
            "sceneAdaptiveEncoder=$sceneAdaptiveEncoder sceneComplexity=${plan.sceneComplexityBitrateGuardFloor}",
            sceneAdaptiveEncoder >= plan.sceneComplexityBitrateGuardFloor
        )
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.48f)
    }

    @Test
    fun movingV9CapsTemporalDenoiseToAvoidSmear() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(
                motionScore = 0.72f,
                normalizedNoiseFloor = 0.58f,
                flatRegionStdDev = 10.4f,
                realIsoNoiseFloor = 2_400f
            )
        )
        val smearGuard = planFieldFloat(plan, "temporalMotionSmearGuardFloor")

        assertTrue("smearGuard=$smearGuard", smearGuard >= 0.90f)
        assertTrue("motionSafeTemporal=${plan.motionSafeTemporalFloor}", plan.motionSafeTemporalFloor <= 0.54f)
        assertTrue("temporalCeiling=${plan.temporalDenoiseCeiling}", plan.temporalDenoiseCeiling <= 0.54f)
    }

    @Test
    fun cleanV9EscalatesDetailOnlyWhenConfidenceIsHigh() {
        val clean = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest())
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR))
        val cleanEscalator = planFieldFloat(clean, "cleanDetailEscalatorFloor")
        val noisyEscalator = planFieldFloat(noisy, "cleanDetailEscalatorFloor")

        assertTrue("cleanEscalator=$cleanEscalator noisyEscalator=$noisyEscalator", cleanEscalator > noisyEscalator)
        assertTrue("cleanEscalator=$cleanEscalator", cleanEscalator >= 0.52f)
        assertTrue(clean.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun strictEncodedModesIncludeEliteV10EvidenceCalibratedQualityGateStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("QUALITY_EVIDENCE_GATE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("NOISE_PRESSURE_METER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("RGB_CHROMA_INSTABILITY_METER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("MOTION_SMEAR_RISK_METER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("EDGE_HALO_RISK_METER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("ENCODER_STRESS_METER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("PREVIEW_RECORDING_PARITY_EVIDENCE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("PERCEPTUAL_RESOLUTION_EVIDENCE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("EVIDENCE_CALIBRATED_OUTPUT_CLAMP"))
            assertTrue(
                "$signal noise evidence must be measured before conflict resolution",
                stageNames.indexOf("NOISE_PRESSURE_METER") < stageNames.indexOf("QUALITY_CONFLICT_RESOLVER")
            )
            assertTrue(
                "$signal motion evidence must feed smear guard",
                stageNames.indexOf("MOTION_SMEAR_RISK_METER") < stageNames.indexOf("TEMPORAL_MOTION_SMEAR_GUARD")
            )
            assertTrue(
                "$signal output clamp must feed scene complexity bitrate guard",
                stageNames.indexOf("EVIDENCE_CALIBRATED_OUTPUT_CLAMP") <
                    stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD")
            )
            assertTrue(
                "$signal scene complexity guard must remain last before output",
                stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD") == stageNames.indexOf("OUTPUT") - 1
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("QUALITY_EVIDENCE_GATE"))
        assertFalse(rawStageNames.contains("EVIDENCE_CALIBRATED_OUTPUT_CLAMP"))
    }

    @Test
    fun noisyV10RaisesEvidenceGateAndClampsOutputRisk() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val evidenceGate = planFieldFloat(plan, "qualityEvidenceGateFloor")
        val noisePressure = planFieldFloat(plan, "noisePressureEvidenceFloor")
        val rgbInstability = planFieldFloat(plan, "rgbChromaInstabilityEvidenceFloor")
        val haloRisk = planFieldFloat(plan, "edgeHaloRiskEvidenceFloor")
        val outputClamp = planFieldFloat(plan, "evidenceCalibratedOutputClampFloor")

        assertTrue("evidenceGate=$evidenceGate", evidenceGate >= 0.94f)
        assertTrue("noisePressure=$noisePressure", noisePressure >= 0.95f)
        assertTrue("rgbInstability=$rgbInstability", rgbInstability >= 0.95f)
        assertTrue("haloRisk=$haloRisk", haloRisk >= 0.92f)
        assertTrue("outputClamp=$outputClamp", outputClamp >= 0.94f)
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.48f)
        assertTrue(plan.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun movingV10UsesEvidenceToKeepTemporalSafe() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(
                motionScore = 0.72f,
                normalizedNoiseFloor = 0.58f,
                flatRegionStdDev = 10.4f,
                realIsoNoiseFloor = 2_400f
            )
        )
        val smearEvidence = planFieldFloat(plan, "motionSmearEvidenceFloor")

        assertTrue("smearEvidence=$smearEvidence", smearEvidence >= 0.92f)
        assertTrue("motionSafeTemporal=${plan.motionSafeTemporalFloor}", plan.motionSafeTemporalFloor <= 0.54f)
        assertTrue("temporalCeiling=${plan.temporalDenoiseCeiling}", plan.temporalDenoiseCeiling <= 0.54f)
    }

    @Test
    fun cleanV10AllowsDetailOnlyWhenEvidenceConfidenceIsHigh() {
        val clean = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest())
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR))
        val cleanDetailEvidence = planFieldFloat(clean, "cleanDetailEvidenceFloor")
        val noisyDetailEvidence = planFieldFloat(noisy, "cleanDetailEvidenceFloor")
        val cleanClamp = planFieldFloat(clean, "evidenceCalibratedOutputClampFloor")
        val noisyClamp = planFieldFloat(noisy, "evidenceCalibratedOutputClampFloor")

        assertTrue(
            "cleanDetailEvidence=$cleanDetailEvidence noisyDetailEvidence=$noisyDetailEvidence",
            cleanDetailEvidence > noisyDetailEvidence
        )
        assertTrue("cleanDetailEvidence=$cleanDetailEvidence", cleanDetailEvidence >= 0.58f)
        assertTrue("cleanClamp=$cleanClamp noisyClamp=$noisyClamp", cleanClamp <= noisyClamp)
        assertTrue(clean.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun lowResolutionV10KeepsPerceptualResolutionTruth() {
        val lowResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 1024, height = 576)
        )
        val highResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 3840, height = 2160)
        )
        val lowResolutionEvidence = planFieldFloat(lowResolution, "perceptualResolutionEvidenceFloor")
        val highResolutionEvidence = planFieldFloat(highResolution, "perceptualResolutionEvidenceFloor")

        assertTrue(
            "lowResolutionEvidence=$lowResolutionEvidence highResolutionEvidence=$highResolutionEvidence",
            lowResolutionEvidence > highResolutionEvidence
        )
        assertTrue("lowResolutionEvidence=$lowResolutionEvidence", lowResolutionEvidence >= 0.54f)
        assertTrue(lowResolution.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
        assertTrue(highResolution.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun strictEncodedModesIncludeEliteV11ConfidenceMatrixStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("V11_CONFIDENCE_MATRIX"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SENSOR_GAIN_NOISE_CLAMP"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("TEMPORAL_STABILITY_CONFIDENCE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("CHROMA_EDGE_SEPARATION_MAP"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("DETAIL_ARTIFACT_RISK_GATE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("PERCEPTUAL_UPSCALE_TRUTH_GUARD"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("CODEC_DETAIL_PRESERVATION_GATE"))
            assertTrue(
                "$signal V11 confidence must bind evidence before sensor noise profile",
                stageNames.indexOf("V11_CONFIDENCE_MATRIX") > stageNames.indexOf("RGB_CHROMA_INSTABILITY_METER") &&
                    stageNames.indexOf("V11_CONFIDENCE_MATRIX") < stageNames.indexOf("SENSOR_NOISE_PROFILE")
            )
            assertTrue(
                "$signal sensor gain clamp must feed denoise",
                stageNames.indexOf("SENSOR_GAIN_NOISE_CLAMP") < stageNames.indexOf("NOISE_REDUCTION")
            )
            assertTrue(
                "$signal chroma-edge separation must feed edge-aware detail",
                stageNames.indexOf("CHROMA_EDGE_SEPARATION_MAP") < stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal codec detail preservation must feed final bitrate guard",
                stageNames.indexOf("CODEC_DETAIL_PRESERVATION_GATE") <
                    stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD")
            )
            assertTrue(
                "$signal scene complexity guard must remain last before output",
                stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD") == stageNames.indexOf("OUTPUT") - 1
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("V11_CONFIDENCE_MATRIX"))
        assertFalse(rawStageNames.contains("PERCEPTUAL_UPSCALE_TRUTH_GUARD"))
    }

    @Test
    fun severeNoisyV11RaisesGainClampAndArtifactGate() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val confidenceMatrix = planFieldFloat(plan, "v11ConfidenceMatrixFloor")
        val gainClamp = planFieldFloat(plan, "sensorGainNoiseClampFloor")
        val artifactGate = planFieldFloat(plan, "detailArtifactRiskGateFloor")
        val codecGate = planFieldFloat(plan, "codecDetailPreservationGateFloor")

        assertTrue("confidenceMatrix=$confidenceMatrix", confidenceMatrix >= 0.95f)
        assertTrue("gainClamp=$gainClamp", gainClamp >= 0.96f)
        assertTrue("artifactGate=$artifactGate", artifactGate >= 0.94f)
        assertTrue("codecGate=$codecGate", codecGate >= 0.64f)
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.48f)
        assertTrue(plan.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun cleanV11PreservesEdgeDetailOnlyWhenArtifactRiskIsLow() {
        val clean = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest())
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR))
        val cleanChromaEdge = planFieldFloat(clean, "chromaEdgeSeparationFloor")
        val noisyChromaEdge = planFieldFloat(noisy, "chromaEdgeSeparationFloor")
        val cleanArtifactGate = planFieldFloat(clean, "detailArtifactRiskGateFloor")
        val noisyArtifactGate = planFieldFloat(noisy, "detailArtifactRiskGateFloor")
        val cleanCodecGate = planFieldFloat(clean, "codecDetailPreservationGateFloor")

        assertTrue("cleanChromaEdge=$cleanChromaEdge noisyChromaEdge=$noisyChromaEdge", cleanChromaEdge > noisyChromaEdge)
        assertTrue("cleanChromaEdge=$cleanChromaEdge", cleanChromaEdge >= 0.58f)
        assertTrue("cleanArtifactGate=$cleanArtifactGate noisyArtifactGate=$noisyArtifactGate", cleanArtifactGate < noisyArtifactGate)
        assertTrue("cleanCodecGate=$cleanCodecGate", cleanCodecGate >= 0.56f)
        assertTrue(clean.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun fastMotionV11RaisesTemporalStabilityWithoutInflatingTemporalCeiling() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(
                motionScore = 0.72f,
                normalizedNoiseFloor = 0.58f,
                flatRegionStdDev = 10.4f,
                realIsoNoiseFloor = 2_400f
            )
        )
        val temporalStability = planFieldFloat(plan, "temporalStabilityConfidenceFloor")

        assertTrue("temporalStability=$temporalStability", temporalStability >= 0.92f)
        assertTrue("motionSafeTemporal=${plan.motionSafeTemporalFloor}", plan.motionSafeTemporalFloor <= 0.54f)
        assertTrue("temporalCeiling=${plan.temporalDenoiseCeiling}", plan.temporalDenoiseCeiling <= 0.54f)
    }

    @Test
    fun lowResolutionV11RaisesOnlyTruthfulPerceptualUpscaleGuard() {
        val lowResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 1024, height = 576)
        )
        val highResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 3840, height = 2160)
        )
        val lowTruthGuard = planFieldFloat(lowResolution, "perceptualUpscaleTruthGuardFloor")
        val highTruthGuard = planFieldFloat(highResolution, "perceptualUpscaleTruthGuardFloor")

        assertTrue("lowTruthGuard=$lowTruthGuard highTruthGuard=$highTruthGuard", lowTruthGuard > highTruthGuard)
        assertTrue("lowTruthGuard=$lowTruthGuard", lowTruthGuard >= 0.62f)
        assertTrue(lowResolution.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
        assertTrue(highResolution.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun encoderArtifactSuppressionStillAccumulatesNoiseAtHighFrameRate() {
        val clean60 = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                fps = 60,
                strictHighEnd = false,
                normalizedNoiseFloor = 0.02f,
                flatRegionStdDev = 1.0f,
                realIsoNoiseFloor = 160f
            )
        )
        val noisy60 = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                fps = 60,
                strictHighEnd = false,
                normalizedNoiseFloor = 0.92f,
                flatRegionStdDev = 15.0f,
                realIsoNoiseFloor = 42_000f
            )
        )

        assertTrue(
            "clean=${clean60.encoderArtifactSuppressionFloor} noisy=${noisy60.encoderArtifactSuppressionFloor}",
            noisy60.encoderArtifactSuppressionFloor > clean60.encoderArtifactSuppressionFloor + 0.02f
        )
    }

    @Test
    fun sceneComplexityBitrateGuardStillAccumulatesResolutionAtHighFrameRate() {
        val highResolution60 = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                width = 3840,
                height = 2160,
                fps = 60,
                strictHighEnd = false
            )
        )
        val lowResolution60 = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                width = 1920,
                height = 1080,
                fps = 60,
                strictHighEnd = false
            )
        )

        assertTrue(
            "high=${highResolution60.sceneComplexityBitrateGuardFloor} low=${lowResolution60.sceneComplexityBitrateGuardFloor}",
            lowResolution60.sceneComplexityBitrateGuardFloor > highResolution60.sceneComplexityBitrateGuardFloor
        )
    }

    @Test
    fun sceneAdaptiveEncoderGuardStillAccumulatesLogFpsAndResolutionBoosts() {
        val logHighResolution60 = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                signalFamily = VideoImageSignalFamily.LOG,
                width = 3840,
                height = 2160,
                fps = 60,
                strictHighEnd = false
            )
        )
        val logLowResolution60 = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                signalFamily = VideoImageSignalFamily.LOG,
                width = 1920,
                height = 1080,
                fps = 60,
                strictHighEnd = false
            )
        )

        assertTrue(
            "high=${logHighResolution60.sceneAdaptiveEncoderGuardFloor} low=${logLowResolution60.sceneAdaptiveEncoderGuardFloor}",
            logLowResolution60.sceneAdaptiveEncoderGuardFloor > logHighResolution60.sceneAdaptiveEncoderGuardFloor
        )
    }

    @Test
    fun strictEncodedModesIncludeEliteV12SensorLimitQualityStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("V12_SENSOR_LIMIT_QUALITY_GATE"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("READ_NOISE_FLOOR_ESTIMATOR"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("SHADOW_CHROMA_NEUTRALITY_LOCK"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("TEXTURE_EDGE_CONFIDENCE_FUSION"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("MOTION_ADAPTIVE_DETAIL_HOLD"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("PERCEPTUAL_DETAIL_SCALER"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("FINAL_TRUTHFUL_QUALITY_AUDIT"))
            assertTrue(
                "$signal V12 gate must bind V11 evidence before sensor noise profile",
                stageNames.indexOf("V12_SENSOR_LIMIT_QUALITY_GATE") > stageNames.indexOf("V11_CONFIDENCE_MATRIX") &&
                    stageNames.indexOf("V12_SENSOR_LIMIT_QUALITY_GATE") < stageNames.indexOf("SENSOR_NOISE_PROFILE")
            )
            assertTrue(
                "$signal read noise estimator must feed sensor gain clamp",
                stageNames.indexOf("READ_NOISE_FLOOR_ESTIMATOR") < stageNames.indexOf("SENSOR_GAIN_NOISE_CLAMP")
            )
            assertTrue(
                "$signal texture-edge fusion must feed edge-aware detail",
                stageNames.indexOf("TEXTURE_EDGE_CONFIDENCE_FUSION") <
                    stageNames.indexOf("EDGE_AWARE_DETAIL_RECONSTRUCTION")
            )
            assertTrue(
                "$signal final truthful audit must feed scene complexity bitrate guard",
                stageNames.indexOf("FINAL_TRUTHFUL_QUALITY_AUDIT") <
                    stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD")
            )
            assertTrue(
                "$signal scene complexity guard must remain last before output",
                stageNames.indexOf("SCENE_COMPLEXITY_BITRATE_GUARD") == stageNames.indexOf("OUTPUT") - 1
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("V12_SENSOR_LIMIT_QUALITY_GATE"))
        assertFalse(rawStageNames.contains("FINAL_TRUTHFUL_QUALITY_AUDIT"))
    }

    @Test
    fun severeNoisyV12LocksShadowChromaAndSensorLimit() {
        val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val sensorLimitGate = planFieldFloat(plan, "v12SensorLimitQualityGateFloor")
        val readNoise = planFieldFloat(plan, "readNoiseFloorEstimatorFloor")
        val shadowChromaNeutrality = planFieldFloat(plan, "shadowChromaNeutralityLockFloor")
        val finalAudit = planFieldFloat(plan, "finalTruthfulQualityAuditFloor")

        assertTrue("sensorLimitGate=$sensorLimitGate", sensorLimitGate >= 0.96f)
        assertTrue("readNoise=$readNoise", readNoise >= 0.96f)
        assertTrue("shadowChromaNeutrality=$shadowChromaNeutrality", shadowChromaNeutrality >= 0.98f)
        assertTrue("finalAudit=$finalAudit", finalAudit >= 0.95f)
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.48f)
        assertTrue(plan.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun cleanV12RaisesTextureEdgeFusionWithoutRaisingArtifactRisk() {
        val clean = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest())
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR))
        val cleanTextureEdgeFusion = planFieldFloat(clean, "textureEdgeConfidenceFusionFloor")
        val noisyTextureEdgeFusion = planFieldFloat(noisy, "textureEdgeConfidenceFusionFloor")
        val cleanPerceptualDetail = planFieldFloat(clean, "perceptualDetailScalerFloor")
        val cleanArtifactGate = planFieldFloat(clean, "detailArtifactRiskGateFloor")
        val noisyArtifactGate = planFieldFloat(noisy, "detailArtifactRiskGateFloor")

        assertTrue(
            "cleanTextureEdgeFusion=$cleanTextureEdgeFusion noisyTextureEdgeFusion=$noisyTextureEdgeFusion",
            cleanTextureEdgeFusion > noisyTextureEdgeFusion
        )
        assertTrue("cleanTextureEdgeFusion=$cleanTextureEdgeFusion", cleanTextureEdgeFusion >= 0.60f)
        assertTrue("cleanPerceptualDetail=$cleanPerceptualDetail", cleanPerceptualDetail >= 0.58f)
        assertTrue("cleanArtifactGate=$cleanArtifactGate noisyArtifactGate=$noisyArtifactGate", cleanArtifactGate < noisyArtifactGate)
        assertTrue(clean.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun fastMotionV12RaisesMotionAdaptiveHoldWithoutInflatingTemporalCeiling() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(
                motionScore = 0.72f,
                normalizedNoiseFloor = 0.58f,
                flatRegionStdDev = 10.4f,
                realIsoNoiseFloor = 2_400f
            )
        )
        val motionAdaptiveHold = planFieldFloat(plan, "motionAdaptiveDetailHoldFloor")

        assertTrue("motionAdaptiveHold=$motionAdaptiveHold", motionAdaptiveHold >= 0.92f)
        assertTrue("motionSafeTemporal=${plan.motionSafeTemporalFloor}", plan.motionSafeTemporalFloor <= 0.54f)
        assertTrue("temporalCeiling=${plan.temporalDenoiseCeiling}", plan.temporalDenoiseCeiling <= 0.54f)
    }

    @Test
    fun lowResolutionV12ScalesPerceptualDetailWithoutChangingResolutionTruth() {
        val lowResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 1024, height = 576)
        )
        val highResolution = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(width = 3840, height = 2160)
        )
        val lowPerceptualDetail = planFieldFloat(lowResolution, "perceptualDetailScalerFloor")
        val highPerceptualDetail = planFieldFloat(highResolution, "perceptualDetailScalerFloor")

        assertTrue(
            "lowPerceptualDetail=$lowPerceptualDetail highPerceptualDetail=$highPerceptualDetail",
            lowPerceptualDetail > highPerceptualDetail
        )
        assertTrue("lowPerceptualDetail=$lowPerceptualDetail", lowPerceptualDetail >= 0.64f)
        assertTrue(lowResolution.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
        assertTrue(highResolution.nativeResolutionTruth == "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED")
    }

    @Test
    fun strictEncodedModesIncludeEliteV13SharpnessDetailStages() {
        listOf(
            VideoImageSignalFamily.SDR,
            VideoImageSignalFamily.HDR,
            VideoImageSignalFamily.LOG
        ).forEach { signal ->
            val plan = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = signal))
            val stageNames = plan.pipelineOrder.map { it.name }

            assertTrue("$signal stages=$stageNames", stageNames.contains("V13_EDGE_MICRO_CONTRAST_LIFT"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("FINE_TEXTURE_DETAIL_BOOST"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("HALO_SAFE_SHARPENING_LIFT"))
            assertTrue("$signal stages=$stageNames", stageNames.contains("RECORDING_PREVIEW_DETAIL_PARITY"))
            assertTrue(
                "$signal V13 edge lift must run after texture-edge fusion",
                stageNames.indexOf("V13_EDGE_MICRO_CONTRAST_LIFT") >
                    stageNames.indexOf("TEXTURE_EDGE_CONFIDENCE_FUSION")
            )
            assertTrue(
                "$signal halo-safe sharpening must feed final truthful audit",
                stageNames.indexOf("HALO_SAFE_SHARPENING_LIFT") <
                    stageNames.indexOf("FINAL_TRUTHFUL_QUALITY_AUDIT")
            )
        }

        val rawStageNames = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        ).pipelineOrder.map { it.name }
        assertFalse(rawStageNames.contains("V13_EDGE_MICRO_CONTRAST_LIFT"))
        assertFalse(rawStageNames.contains("HALO_SAFE_SHARPENING_LIFT"))
    }

    @Test
    fun cleanV13RaisesDetailLiftWhileNoisySceneStaysHaloSafe() {
        val clean = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest())
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR))
        val cleanMicroContrast = planFieldFloat(clean, "edgeMicroContrastLiftFloor")
        val noisyMicroContrast = planFieldFloat(noisy, "edgeMicroContrastLiftFloor")
        val cleanFineTexture = planFieldFloat(clean, "fineTextureDetailBoostFloor")
        val noisyFineTexture = planFieldFloat(noisy, "fineTextureDetailBoostFloor")
        val cleanHaloSafeLift = planFieldFloat(clean, "haloSafeSharpeningLiftFloor")
        val noisyHaloSafeLift = planFieldFloat(noisy, "haloSafeSharpeningLiftFloor")

        assertTrue("cleanMicroContrast=$cleanMicroContrast noisyMicroContrast=$noisyMicroContrast", cleanMicroContrast > noisyMicroContrast)
        assertTrue("cleanFineTexture=$cleanFineTexture noisyFineTexture=$noisyFineTexture", cleanFineTexture > noisyFineTexture)
        assertTrue("cleanMicroContrast=$cleanMicroContrast", cleanMicroContrast >= 0.60f)
        assertTrue("cleanFineTexture=$cleanFineTexture", cleanFineTexture >= 0.58f)
        assertTrue("cleanHaloSafeLift=$cleanHaloSafeLift", cleanHaloSafeLift >= 0.52f)
        assertTrue("noisyHaloSafeLift=$noisyHaloSafeLift", noisyHaloSafeLift <= 0.24f)
        assertTrue("noisy sharpeningCeiling=${noisy.sharpeningCeiling}", noisy.sharpeningCeiling <= 0.62f)
    }

    @Test
    fun previewAndRecordingV13UseComparableDetailParity() {
        val preview = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest().copy(recording = false))
        val recording = VideoImageQualityPipelinePolicy.resolve(cleanDetailedSdrRequest().copy(recording = true))
        val previewParity = planFieldFloat(preview, "recordingPreviewDetailParityFloor")
        val recordingParity = planFieldFloat(recording, "recordingPreviewDetailParityFloor")

        assertTrue("previewParity=$previewParity", previewParity >= 0.62f)
        assertTrue("recordingParity=$recordingParity", recordingParity >= 0.62f)
        assertTrue(
            "previewParity=$previewParity recordingParity=$recordingParity",
            kotlin.math.abs(previewParity - recordingParity) <= 0.12f
        )
    }

    @Test
    fun highNoiseRecordingV20RaisesZeroGrainDenoiseAndSuppressesUnsafeSharpening() {
        val preview = VideoImageQualityPipelinePolicy.resolve(zeroGrainStressRequest(recording = false))
        val recording = VideoImageQualityPipelinePolicy.resolve(zeroGrainStressRequest(recording = true))
        val movingRecording = VideoImageQualityPipelinePolicy.resolve(
            zeroGrainStressRequest(recording = true).copy(motionScore = 0.46f)
        )

        assertTrue(
            "recordingTemporal=${recording.temporalDenoiseFloor} previewTemporal=${preview.temporalDenoiseFloor}",
            recording.temporalDenoiseFloor > preview.temporalDenoiseFloor + 0.20f
        )
        assertTrue(
            "recordingSpatial=${recording.spatialDenoiseFloor} previewSpatial=${preview.spatialDenoiseFloor}",
            recording.spatialDenoiseFloor > preview.spatialDenoiseFloor &&
                recording.spatialDenoiseFloor <= 0.78f
        )
        assertTrue(
            "recordingChroma=${recording.chromaDenoiseFloor} previewChroma=${preview.chromaDenoiseFloor}",
            recording.chromaDenoiseFloor > preview.chromaDenoiseFloor &&
                recording.chromaDenoiseFloor <= 0.88f
        )
        assertTrue("recordingChroma=${recording.chromaDenoiseFloor}", recording.chromaDenoiseFloor in 0.74f..0.88f)
        assertTrue(
            "recordingSharpening=${recording.sharpeningCeiling} previewSharpening=${preview.sharpeningCeiling}",
            recording.sharpeningCeiling < preview.sharpeningCeiling - 0.10f
        )
        assertTrue("recordingSharpening=${recording.sharpeningCeiling}", recording.sharpeningCeiling <= 0.34f)
        assertTrue("haloSafe=${recording.haloSafeSharpeningLiftFloor}", recording.haloSafeSharpeningLiftFloor <= 0.10f)
        assertTrue(
            "noisyEdgeSuppressor=${recording.noisyEdgeSharpeningSuppressorFloor}",
            recording.noisyEdgeSharpeningSuppressorFloor >= 0.96f
        )
        assertTrue("movingTemporal=${movingRecording.temporalDenoiseFloor}", movingRecording.temporalDenoiseFloor <= 0.54f)
        assertTrue("movingSmearGuard=${movingRecording.temporalMotionSmearGuardFloor}", movingRecording.temporalMotionSmearGuardFloor >= 0.72f)
    }

    @Test
    fun cleanStaticV20KeepsMicrodetailBehindNoiseMotionAndArtifactGuards() {
        val protected = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                motionScore = 0.01f,
                normalizedNoiseFloor = 0.015f,
                flatRegionStdDev = 0.9f,
                realIsoNoiseFloor = 100f,
                edgeDensity = 0.36f,
                perceptualSharpness = 0.84f
            )
        )
        val unprotected = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                motionScore = 0.24f,
                normalizedNoiseFloor = 0.19f,
                flatRegionStdDev = 4.1f,
                realIsoNoiseFloor = 2_600f,
                edgeDensity = 0.36f,
                perceptualSharpness = 0.84f
            )
        )

        assertTrue(
            "protectedMicro=${protected.edgeMicroContrastLiftFloor} unprotectedMicro=${unprotected.edgeMicroContrastLiftFloor}",
            protected.edgeMicroContrastLiftFloor > unprotected.edgeMicroContrastLiftFloor
        )
        assertTrue(
            "protectedTexture=${protected.fineTextureDetailBoostFloor} unprotectedTexture=${unprotected.fineTextureDetailBoostFloor}",
            protected.fineTextureDetailBoostFloor > unprotected.fineTextureDetailBoostFloor
        )
        assertTrue(
            "protectedHaloLift=${protected.haloSafeSharpeningLiftFloor} unprotectedHaloLift=${unprotected.haloSafeSharpeningLiftFloor}",
            protected.haloSafeSharpeningLiftFloor > unprotected.haloSafeSharpeningLiftFloor
        )
        assertTrue("protectedMicro=${protected.edgeMicroContrastLiftFloor}", protected.edgeMicroContrastLiftFloor >= 0.64f)
        assertTrue("protectedTexture=${protected.fineTextureDetailBoostFloor}", protected.fineTextureDetailBoostFloor >= 0.62f)
        assertTrue("protectedHaloLift=${protected.haloSafeSharpeningLiftFloor}", protected.haloSafeSharpeningLiftFloor >= 0.56f)
        assertTrue("artifactGate=${protected.detailArtifactRiskGateFloor}", protected.detailArtifactRiskGateFloor <= 0.78f)
        assertTrue("haloRisk=${protected.edgeHaloRiskEvidenceFloor}", protected.edgeHaloRiskEvidenceFloor <= 0.82f)
        assertTrue(
            "haloLift=${protected.haloSafeSharpeningLiftFloor} sharpening=${protected.sharpeningCeiling}",
            protected.haloSafeSharpeningLiftFloor <= protected.sharpeningCeiling
        )
        assertTrue("smearGuard=${protected.temporalMotionSmearGuardFloor}", protected.temporalMotionSmearGuardFloor <= 0.48f)
    }

    @Test
    fun cleanDetailedSdrAllowsMoreNaturalDetailThanNoisyHdr() {
        val noisy = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val clean = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest()
        )

        assertTrue(clean.spatialDenoiseFloor < noisy.spatialDenoiseFloor)
        assertTrue(clean.chromaDenoiseFloor < noisy.chromaDenoiseFloor)
        assertTrue(clean.sharpeningFloor > noisy.sharpeningFloor)
        assertTrue(clean.sharpeningCeiling > noisy.sharpeningCeiling)
        assertTrue(clean.contrastFloor >= 0.92f)
    }

    @Test
    fun cleanStrictRecordingRaisesPerceptualDetailFloorAcrossEncodedModes() {
        val cleanDetailRequest = VideoImageQualityPipelineRequest(
            signalFamily = VideoImageSignalFamily.SDR,
            recording = true,
            width = 3840,
            height = 2160,
            fps = 60,
            estimatedLux = 160f,
            motionScore = 0.02f,
            normalizedNoiseFloor = 0.02f,
            flatRegionStdDev = 1.0f,
            realIsoNoiseFloor = 120f,
            edgeDensity = 0.36f,
            perceptualSharpness = 0.82f,
            dynamicRangeScore = 0.46f,
            highlightRatio = 0.04f,
            strictHighEnd = true
        )

        val sdr = VideoImageQualityPipelinePolicy.resolve(cleanDetailRequest)
        val hdr = VideoImageQualityPipelinePolicy.resolve(cleanDetailRequest.copy(signalFamily = VideoImageSignalFamily.HDR))
        val log = VideoImageQualityPipelinePolicy.resolve(cleanDetailRequest.copy(signalFamily = VideoImageSignalFamily.LOG))

        assertTrue("sdr.sharpeningFloor=${sdr.sharpeningFloor}", sdr.sharpeningFloor >= 0.30f)
        assertTrue("hdr.sharpeningFloor=${hdr.sharpeningFloor}", hdr.sharpeningFloor >= 0.30f)
        assertTrue("log.sharpeningFloor=${log.sharpeningFloor}", log.sharpeningFloor >= 0.22f)
        assertTrue("sdr.sharpeningCeiling=${sdr.sharpeningCeiling}", sdr.sharpeningCeiling >= 0.52f)
        assertTrue("sdr.sharpeningCeiling=${sdr.sharpeningCeiling}", sdr.sharpeningCeiling <= 0.82f)
        assertTrue("hdr.sharpeningCeiling=${hdr.sharpeningCeiling}", hdr.sharpeningCeiling >= 0.40f)
        assertTrue("hdr.sharpeningCeiling=${hdr.sharpeningCeiling}", hdr.sharpeningCeiling <= 0.70f)
    }

    @Test
    fun cleanStrictLogPreviewAndRecordingRaiseDetailWithoutBecomingHdr() {
        val cleanLogRequest = VideoImageQualityPipelineRequest(
            signalFamily = VideoImageSignalFamily.LOG,
            recording = false,
            width = 3840,
            height = 2160,
            fps = 30,
            estimatedLux = 170f,
            motionScore = 0.02f,
            normalizedNoiseFloor = 0.025f,
            flatRegionStdDev = 1.1f,
            realIsoNoiseFloor = 140f,
            edgeDensity = 0.38f,
            perceptualSharpness = 0.86f,
            dynamicRangeScore = 0.50f,
            highlightRatio = 0.05f,
            strictHighEnd = true
        )

        val preview = VideoImageQualityPipelinePolicy.resolve(cleanLogRequest)
        val recording = VideoImageQualityPipelinePolicy.resolve(cleanLogRequest.copy(recording = true))

        assertFalse(preview.hdrMergeRequired)
        assertFalse(recording.hdrMergeRequired)
        assertTrue(preview.logTransferRequired)
        assertTrue(recording.logTransferRequired)
        assertEquals(0f, preview.videoHdrFusionCeiling, 0.0001f)
        assertEquals(0f, recording.videoHdrFusionCeiling, 0.0001f)
        assertTrue("preview.sharpeningFloor=${preview.sharpeningFloor}", preview.sharpeningFloor >= 0.40f)
        assertTrue("preview.sharpeningCeiling=${preview.sharpeningCeiling}", preview.sharpeningCeiling >= 0.46f)
        assertTrue("recording.sharpeningFloor=${recording.sharpeningFloor}", recording.sharpeningFloor >= 0.46f)
        assertTrue("recording.sharpeningCeiling=${recording.sharpeningCeiling}", recording.sharpeningCeiling >= 0.60f)
        assertTrue("logStrength=${preview.logStrengthFloor}", preview.logStrengthFloor >= 0.88f)
        assertTrue("contrast=${preview.contrastCeiling}", preview.contrastCeiling <= 0.78f)
        assertTrue("saturation=${preview.saturationCeiling}", preview.saturationCeiling <= 0.84f)
    }

    @Test
    fun cleanStrictNormalPreviewAndRecordingRaiseEliteSdrDetailWithoutHdrOrLog() {
        val cleanNormalRequest = VideoImageQualityPipelineRequest(
            signalFamily = VideoImageSignalFamily.SDR,
            recording = false,
            width = 3840,
            height = 2160,
            fps = 30,
            estimatedLux = 190f,
            motionScore = 0.02f,
            normalizedNoiseFloor = 0.02f,
            flatRegionStdDev = 1.0f,
            realIsoNoiseFloor = 120f,
            edgeDensity = 0.40f,
            perceptualSharpness = 0.88f,
            dynamicRangeScore = 0.42f,
            highlightRatio = 0.04f,
            strictHighEnd = true
        )

        val preview = VideoImageQualityPipelinePolicy.resolve(cleanNormalRequest)
        val recording = VideoImageQualityPipelinePolicy.resolve(cleanNormalRequest.copy(recording = true))

        assertFalse(preview.hdrMergeRequired)
        assertFalse(recording.hdrMergeRequired)
        assertFalse(preview.logTransferRequired)
        assertFalse(recording.logTransferRequired)
        assertEquals(0f, preview.videoHdrFusionCeiling, 0.0001f)
        assertEquals(0f, recording.videoHdrFusionCeiling, 0.0001f)
        assertTrue("preview.sharpeningFloor=${preview.sharpeningFloor}", preview.sharpeningFloor >= 0.56f)
        assertTrue("preview.sharpeningCeiling=${preview.sharpeningCeiling}", preview.sharpeningCeiling >= 0.84f)
        assertTrue("recording.sharpeningFloor=${recording.sharpeningFloor}", recording.sharpeningFloor >= 0.62f)
        assertTrue("recording.sharpeningCeiling=${recording.sharpeningCeiling}", recording.sharpeningCeiling >= 0.88f)
        assertTrue("recording detail parity=${recording.recordingPreviewDetailParityFloor}", recording.recordingPreviewDetailParityFloor >= 0.67f)
        assertTrue("contrast=${preview.contrastFloor}", preview.contrastFloor >= 0.94f)
        assertTrue("saturation=${preview.saturationFloor}", preview.saturationFloor >= 0.94f)
    }

    @Test
    fun noisyStrictSdrActivatesTenBitNoiseLockAndDetailRecoveryWithoutHdrOrLog() {
        val sdr = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.SDR)
        )
        val hdr = VideoImageQualityPipelinePolicy.resolve(noisyHdrRequest())
        val sdrStageNames = sdr.pipelineOrder.map { it.name }
        val hdrStageNames = hdr.pipelineOrder.map { it.name }

        assertTrue(sdrStageNames.contains("STRICT_SDR_10BIT_NOISE_LOCK"))
        assertTrue(sdrStageNames.contains("STRICT_SDR_10BIT_DETAIL_RECOVERY"))
        assertFalse(hdrStageNames.contains("STRICT_SDR_10BIT_NOISE_LOCK"))
        assertFalse(hdrStageNames.contains("STRICT_SDR_10BIT_DETAIL_RECOVERY"))
        assertFalse(sdr.hdrMergeRequired)
        assertFalse(sdr.logTransferRequired)
        assertEquals(0f, sdr.videoHdrFusionCeiling, 0.0001f)
        assertTrue("temporal=${sdr.temporalDenoiseFloor}", sdr.temporalDenoiseFloor in 0.55f..0.78f)
        assertTrue("spatial=${sdr.spatialDenoiseFloor}", sdr.spatialDenoiseFloor in 0.52f..0.82f)
        assertTrue("chroma=${sdr.chromaDenoiseFloor}", sdr.chromaDenoiseFloor in 0.62f..0.92f)
        assertTrue("adaptiveDetail=${sdr.adaptiveDetailReconstructionFloor}", sdr.adaptiveDetailReconstructionFloor >= 0.45f)
        assertTrue("honestDetail=${sdr.resolutionHonestDetailBoostFloor}", sdr.resolutionHonestDetailBoostFloor >= 0.45f)
        assertTrue("parity=${sdr.recordingPreviewDetailParityFloor}", sdr.recordingPreviewDetailParityFloor >= 0.54f)
    }

    @Test
    fun moderateRgbGrainAndSoftStrictSdrEngagesEliteRecoveryWithoutHdrOrLog() {
        val plan = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                normalizedNoiseFloor = 0.23f,
                flatRegionStdDev = 5.5f,
                realIsoNoiseFloor = 4_000f,
                estimatedLux = 60f,
                edgeDensity = 0.08f,
                perceptualSharpness = 0.36f,
                motionScore = 0.08f
            )
        )
        val stageNames = plan.pipelineOrder.map { it.name }

        assertTrue(stageNames.contains("STRICT_SDR_10BIT_NOISE_LOCK"))
        assertTrue(stageNames.contains("STRICT_SDR_10BIT_DETAIL_RECOVERY"))
        assertFalse(plan.hdrMergeRequired)
        assertFalse(plan.logTransferRequired)
        assertEquals(0f, plan.videoHdrFusionCeiling, 0.0001f)
        assertTrue("temporal=${plan.temporalDenoiseFloor}", plan.temporalDenoiseFloor in 0.30f..0.52f)
        assertTrue("spatial=${plan.spatialDenoiseFloor}", plan.spatialDenoiseFloor in 0.36f..0.68f)
        assertTrue("chroma=${plan.chromaDenoiseFloor}", plan.chromaDenoiseFloor in 0.48f..0.78f)
        assertTrue("sharpeningFloor=${plan.sharpeningFloor}", plan.sharpeningFloor >= 0.58f)
        assertTrue("adaptiveDetail=${plan.adaptiveDetailReconstructionFloor}", plan.adaptiveDetailReconstructionFloor >= 0.50f)
        assertTrue("aliasSafeDetail=${plan.aliasSafeDetailReconstructionFloor}", plan.aliasSafeDetailReconstructionFloor >= 0.50f)
        assertTrue("honestDetail=${plan.resolutionHonestDetailBoostFloor}", plan.resolutionHonestDetailBoostFloor >= 0.50f)
    }

    @Test
    fun `extreme detail clean sdr raises detail floors beyond focused baseline`() {
        val focused = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                edgeDensity = 0.34f,
                normalizedNoiseFloor = 0.025f,
                perceptualSharpness = 0.78f,
                videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
            )
        )
        val extreme = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                edgeDensity = 0.34f,
                normalizedNoiseFloor = 0.025f,
                perceptualSharpness = 0.78f,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
            )
        )

        assertTrue(extreme.haloSafeSharpeningLiftFloor > focused.haloSafeSharpeningLiftFloor)
        assertTrue(extreme.edgeMicroContrastLiftFloor > focused.edgeMicroContrastLiftFloor)
        assertTrue(extreme.fineTextureDetailBoostFloor > focused.fineTextureDetailBoostFloor)
        assertTrue(extreme.recordingPreviewDetailParityFloor >= focused.recordingPreviewDetailParityFloor)
        assertEquals(focused.spatialDenoiseFloor, extreme.spatialDenoiseFloor, 0.0001f)
        assertEquals(focused.chromaDenoiseFloor, extreme.chromaDenoiseFloor, 0.0001f)
    }

    @Test
    fun `extreme detail noisy sdr raises cleanup while keeping halo safe sharpening bounded`() {
        val focused = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                edgeDensity = 0.12f,
                normalizedNoiseFloor = 0.76f,
                flatRegionStdDev = 14.0f,
                realIsoNoiseFloor = 36_000f,
                perceptualSharpness = 0.34f,
                videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
            )
        )
        val extreme = VideoImageQualityPipelinePolicy.resolve(
            cleanDetailedSdrRequest().copy(
                edgeDensity = 0.12f,
                normalizedNoiseFloor = 0.76f,
                flatRegionStdDev = 14.0f,
                realIsoNoiseFloor = 36_000f,
                perceptualSharpness = 0.34f,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
            )
        )

        assertTrue(extreme.spatialDenoiseFloor >= focused.spatialDenoiseFloor)
        assertTrue(extreme.chromaDenoiseFloor >= focused.chromaDenoiseFloor)
        assertTrue(extreme.spatialDenoiseFloor in 0.70f..0.82f)
        assertTrue(extreme.chromaDenoiseFloor in 0.80f..0.92f)
        assertTrue(extreme.haloSafeSharpeningLiftFloor <= 0.58f)
    }

    @Test
    fun `raw ignores extreme detail profile and keeps raw pipeline floors`() {
        val rawDefault = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
        )
        val rawExtreme = VideoImageQualityPipelinePolicy.resolve(
            noisyHdrRequest().copy(
                signalFamily = VideoImageSignalFamily.RAW,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
            )
        )

        assertEquals(rawDefault, rawExtreme)
    }

    private fun noisyHdrRequest(): VideoImageQualityPipelineRequest =
        VideoImageQualityPipelineRequest(
            signalFamily = VideoImageSignalFamily.HDR,
            recording = true,
            width = 3840,
            height = 2160,
            fps = 60,
            estimatedLux = 3.2f,
            motionScore = 0.18f,
            normalizedNoiseFloor = 0.93f,
            flatRegionStdDev = 15.5f,
            realIsoNoiseFloor = 42_000f,
            edgeDensity = 0.08f,
            perceptualSharpness = 0.20f,
            dynamicRangeScore = 0.90f,
            highlightRatio = 0.21f,
            strictHighEnd = true
        )

    private fun cleanDetailedSdrRequest(): VideoImageQualityPipelineRequest =
        VideoImageQualityPipelineRequest(
            signalFamily = VideoImageSignalFamily.SDR,
            recording = true,
            width = 3840,
            height = 2160,
            fps = 30,
            estimatedLux = 180f,
            motionScore = 0.03f,
            normalizedNoiseFloor = 0.025f,
            flatRegionStdDev = 1.2f,
            realIsoNoiseFloor = 160f,
            edgeDensity = 0.34f,
            perceptualSharpness = 0.78f,
            dynamicRangeScore = 0.34f,
            highlightRatio = 0.04f,
            strictHighEnd = true
        )

    private fun zeroGrainStressRequest(recording: Boolean): VideoImageQualityPipelineRequest =
        VideoImageQualityPipelineRequest(
            signalFamily = VideoImageSignalFamily.HDR,
            recording = recording,
            width = 3840,
            height = 2160,
            fps = 60,
            estimatedLux = 5f,
            motionScore = 0.16f,
            normalizedNoiseFloor = 0.82f,
            flatRegionStdDev = 13.8f,
            realIsoNoiseFloor = 24_000f,
            edgeDensity = 0.16f,
            perceptualSharpness = 0.34f,
            dynamicRangeScore = 0.72f,
            highlightRatio = 0.12f,
            strictHighEnd = false
        )

    private fun planFieldFloat(plan: VideoImageQualityPipelinePlan, fieldName: String): Float {
        val pattern = Regex("$fieldName=([0-9.]+)")
        val match = pattern.find(plan.toString())
            ?: error("Missing $fieldName in $plan")
        return match.groupValues[1].toFloat()
    }
}
