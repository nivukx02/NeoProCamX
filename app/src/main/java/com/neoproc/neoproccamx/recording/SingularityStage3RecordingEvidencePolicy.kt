package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityStage3EvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage3EvidenceMapper
import com.neoproc.neoproccamx.quality.SingularityStage3PipelineEvidence
import com.neoproc.neoproccamx.quality.SingularityStage3SignalState
import com.neoproc.neoproccamx.quality.SingularityStage3StageSignal
import java.util.Locale

internal object SingularityStage3RecordingEvidencePolicy {
    fun fromQualityRequest(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): SingularityStage3EvidenceBundle {
        val metadata = request.detailMetadata + runtimeMetadata
        val context = Stage3Context(request, metadata)
        return SingularityStage3EvidenceMapper.map(
            SingularityStage3PipelineEvidence(
                recordingId = request.recordingId,
                outputMode = request.selectedProfile,
                proprietaryProfilePreserved = metadata.boolean("proprietaryProfilePreserved", default = true),
                vendorHdrCertificationClaimed =
                    metadata.boolean("vendorHdrCertification") ||
                        metadata.boolean("vendorCertifiedHdr10Plus") ||
                        metadata.boolean("vendorCertifiedDolbyVision") ||
                        metadata.boolean("dolbyRpuWritten") ||
                        metadata.boolean("officialHdr10PlusMetadataWritten"),
                previewRecordingParityStatus = context.previewRecordingParityStatus,
                previewRecordingParityFailureCodes = context.previewRecordingParityFailureCodes,
                stageSignals = context.stageSignals(),
                evidenceReferences = context.baseReferences
            )
        )
    }

    fun metadataFor(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): Map<String, String> =
        fromQualityRequest(request, runtimeMetadata).toMetadata()

    private class Stage3Context(
        val request: RecordingQualityEvidenceRequest,
        val metadata: Map<String, String>
    ) {
        val baseReferences: List<String> = listOf(
            "recordingQualityEvidenceRequest",
            "stage3ExistingPipelineMap",
            "recordingId=${request.recordingId}"
        )

        val rawActive: Boolean =
            request.mode == EliteVideoPipelineMode.RAW ||
                request.rawSensorOriginal ||
                request.npv10PayloadFormat.contains("RAW", ignoreCase = true) ||
                metadata.boolean("rawPrivateMasterUnmodified") ||
                metadata.boolean("rawSensorCapture")

        val npv10Active: Boolean = request.mode == EliteVideoPipelineMode.NPV10

        val dynamicRangeActive: Boolean =
            request.mode == EliteVideoPipelineMode.HDR ||
                request.normalizedHdrVersion.isNeoDynamicRangeProfile() ||
                request.activeTransport.contains("PQ", ignoreCase = true) ||
                request.activeTransport.contains("BT.2100", ignoreCase = true) ||
                metadata.clean("selectedColorScience").orEmpty().contains("NDR", ignoreCase = true) ||
                metadata.clean("selectedColorScience").orEmpty().contains("NIVUKX", ignoreCase = true) ||
                request.enhancementUniformSignature.contains("NDR", ignoreCase = true) ||
                request.enhancementUniformSignature.contains("NIVUKX", ignoreCase = true)

        val logActive: Boolean =
            request.mode == EliteVideoPipelineMode.LOG ||
                request.normalizedLogVersion.contains("N-Log", ignoreCase = true) ||
                request.selectedProfile.contains("log", ignoreCase = true) ||
                metadata.clean("selectedColorScience").orEmpty().contains("log", ignoreCase = true)

        val processedGpuProven: Boolean =
            request.gpuProcessingValidated &&
                request.processedRecordingFramesValidated &&
                request.gpuProcessingPath.isNotBlank()

        val runtimeGpuFrames: Long = maxOf(
            metadata.long("vulkanEncodedProcessedFrames"),
            metadata.long("offscreenEglSubmittedFrames"),
            metadata.long("vulkanSessionImportedFrames")
        )

        val averageGpuNs: Long =
            metadata.double("vulkanAverageComputeMs")
                .takeIf { it > 0.0 }
                ?.let { (it * 1_000_000.0).toLong() }
                ?: 0L

        val cinemaDebayerFrames: Long = maxOf(
            metadata.long("stage3CinemaDebayeredFrames"),
            metadata.long("cinemaDebayeredFrames"),
            if (
                rawActive &&
                request.gpuProcessingPath.contains("VULKAN_DEMOSAIC", ignoreCase = true) &&
                request.gpuProcessingValidated
            ) {
                request.actualFrameCount
            } else {
                0L
            }
        )

        val cinemaDebayerProven: Boolean =
            rawActive &&
                cinemaDebayerFrames > 0L &&
                (
                    request.gpuProcessingValidated ||
                        metadata.boolean("stage3CinemaDebayerPipelineActive") ||
                        metadata.boolean("cinemaDebayerPipelineActive")
                    )

        val rawMonitorPreviewOnly: Boolean =
            metadata.clean("rawMonitorPipeline").orEmpty()
                .contains("PREVIEW", ignoreCase = true)

        val subjectMaskPresent: Boolean =
            metadata.boolean("stage3AiSubjectMaskPresent") ||
                metadata.boolean("subjectMaskPresent") ||
                request.enhancementSubjectDetailGain > 0f

        val aiObserved: Boolean =
            metadata.boolean("stage3AiFrameResultObserved") ||
                metadata.boolean("aiFrameProcessingActive") ||
                metadata.boolean("faceDetect")

        val faceCount: Int = metadata.int("stage3AiFaceCount")
        val eyeLandmarkCount: Int = metadata.int("stage3AiEyeLandmarkCount")
        val primarySubjectPresent: Boolean =
            metadata.boolean("stage3AiPrimarySubjectPresent") ||
                metadata.double("stage3AiPrimarySubjectConfidence") > 0.0
        val depthPresent: Boolean = metadata.boolean("stage3AiDepthMapPresent")
        val depthConfidencePresent: Boolean =
            metadata.boolean("stage3AiDepthConfidenceAvailable") ||
                metadata.double("stage3AiDepthConfidenceMean") > 0.0
        val segmentationPresent: Boolean =
            metadata.boolean("stage3AiSegmentationPresent") ||
                subjectMaskPresent
        val semanticTemporalStable: Boolean =
            metadata.boolean("stage3AiSemanticTemporalStabilized") ||
                metadata.boolean("stage3AiDepthTemporallyRefined") ||
                metadata.double("stage3AiPrimarySubjectStability") > 0.0

        val detailSignature: String =
            metadata.clean("detailSignature")
                ?: metadata.clean("detailSharpeningSignature")
                ?: ""
        val trueResolutionClaim: String =
            metadata.clean("detailTrueResolutionClaim") ?: ""
        val detailEvidencePresent: Boolean =
            detailSignature.isNotBlank() ||
                metadata.clean("detailProcessingPath").orEmpty().isNotBlank() ||
                request.enhancementDetailProfile.isNotBlank() ||
                request.enhancementSharpening > 0f

        val hasNoiseMetrics: Boolean =
            request.noiseMetrics.chromaNoise > 0f ||
                request.noiseMetrics.lumaNoise > 0f ||
                request.noiseMetrics.temporalInstability > 0f ||
                request.noiseMetrics.frameToFrameRgbVariance > 0f ||
                metadata.int("singularitySensorNoiseProfilePairs") > 0

        val temporalHistoryFrames: Long =
            maxOf(
                metadata.long("stage3TemporalHistoryFrames"),
                metadata.long("temporalHistoryFrames"),
                metadata.long("npv10ExportTemporalHistoryFrames")
            )

        val multiLensStreamProven: Boolean =
            metadata.boolean("singularityPhysicalCameraStreamProven") ||
                metadata.boolean("physicalCameraStreamProven")

        val selectedColorSpace: String =
            metadata.clean("selectedColorSpace")
                ?: metadata.clean("paritySelectedColorSpace")
                ?: request.primaries

        val selectedColorScience: String =
            metadata.clean("selectedColorScience")
                ?: metadata.clean("paritySelectedColorScience")
                ?: request.selectedProfile

        val manualColorReady: Boolean =
            metadata.boolean("manualColorSelectionReady") ||
                metadata.boolean("colorSpaceSelectedByUser") ||
                metadata.boolean("colorScienceSelectedByUser") ||
                metadata.clean("colorSelectionSource")
                    .orEmpty()
                    .contains("USER", ignoreCase = true)

        val previewRecordingParityStatus: String =
            metadata.clean("previewRecordingParityStatus")
                ?: metadata.clean("stage3PreviewRecordingParityStatus")
                ?: if (request.previewQualitySignature == request.recordingQualitySignature) "PASS" else "FAIL"

        val previewRecordingParityFailureCodes: String =
            metadata.clean("previewRecordingParityFailureCodes")
                ?: metadata.clean("parityFailureCodes")
                ?: ""

        val previewRecordingParityPass: Boolean =
            previewRecordingParityStatus.equals("PASS", ignoreCase = true) &&
                request.previewQualitySignature.isNotBlank() &&
                request.previewQualitySignature == request.recordingQualitySignature

        val cleanCreativeProfile: Boolean =
            metadata.clean("lookProfile")
                .orEmpty()
                .contains("clean", ignoreCase = true) ||
                request.enhancementDetailProfile.equals("EXTREME_DETAIL", ignoreCase = true) ||
                metadata.clean("selectedDetailProfile")
                    .orEmpty()
                    .contains("EXTREME_DETAIL", ignoreCase = true)

        fun stageSignals(): Map<Int, SingularityStage3StageSignal> = buildMap {
            putRawReconstruction()
            putOpticalIdentity()
            putExposureAuthority()
            putDynamicRangeAuthority()
            putNoiseAuthority()
            putMotionAuthority()
            putMultiLensAuthority()
            putSubjectDepthAuthority()
            putTextureAuthority()
            putResolutionAuthority()
            putColorAuthority()
            putToneCinemaAuthority()
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putRawReconstruction() {
            val rawPackRefs = refs("raw=${rawActive}", "payload=${request.npv10PayloadFormat}", "sourceBitDepth=${request.sourceBitDepth}")
            put(32, if (rawActive && request.sourceBitDepth >= 10) {
                proven("RAW_PRIVATE_MASTER", "raw_packing=${request.npv10PayloadFormat}", rawPackRefs)
            } else {
                disabledMode("RAW reconstruction is inactive for ${request.mode}.", rawPackRefs)
            })
            put(33, when {
                cinemaDebayerProven -> proven("VULKAN_CINEMA_DEBAYER", "linearizedRawFrames=$cinemaDebayerFrames", refs("cinemaDebayerFrames=$cinemaDebayerFrames"))
                rawActive -> fallback("RAW_PRIVATE_MASTER", "RAW_PACKED_PRIVATE_MASTER", "RAW master remains packed/unmodified; recording does not claim destructive linearization.", rawPackRefs)
                else -> disabledMode("RAW unpack is inactive for ${request.mode}.", rawPackRefs)
            })
            (34..42).forEach { id ->
                put(id, when {
                    cinemaDebayerProven -> proven("VULKAN_CINEMA_DEBAYER", "debayeredFrames=$cinemaDebayerFrames", refs("cinemaDebayerFrames=$cinemaDebayerFrames"))
                    rawActive -> sourceOnly("VULKAN_CINEMA_DEBAYER_SOURCE", "Cinema debayer shader/source is available, but runtime dispatch was not proven.", rawPackRefs)
                    else -> disabledMode("RAW reconstruction stage is inactive for ${request.mode}.", rawPackRefs)
                })
            }
            put(43, if (rawActive && selectedColorScience.isNotBlank()) proven("RAW_COLOR_POLICY", selectedColorScience, refs("selectedColorScience=$selectedColorScience")) else disabledMode("Spectral normalization is inactive without RAW/color evidence.", rawPackRefs))
            put(44, if (rawActive && selectedColorSpace.isNotBlank()) proven("RAW_COLOR_POLICY", selectedColorSpace, refs("selectedColorSpace=$selectedColorSpace")) else disabledMode("Camera RGB transform is inactive without RAW/color evidence.", rawPackRefs))
            put(45, if (rawActive && request.sourceBitDepth >= 10) proven("RAW_RANGE_POLICY", "sourceBitDepth=${request.sourceBitDepth}", rawPackRefs) else disabledMode("Linear range sanitization is inactive without RAW bit-depth evidence.", rawPackRefs))
            put(46, if (rawActive && detailEvidencePresent) proven("VIDEO_DETAIL_POLICY", detailSignature.ifBlank { request.enhancementDetailProfile }, refs("detailSignature=$detailSignature")) else disabledMode("RAW reconstruction detail confidence is inactive without RAW detail evidence.", rawPackRefs))
            put(47, when {
                cinemaDebayerProven -> proven("VULKAN_CINEMA_DEBAYER", "linearRgbFrames=$cinemaDebayerFrames", refs("cinemaDebayerFrames=$cinemaDebayerFrames"))
                rawActive && rawMonitorPreviewOnly -> identity("RAW_PRIVATE_MASTER", "raw_monitor_preview_only_master_unmodified", rawPackRefs)
                rawActive -> fallback("RAW_PRIVATE_MASTER", "RAW_RECONSTRUCTION_MONITOR_NOT_PROVEN", "RAW private master is preserved; reconstructed RGB output is not claimed for the recording.", rawPackRefs)
                else -> disabledMode("Linear RGB reconstruction output is inactive for ${request.mode}.", rawPackRefs)
            })
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putOpticalIdentity() {
            val lensEvidence = metadata.clean("stage3LensProfile")
                ?: metadata.clean("lensProfile")
                ?: metadata.clean("singularityCameraId")
                ?: ""
            put(48, if (lensEvidence.isNotBlank()) proven("CAMERA2_LENS_PROFILE", lensEvidence, refs("lens=$lensEvidence")) else unavailable("Lens profile selection is not proven.", refs()))
            put(49, if (metadata.clean("stage3FocalApertureContext").orEmpty().isNotBlank()) {
                proven("CAMERA2_LENS_METADATA", metadata.clean("stage3FocalApertureContext").orEmpty(), refs())
            } else if (lensEvidence.isNotBlank()) {
                fallback("CAMERA2_LENS_METADATA", "FOCAL_APERTURE_CONTEXT_PARTIAL", "Camera identity is known, but focal/aperture context is incomplete.", refs("lens=$lensEvidence"))
            } else {
                unavailable("Focal/aperture context is not proven.", refs())
            })
            (50..63).forEach { id ->
                put(id, when {
                    metadata.boolean("stage3OpticalCorrectionProven") -> proven("VULKAN_OPTICAL_CORRECTION", "opticalCorrectionProven=true", refs())
                    processedGpuProven && detailEvidencePresent -> fallback(
                        "VULKAN_GENERIC_DETAIL_PASS",
                        "DEDICATED_OPTICAL_STAGE_NOT_PROVEN",
                        "Generic GPU/detail processing is active, but a dedicated optical correction dispatch is not proven.",
                        refs("gpuPath=${request.gpuProcessingPath}")
                    )
                    metadata.boolean("stage3OpticalShaderSourceAvailable") -> sourceOnly("VULKAN_OPTICAL_SHADER_SOURCE", "Optical shader/source exists without runtime dispatch proof.", refs())
                    else -> unavailable("Optical stage is not proven by runtime metadata.", refs())
                })
            }
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putExposureAuthority() {
            val exposureRefs = refs(
                "hdr=${request.normalizedHdrVersion}",
                "log=${request.normalizedLogVersion}",
                "transport=${request.activeTransport}"
            )
            put(64, if (metadata.boolean("stage3FlickerDetectionProven")) proven("CAMERA2_EXPOSURE_POLICY", "flickerDetection=true", exposureRefs) else unavailable("Flicker detection is not proven.", exposureRefs))
            put(65, if (request.width > 0 && request.height > 0 && request.fps > 0) proven("CAMERA2_EXPOSURE_POLICY", "${request.width}x${request.height}@${request.fps}", exposureRefs) else failed("EXPOSURE_FEASIBILITY_INVALID", "Exposure feasibility is invalid without resolution and frame-rate evidence.", exposureRefs))
            put(66, proven("CAMERA2_EXPOSURE_POLICY", "baseExposureMode=${request.mode}", exposureRefs))
            put(67, if (dynamicRangeActive || request.enhancementUniformSignature.contains("shadowBoost", ignoreCase = true)) proven("NEO_EXPOSURE_POLICY", "shadowSchedule=${dynamicRangeActive}", exposureRefs) else disabledMode("Shadow exposure schedule is inactive for this mode.", exposureRefs))
            put(68, if (dynamicRangeActive || request.enhancementUniformSignature.contains("highlight", ignoreCase = true)) proven("NEO_EXPOSURE_POLICY", "highlightSchedule=${dynamicRangeActive}", exposureRefs) else disabledMode("Highlight exposure schedule is inactive for this mode.", exposureRefs))
            put(69, if (metadata.boolean("stage3MultiExposureSynchronizationProven") || metadata.boolean("dualGainFusionEnabled")) proven("CAMERA2_MULTI_EXPOSURE", "multiExposureSynchronization=true", exposureRefs) else fallback("CAMERA2_SINGLE_EXPOSURE", "MULTI_EXPOSURE_SYNC_NOT_PROVEN", "Multi-exposure synchronization is not proven; single-frame or dual-gain-safe path is recorded.", exposureRefs))
            put(70, proven("EXPOSURE_NORMALIZATION_POLICY", "sourceBitDepth=${request.sourceBitDepth};encoded=${request.encodedBitDepth}", exposureRefs))
            put(71, if (metadata.double("motion") > 0.0 || metadata.double("stage3SceneMotionScore") > 0.0) proven("SCENE_MOTION_POLICY", "motion=${metadata.clean("motion") ?: metadata.clean("stage3SceneMotionScore").orEmpty()}", exposureRefs) else unavailable("Motion exposure delta is not proven.", exposureRefs))
            put(72, if (metadata.double("highlight") > 0.0 || metadata.double("highlightRatio") > 0.0 || dynamicRangeActive) proven("HIGHLIGHT_MASK_POLICY", "highlightProtection=true", exposureRefs) else unavailable("Clipped highlight mask is not proven.", exposureRefs))
            put(73, if (metadata.double("shadowBoost") > 0.0 || request.enhancementSpatialDenoise > 0f || dynamicRangeActive) proven("SHADOW_MASK_POLICY", "shadowProtection=true", exposureRefs) else unavailable("Deep shadow mask is not proven.", exposureRefs))
            put(74, if (subjectMaskPresent || faceCount > 0 || dynamicRangeActive) proven("SKIN_HIGHLIGHT_POLICY", "skinHighlightProtection=true", exposureRefs) else unavailable("Skin highlight protection is not proven.", exposureRefs))
            put(75, if (metadata.boolean("stage3NeonHighlightProtectionProven")) proven("NEON_HIGHLIGHT_POLICY", "neonHighlightProtection=true", exposureRefs) else unavailable("Neon highlight protection is not proven.", exposureRefs))
            put(76, if (metadata.double("avgLuma") > 0.0 || metadata.double("dynamicRange") > 0.0 || request.actualFrameCount > 0) proven("EXPOSURE_CONFIDENCE_POLICY", "frames=${request.actualFrameCount}", exposureRefs) else unavailable("Exposure confidence is not proven.", exposureRefs))
            put(77, proven("EXPOSURE_DOWNGRADE_POLICY", metadata.clean("transportFallbackReason") ?: "NONE", exposureRefs))
            put(78, if (previewRecordingParityStatus.isNotBlank()) proven("EXPOSURE_STABILITY_POLICY", previewRecordingParityStatus, exposureRefs) else unavailable("Exposure pumping guard is not proven.", exposureRefs))
            put(79, proven("EXPOSURE_AUTHORITY", "mode=${request.mode};dynamic=$dynamicRangeActive;log=$logActive", exposureRefs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putDynamicRangeAuthority() {
            val refs = refs(
                "profile=${request.selectedProfile}",
                "hdr=${request.normalizedHdrVersion}",
                "proprietary=${metadata.clean("proprietaryProfilePreserved") ?: "true"}",
                "vendor=false"
            )
            put(80, proven("NEO_DYNAMIC_RANGE_POLICY", "baseExposureReconstruction=true", refs))
            put(81, if (dynamicRangeActive || request.enhancementSpatialDenoise > 0f) proven("NEO_DYNAMIC_RANGE_POLICY", "shadowReconstruction=true", refs) else disabledMode("Shadow exposure reconstruction is inactive for this mode.", refs))
            put(82, if (dynamicRangeActive || request.enhancementUniformSignature.contains("highlight", ignoreCase = true)) proven("NEO_DYNAMIC_RANGE_POLICY", "highlightReconstruction=true", refs) else disabledMode("Highlight exposure reconstruction is inactive for this mode.", refs))
            put(83, if (metadata.boolean("stage3ExposureAlignmentProven") || metadata.boolean("dualGainMotionMaskEnabled")) proven("EXPOSURE_ALIGNMENT_POLICY", "alignment=true", refs) else if (dynamicRangeActive) fallback("EXPOSURE_ALIGNMENT_POLICY", "ALIGNMENT_CONFIDENCE_NOT_PROVEN", "Dynamic range path is active, but exposure alignment confidence is not independently proven.", refs) else disabledMode("Exposure alignment is inactive for this mode.", refs))
            put(84, if (dynamicRangeActive || metadata.boolean("dualGainFusionEnabled")) proven("NEO_FUSION_WEIGHT_POLICY", "fusionWeights=true", refs) else disabledMode("Exposure fusion weights are inactive for this mode.", refs))
            put(85, if (metadata.boolean("dualGainMotionMaskEnabled") || temporalHistoryFrames > 0L) proven("GHOST_SAFE_FUSION_POLICY", "ghostSafeFusion=true", refs) else if (dynamicRangeActive) fallback("GHOST_SAFE_FUSION_POLICY", "GHOST_SAFE_WEIGHTS_NOT_PROVEN", "Ghost-safe fusion is reduced because motion/temporal proof is incomplete.", refs) else disabledMode("Ghost-safe fusion is inactive for this mode.", refs))
            put(86, if (dynamicRangeActive || metadata.double("highlight") > 0.0) proven("HIGHLIGHT_RECOVERY_POLICY", "highlightDetailRecovery=true", refs) else disabledMode("Highlight detail recovery is inactive for this mode.", refs))
            put(87, if (dynamicRangeActive || request.transfer.contains("PQ", ignoreCase = true)) proven("HIGHLIGHT_ROLLOFF_POLICY", request.transfer, refs) else disabledMode("Highlight shoulder is inactive for this mode.", refs))
            put(88, if (dynamicRangeActive || request.enhancementChromaDenoise > 0f) proven("SHADOW_CHROMA_POLICY", "chromaDenoise=${request.enhancementChromaDenoise}", refs) else disabledMode("Shadow chroma floor is inactive for this mode.", refs))
            put(89, if (dynamicRangeActive || detailEvidencePresent) proven("SHADOW_TEXTURE_POLICY", detailSignature.ifBlank { request.enhancementDetailProfile }, refs) else disabledMode("Shadow texture preservation is inactive for this mode.", refs))
            put(90, if (metadata.double("dynamicRange") > 0.0 || dynamicRangeActive) proven("LOCAL_DYNAMIC_RANGE_POLICY", "zoneMap=true", refs) else unavailable("Local dynamic range zone map is not proven.", refs))
            put(91, if (metadata.double("dynamicRange") > 0.0 || dynamicRangeActive) proven("SCENE_DYNAMIC_RANGE_ESTIMATE", metadata.clean("dynamicRange") ?: request.selectedProfile, refs) else unavailable("Global dynamic range estimate is not proven.", refs))
            put(92, if (request.encodedBitDepth >= 10 || dynamicRangeActive) proven("TONE_HEADROOM_POLICY", "encodedBitDepth=${request.encodedBitDepth}", refs) else unavailable("Tone headroom estimate is not proven.", refs))
            put(93, if (metadata.boolean("stage3FusionArtifactQcPassed")) proven("FUSION_ARTIFACT_QC", "fusionArtifactQc=pass", refs) else unavailable("Fusion artifact QC is not proven.", refs))
            put(94, proven("DYNAMIC_RANGE_FALLBACK_POLICY", metadata.clean("transportFallbackReason") ?: "NONE", refs))
            put(95, if (dynamicRangeActive || logActive || request.encodedBitDepth > 0) proven("SCENE_LINEAR_OUTPUT_POLICY", "mode=${request.mode};transfer=${request.transfer}", refs) else unavailable("Scene-linear dynamic range output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putNoiseAuthority() {
            val refs = refs(
                "luma=${request.noiseMetrics.lumaNoise}",
                "chroma=${request.noiseMetrics.chromaNoise}",
                "temporal=${request.noiseMetrics.temporalInstability}",
                "frameVariance=${request.noiseMetrics.frameToFrameRgbVariance}"
            )
            put(96, if (metadata.int("singularitySensorNoiseProfilePairs") > 0 || request.enhancementUniformSignature.contains("shotNoise", ignoreCase = true)) proven("SENSOR_NOISE_POLICY", "shotNoise=true", refs) else unavailable("Shot-noise model is not proven.", refs))
            put(97, if (metadata.int("singularitySensorNoiseProfilePairs") > 0 || request.enhancementUniformSignature.contains("readNoise", ignoreCase = true)) proven("SENSOR_NOISE_POLICY", "readNoise=true", refs) else unavailable("Read-noise model is not proven.", refs))
            put(98, if (metadata.clean("singularityFixedPatternNoiseProfile").orEmpty().isNotBlank()) proven("SENSOR_NOISE_POLICY", metadata.clean("singularityFixedPatternNoiseProfile").orEmpty(), refs) else unavailable("Fixed-pattern residual is not proven.", refs))
            put(99, if (hasNoiseMetrics || metadata.clean("singularityRowColumnBandingModel").orEmpty().isNotBlank()) proven("BANDING_RISK_POLICY", "bandingRisk=true", refs) else unavailable("Banding risk map is not proven.", refs))
            put(100, if (request.noiseMetrics.chromaNoise > 0f || request.enhancementChromaDenoise > 0f) proven("CHROMA_NOISE_POLICY", "chromaDenoise=${request.enhancementChromaDenoise}", refs) else unavailable("Chroma noise model is not proven.", refs))
            put(101, if (request.noiseMetrics.frameToFrameRgbVariance > 0f || request.noiseMetrics.chromaNoise > 0f) proven("CHROMA_CRAWL_POLICY", "rgbVariance=${request.noiseMetrics.frameToFrameRgbVariance}", refs) else unavailable("Chroma crawl risk map is not proven.", refs))
            put(102, if (request.noiseMetrics.temporalInstability > 0f || request.enhancementTemporalBlend > 0f) proven("TEMPORAL_NOISE_POLICY", "temporalBlend=${request.enhancementTemporalBlend}", refs) else unavailable("Temporal noise model is not proven.", refs))
            put(103, if (hasNoiseMetrics && detailEvidencePresent) proven("TEXTURE_NOISE_CLASSIFIER", "detail=$detailSignature", refs) else unavailable("Texture/noise classifier is not proven.", refs))
            put(104, if (subjectMaskPresent || detailEvidencePresent) proven("PROTECTED_TEXTURE_POLICY", "subjectMask=$subjectMaskPresent", refs) else unavailable("Protected texture masks are not proven.", refs))
            put(105, if (request.enhancementSpatialDenoise > 0f || request.enhancementChromaDenoise > 0f) proven("DENOISE_STRENGTH_POLICY", "spatial=${request.enhancementSpatialDenoise};chroma=${request.enhancementChromaDenoise}", refs) else unavailable("Denoise strength map is not proven.", refs))
            put(106, if (request.enhancementSpatialDenoise > 0f) proven("VULKAN_REALTIME_ENHANCE", "spatialDenoise=${request.enhancementSpatialDenoise}", refs) else unavailable("Spatial noise reduction is not proven.", refs))
            put(107, when {
                temporalHistoryFrames > 0L -> proven("TEMPORAL_NOISE_REDUCTION", "historyFrames=$temporalHistoryFrames", refs)
                request.enhancementTemporalBlend > 0f -> sourceOnly("TEMPORAL_NOISE_SOURCE", "Temporal blend intent exists, but previous-frame runtime history is not proven.", refs)
                else -> unavailable("Temporal noise reduction is not proven.", refs)
            })
            put(108, if (request.enhancementSpatialDenoise > 0f || request.enhancementChromaDenoise > 0f) proven("BANDING_SUPPRESSION_POLICY", "denoise=true", refs) else unavailable("Banding suppression is not proven.", refs))
            put(109, if (request.enhancementChromaDenoise > 0f) proven("CHROMA_STABILIZATION_POLICY", "chromaDenoise=${request.enhancementChromaDenoise}", refs) else unavailable("Chroma stabilization is not proven.", refs))
            put(110, if (detailEvidencePresent) proven("DETAIL_PRESERVATION_QC_POLICY", detailSignature.ifBlank { request.enhancementDetailProfile }, refs) else unavailable("Detail preservation QC is not proven.", refs))
            put(111, if (request.enhancementSpatialDenoise > 0f || request.enhancementChromaDenoise > 0f || hasNoiseMetrics) proven("NOISE_AUTHORITY", "noiseAuthorityOutput=true", refs) else unavailable("Noise authority output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putMotionAuthority() {
            val refs = refs("imu=${metadata.clean("singularityImuSampleCount").orEmpty()}", "history=$temporalHistoryFrames")
            val imuSamples = metadata.int("singularityImuSampleCount")
            put(112, if (imuSamples > 0) proven("IMU_INGEST", "imuSamples=$imuSamples", refs) else unavailable("Gyro samples are not proven.", refs))
            put(113, if (metadata.int("stage3AccelerometerSampleCount") > 0) proven("IMU_INGEST", "accelerometerSamples=${metadata.int("stage3AccelerometerSampleCount")}", refs) else if (imuSamples > 0) fallback("IMU_INGEST", "ACCELEROMETER_AGGREGATE_NOT_SPLIT", "IMU samples are present but accelerometer-specific count is not split.", refs) else unavailable("Accelerometer samples are not proven.", refs))
            put(114, if (imuSamples > 0 && metadata.long("singularityCaptureTimestampNs") > 0L) proven("IMU_TIMESTAMP_POLICY", "captureTimestamp=${metadata.long("singularityCaptureTimestampNs")}", refs) else unavailable("Sensor/IMU timestamp alignment is not proven.", refs))
            put(115, if (metadata.long("singularityRollingShutterSkewNs") > 0L) proven("ROLLING_SHUTTER_POLICY", "skewNs=${metadata.long("singularityRollingShutterSkewNs")}", refs) else unavailable("Rolling-shutter motion model is not proven.", refs))
            put(116, if (metadata.boolean("stage3OpticalFlowProven")) proven("OPTICAL_FLOW_RUNTIME", "opticalFlow=true", refs) else sourceOnly("OPTICAL_FLOW_SOURCE", "Optical-flow source/manifest exists, but neural/runtime flow proof is unavailable.", refs))
            put(117, if (metadata.boolean("stage3OpticalFlowProven")) proven("OPTICAL_FLOW_RUNTIME", "denseSparseFlow=true", refs) else sourceOnly("OPTICAL_FLOW_SOURCE", "Dense/sparse flow solution is not proven at runtime.", refs))
            put(118, if (metadata.boolean("stage3OpticalFlowProven") && imuSamples > 0) proven("IMU_FLOW_FUSION", "imuFlowFusion=true", refs) else unavailable("IMU/flow fusion is not proven.", refs))
            put(119, if (metadata.boolean("stage3MotionOcclusionMapProven")) proven("MOTION_OCCLUSION_POLICY", "occlusion=true", refs) else unavailable("Motion occlusion map is not proven.", refs))
            put(120, if (primarySubjectPresent || metadata.double("motion") > 0.0) fallback("SUBJECT_MOTION_POLICY", "SUBJECT_MOTION_WITHOUT_FLOW", "Subject/motion evidence exists, but dense optical-flow proof is unavailable.", refs) else unavailable("Subject motion map is not proven.", refs))
            put(121, if (request.actualFrameCount > 0) proven("FRAME_ANCHOR_POLICY", "frames=${request.actualFrameCount}", refs) else unavailable("Reference frame anchor is not proven.", refs))
            put(122, if (metadata.boolean("stage3SceneCutDetectionProven")) proven("SCENE_CUT_POLICY", "sceneCut=true", refs) else unavailable("Scene-cut detection is not proven.", refs))
            put(123, if (temporalHistoryFrames > 0L) proven("TEMPORAL_FUSION_POLICY", "historyFrames=$temporalHistoryFrames", refs) else sourceOnly("TEMPORAL_FUSION_SOURCE", "Temporal fusion weights are not proven without previous-frame history.", refs))
            put(124, if (metadata.boolean("stage3GhostRejectionMaskProven")) proven("GHOST_REJECTION_POLICY", "ghostMask=true", refs) else unavailable("Ghost rejection mask is not proven.", refs))
            put(125, if (temporalHistoryFrames > 0L) proven("TEMPORAL_ACCUMULATION", "historyFrames=$temporalHistoryFrames", refs) else sourceOnly("TEMPORAL_ACCUMULATION_SOURCE", "Temporal accumulation is not proven by runtime history evidence.", refs))
            put(126, if (metadata.boolean("stage3TemporalShimmerQcPassed")) proven("TEMPORAL_SHIMMER_QC", "temporalShimmer=pass", refs) else unavailable("Temporal shimmer QC is not proven.", refs))
            put(127, if (imuSamples > 0 || temporalHistoryFrames > 0L || metadata.double("motion") > 0.0) proven("MOTION_AUTHORITY", "motionAuthorityOutput=true", refs) else unavailable("Motion authority output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putMultiLensAuthority() {
            val refs = refs("physicalCameraStream=$multiLensStreamProven", "physicalId=${metadata.clean("singularityPhysicalCameraId").orEmpty()}")
            put(128, if (metadata.clean("singularityPhysicalCameraId").orEmpty().isNotBlank() || multiLensStreamProven) proven("CAMERA2_LOGICAL_CAMERA", "logicalCamera=true", refs) else unavailable("Logical camera capability is not proven.", refs))
            put(129, if (multiLensStreamProven) proven("CAMERA2_PHYSICAL_STREAM", "physicalStream=true", refs) else unavailable("Physical stream feasibility is not proven.", refs))
            put(130, if (metadata.clean("singularityPhysicalCameraId").orEmpty().isNotBlank()) proven("CAMERA2_PHYSICAL_LENS", metadata.clean("singularityPhysicalCameraId").orEmpty(), refs) else unavailable("Physical lens identity is not proven.", refs))
            (131..142).forEach { id ->
                put(id, when {
                    metadata.boolean("stage3MultiLensFusionProven") -> proven("MULTI_LENS_FUSION_RUNTIME", "multiLensFusion=true", refs)
                    multiLensStreamProven && id in 131..135 -> fallback("CAMERA2_PHYSICAL_STREAM", "MULTI_LENS_CALIBRATION_PARTIAL", "Physical stream is proven, but full multi-lens calibration/fusion proof is incomplete.", refs)
                    else -> unavailable("Multi-lens stage is not proven on this device/session.", refs)
                })
            }
            put(143, if (multiLensStreamProven) proven("MULTI_LENS_OUTPUT_POLICY", "primaryOrFused=physical", refs) else fallback("PRIMARY_LENS_OUTPUT_POLICY", "PRIMARY_LENS_ONLY", "Multi-lens fusion is unavailable; primary lens output is recorded visibly.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putSubjectDepthAuthority() {
            val refs = refs(
                "aiObserved=$aiObserved",
                "faces=$faceCount",
                "subjectMask=$subjectMaskPresent",
                "depth=$depthPresent"
            )
            put(144, if (aiObserved || faceCount > 0) proven("AI_FACE_TRACKER", "faces=$faceCount", refs) else unavailable("Face detection is not proven.", refs))
            put(145, if (eyeLandmarkCount > 0 || faceCount > 0) proven("AI_FACE_LANDMARKS", "eyeLandmarks=$eyeLandmarkCount", refs) else unavailable("Face landmarks are not proven.", refs))
            put(146, if (faceCount > 0 || subjectMaskPresent) proven("AI_SKIN_CONFIDENCE", "skinMaskProxy=true", refs) else unavailable("Skin confidence mask is not proven.", refs))
            put(147, if (segmentationPresent) proven("AI_SEGMENTATION", "subjectMask=$subjectMaskPresent", refs) else unavailable("Subject segmentation is not proven.", refs))
            put(148, if (depthPresent) proven("AI_DEPTH_ESTIMATOR", "depthMap=true", refs) else unavailable("Depth inference is not proven.", refs))
            put(149, if (depthConfidencePresent) proven("AI_DEPTH_ESTIMATOR", "depthConfidence=true", refs) else unavailable("Depth confidence is not proven.", refs))
            put(150, if (primarySubjectPresent) proven("AI_SUBJECT_AWARENESS", "primarySubject=true", refs) else unavailable("Foreground priority map is not proven.", refs))
            put(151, if (subjectMaskPresent && depthPresent) proven("AI_SUBJECT_DEPTH", "backgroundProtection=true", refs) else unavailable("Background protection map is not proven.", refs))
            put(152, if (metadata.boolean("stage3TextRegionMaskProven")) proven("AI_TEXT_REGION", "textMask=true", refs) else unavailable("Text region mask is not proven.", refs))
            put(153, if (metadata.boolean("stage3SkyRegionMaskProven")) proven("AI_SKY_REGION", "skyMask=true", refs) else unavailable("Sky region mask is not proven by the current segmentation labels.", refs))
            put(154, if (metadata.boolean("stage3FoliageRegionMaskProven")) proven("AI_FOLIAGE_REGION", "foliageMask=true", refs) else unavailable("Foliage region mask is not proven by the current segmentation labels.", refs))
            put(155, if (metadata.boolean("stage3NeonRegionMaskProven")) proven("AI_NEON_REGION", "neonMask=true", refs) else unavailable("Neon region mask is not proven.", refs))
            put(156, if (metadata.boolean("stage3HairFabricRegionMaskProven")) proven("AI_HAIR_FABRIC_REGION", "hairFabric=true", refs) else if (subjectMaskPresent) fallback("AI_SEGMENTATION", "HAIR_FABRIC_CLASS_NOT_SPLIT", "Subject mask exists, but hair/fabric class evidence is not split.", refs) else unavailable("Hair/fabric region mask is not proven.", refs))
            put(157, if (metadata.boolean("stage3ProtectedNeutralMaskProven")) proven("AI_PROTECTED_NEUTRAL", "neutralMask=true", refs) else unavailable("Protected neutral mask is not proven.", refs))
            put(158, if (semanticTemporalStable) proven("AI_SEMANTIC_STABILITY", "semanticTemporalStable=true", refs) else unavailable("Semantic mask temporal stabilization is not proven.", refs))
            put(159, if (aiObserved || segmentationPresent || depthPresent || primarySubjectPresent) proven("AI_SUBJECT_DEPTH_AUTHORITY", "subjectDepthOutput=true", refs) else unavailable("Subject/depth authority output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putTextureAuthority() {
            val refs = refs("detailSignature=$detailSignature", "detailProfile=${request.enhancementDetailProfile}")
            put(160, if (metadata.double("detailTextureConfidence") > 0.0 || detailEvidencePresent) proven("VIDEO_DETAIL_POLICY", "microtexture=true", refs) else unavailable("Microtexture confidence map is not proven.", refs))
            put(161, if (metadata.double("detailEdgeConfidence") > 0.0 || detailEvidencePresent) proven("VIDEO_DETAIL_POLICY", "edgeConfidence=true", refs) else unavailable("Edge confidence map is not proven.", refs))
            put(162, if (metadata.boolean("detailSkinTextureGuard") || subjectMaskPresent) proven("VIDEO_DETAIL_POLICY", "skinTextureGuard=true", refs) else unavailable("Skin texture guard is not proven.", refs))
            put(163, if (metadata.boolean("stage3HairTextureGuardProven") || subjectMaskPresent) fallback("VIDEO_DETAIL_POLICY", "HAIR_TEXTURE_CLASS_PROXY", "Subject mask provides texture protection, but hair-specific guard is not independently proven.", refs) else unavailable("Hair texture guard is not proven.", refs))
            put(164, if (metadata.boolean("stage3FabricTextureGuardProven") || detailEvidencePresent) fallback("VIDEO_DETAIL_POLICY", "FABRIC_TEXTURE_CLASS_PROXY", "Detail policy protects texture generally; fabric-specific guard is not independently proven.", refs) else unavailable("Fabric texture guard is not proven.", refs))
            put(165, if (metadata.boolean("stage3FoliageTextureGuardProven")) proven("VIDEO_DETAIL_POLICY", "foliageTexture=true", refs) else unavailable("Foliage texture guard is not proven.", refs))
            put(166, if (trueResolutionClaim.isNotBlank() || metadata.boolean("stage3TextLegibilityGuardProven")) proven("VIDEO_DETAIL_POLICY", trueResolutionClaim.ifBlank { "textLegibility=true" }, refs) else unavailable("Text legibility guard is not proven.", refs))
            put(167, if (metadata.boolean("detailMoireAliasGuard") || metadata.double("detailHaloRisk") > 0.0) proven("VIDEO_DETAIL_POLICY", "clippedEdgeGuard=true", refs) else unavailable("Clipped edge guard is not proven.", refs))
            put(168, if (metadata.boolean("detailNoisyShadowSharpeningSuppression") || request.enhancementSpatialDenoise > 0f) proven("VIDEO_DETAIL_POLICY", "noiseSharpeningRejected=true", refs) else unavailable("Noise sharpening rejection is not proven.", refs))
            put(169, if (processedGpuProven && detailEvidencePresent) proven("VULKAN_DETAIL_PASS", "localContrastPyramid=true", refs, gpuNs = averageGpuNs) else if (detailEvidencePresent) fallback("VIDEO_DETAIL_POLICY", "LOCAL_CONTRAST_POLICY_ONLY", "Detail policy exists, but GPU local-contrast dispatch proof is incomplete.", refs) else unavailable("Local contrast pyramid is not proven.", refs))
            put(170, if (request.enhancementSharpening > 0f || metadata.double("detailMicroContrast") > 0.0) proven("VIDEO_DETAIL_POLICY", "midtoneDetail=true", refs) else unavailable("Midtone detail recovery is not proven.", refs))
            put(171, if (metadata.double("detailReconstruction") > 0.0) proven("VIDEO_DETAIL_POLICY", "deconvolutionConfidence=true", refs) else unavailable("Confidence-gated deconvolution is not proven.", refs))
            put(172, if (metadata.double("detailHaloSuppression") > 0.0 || metadata.boolean("detailEdgeAwareOnly")) proven("VIDEO_DETAIL_POLICY", "haloClamp=true", refs) else unavailable("Edge halo clamp is not proven.", refs))
            put(173, if (metadata.boolean("detailMoireAliasGuard") || metadata.double("detailHaloSuppression") > 0.0) proven("VIDEO_DETAIL_POLICY", "ringingSuppression=true", refs) else unavailable("Ringing suppression is not proven.", refs))
            put(174, if (temporalHistoryFrames > 0L) proven("TEXTURE_TEMPORAL_QC", "historyFrames=$temporalHistoryFrames", refs) else sourceOnly("TEXTURE_TEMPORAL_QC_SOURCE", "Texture temporal consistency QC is not proven without history evidence.", refs))
            put(175, if (detailEvidencePresent || request.enhancementSharpening > 0f) proven("TEXTURE_AUTHORITY", "textureAuthorityOutput=true", refs) else unavailable("Texture authority output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putResolutionAuthority() {
            val refs = refs("selected=${request.selectedResolution}", "actual=${request.actualResolution}", "truth=$trueResolutionClaim")
            val resolutionMismatch = request.selectedResolution.isNotBlank() &&
                request.actualResolution.isNotBlank() &&
                request.selectedResolution != request.actualResolution
            put(176, if (trueResolutionClaim.isNotBlank() || !resolutionMismatch) proven("RESOLUTION_TRUTH_POLICY", trueResolutionClaim.ifBlank { request.actualResolution }, refs) else unavailable("Native resolution confidence is not proven.", refs))
            put(177, if (resolutionMismatch) proven("RESOLUTION_POLICY", "liftRequired=${request.actualResolution}->${request.selectedResolution}", refs) else identity("RESOLUTION_POLICY", "native_resolution_no_lift_required", refs))
            put(178, if (temporalHistoryFrames > 0L) proven("TEMPORAL_RESOLUTION_POLICY", "historyFrames=$temporalHistoryFrames", refs) else unavailable("Temporal resolution feasibility is not proven.", refs))
            put(179, if (temporalHistoryFrames > 0L) proven("TEMPORAL_RESOLUTION_INPUTS", "historyFrames=$temporalHistoryFrames", refs) else unavailable("Temporal resolution inputs are not proven.", refs))
            put(180, if (metadata.boolean("stage3TemporalResolutionLiftProven")) proven("TEMPORAL_RESOLUTION_LIFT", "temporalLift=true", refs) else unavailable("Temporal resolution lift is not proven.", refs))
            put(181, if (metadata.boolean("stage3NeuralResolutionCapabilityProven")) proven("NEURAL_RESOLUTION_ENGINE", "neuralResolutionCapability=true", refs) else unavailable("Neural resolution capability is unavailable; SNAR is not proven.", refs))
            (182..188).forEach { id ->
                put(id, if (metadata.boolean("stage3NeuralResolutionCapabilityProven")) proven("NEURAL_RESOLUTION_ENGINE", "neuralResolution=true", refs) else unavailable("Neural resolution stage is unavailable; no SNAR/model/runtime proof exists.", refs))
            }
            put(189, if (resolutionMismatch) fallback("SPATIAL_UPSCALE_POLICY", "VALIDATED_SPATIAL_UPSCALE_FALLBACK", "Resolution mismatch uses a visible spatial fallback; no neural true-resolution claim is made.", refs) else identity("SPATIAL_UPSCALE_POLICY", "native_resolution_identity", refs))
            put(190, if (detailEvidencePresent) proven("OUTPUT_ACUITY_QC_POLICY", detailSignature.ifBlank { trueResolutionClaim }, refs) else unavailable("Output acuity QC is not proven.", refs))
            put(191, if (trueResolutionClaim.isNotBlank() || detailEvidencePresent || resolutionMismatch) proven("RESOLUTION_AUTHORITY", "resolutionAuthorityOutput=true", refs) else unavailable("Resolution authority output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putColorAuthority() {
            val refs = refs(
                "space=$selectedColorSpace",
                "science=$selectedColorScience",
                "primaries=${request.primaries}",
                "matrix=${request.matrix}"
            )
            put(192, if (request.matrix.isNotBlank() || request.primaries.isNotBlank()) proven("COLOR_MATRIX_POLICY", "${request.primaries}|${request.matrix}", refs) else unavailable("Camera color matrix is not proven.", refs))
            put(193, if (manualColorReady || metadata.clean("manualWhiteBalanceKelvin").orEmpty().isNotBlank()) proven("WHITE_BALANCE_STABILITY_POLICY", "manualColorReady=$manualColorReady", refs) else fallback("WHITE_BALANCE_STABILITY_POLICY", "WB_STABILITY_METADATA_PARTIAL", "White balance is represented by color-profile metadata; capture-result stability proof is incomplete.", refs))
            put(194, if (selectedColorSpace.isNotBlank()) proven("NEUTRAL_AXIS_POLICY", selectedColorSpace, refs) else unavailable("Neutral axis protection is not proven.", refs))
            put(195, if (metadata.clean("selectedColorWhitePoint").orEmpty().isNotBlank() || manualColorReady) proven("ILLUMINANT_POLICY", metadata.clean("selectedColorWhitePoint") ?: "D65", refs) else fallback("ILLUMINANT_POLICY", "ILLUMINANT_PROFILE_DEFAULT", "Illuminant is inferred from selected profile; measured scene illuminant is not proven.", refs))
            put(196, if (selectedColorSpace.isNotBlank() && selectedColorScience.isNotBlank()) proven("CHROMATIC_ADAPTATION_POLICY", selectedColorScience, refs) else unavailable("Chromatic adaptation is not proven.", refs))
            put(197, if (selectedColorSpace.isNotBlank()) proven("WORKING_GAMUT_POLICY", selectedColorSpace, refs) else unavailable("Working gamut conversion is not proven.", refs))
            put(198, if (metadata.clean("skinToneProtection").orEmpty() != "NOT_APPLICABLE" || subjectMaskPresent || dynamicRangeActive) proven("SKIN_COLOR_POLICY", metadata.clean("skinToneProtection") ?: "skinColorProtection=true", refs) else unavailable("Skin color protection is not proven.", refs))
            put(199, if (metadata.boolean("stage3SkyColorProtectionProven")) proven("SKY_COLOR_POLICY", "skyColor=true", refs) else sourceOnly("SKY_COLOR_POLICY_SOURCE", "Sky color policy exists, but per-session sky-region proof is incomplete.", refs))
            put(200, if (metadata.boolean("stage3NeonColorProtectionProven")) proven("NEON_COLOR_POLICY", "neonColor=true", refs) else unavailable("Neon color protection is not proven.", refs))
            put(201, if (metadata.boolean("stage3FoliageColorProtectionProven")) proven("FOLIAGE_COLOR_POLICY", "foliageColor=true", refs) else sourceOnly("FOLIAGE_COLOR_POLICY_SOURCE", "Foliage color policy exists, but per-session foliage-region proof is incomplete.", refs))
            put(202, if (request.enhancementUniformSignature.isNotBlank() || selectedColorScience.isNotBlank()) proven("SATURATION_CHROMA_POLICY", selectedColorScience, refs) else unavailable("Saturation/chroma authority is not proven.", refs))
            put(203, if (selectedColorSpace.containsWideGamutToken() || request.primaries.contains("2020", ignoreCase = true)) proven("GAMUT_MAPPING_POLICY", selectedColorSpace, refs) else identity("GAMUT_MAPPING_POLICY", "rec709_identity_gamut", refs))
            put(204, if (metadata.clean("renderGraphLutSha256").orEmpty().isNotBlank() || metadata.clean("previewRenderGraphLutSha256").orEmpty().isNotBlank()) proven("LUT_SELECTION_POLICY", metadata.clean("renderGraphLutSha256") ?: metadata.clean("previewRenderGraphLutSha256").orEmpty(), refs) else fallback("LUT_SELECTION_POLICY", "LUT_IDENTITY_OR_NOT_SELECTED", "No custom LUT hash is proven; identity LUT/color transform is recorded.", refs))
            put(205, if (metadata.boolean("stage3TetrahedralLutInterpolationProven")) proven("LUT_INTERPOLATION_POLICY", "tetrahedral=true", refs) else sourceOnly("LUT_INTERPOLATION_SOURCE", "Tetrahedral interpolation source/test authority exists, but runtime GPU proof is incomplete.", refs))
            put(206, if (metadata.boolean("stage3PerZoneLutBlendingProven")) proven("PER_ZONE_LUT_POLICY", "perZoneLut=true", refs) else unavailable("Per-zone LUT blending is not proven.", refs))
            put(207, when {
                metadata.boolean("stage3ColorAccuracyQcPassed") -> proven("COLOR_ACCURACY_QC", "colorAccuracy=pass", refs)
                metadata.boolean("vendorHdrCertification") || metadata.boolean("vendorCertifiedDolbyVision") || metadata.boolean("vendorCertifiedHdr10Plus") -> failed("VENDOR_COLOR_CERTIFICATION_CLAIMED", "Vendor HDR certification was claimed and blocks proprietary color honesty.", refs)
                else -> unavailable("Color accuracy QC is not proven by runtime/chart evidence.", refs)
            })
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putToneCinemaAuthority() {
            val refs = refs("transfer=${request.transfer}", "parity=$previewRecordingParityStatus")
            put(208, if (metadata.double("avgLuma") > 0.0 || metadata.double("dynamicRange") > 0.0 || request.actualFrameCount > 0) proven("SCENE_LUMINANCE_POLICY", "avgLuma=${metadata.clean("avgLuma").orEmpty()}", refs) else unavailable("Scene luminance analysis is not proven.", refs))
            put(209, if (request.activeTransport.isNotBlank()) proven("DISPLAY_OUTPUT_TARGET_POLICY", request.activeTransport, refs) else unavailable("Display output target is not proven.", refs))
            put(210, if (request.transfer.isNotBlank() || logActive || dynamicRangeActive) proven("TONE_CURVE_POLICY", request.transfer.ifBlank { request.selectedProfile }, refs) else unavailable("Tone curve selection is not proven.", refs))
            put(211, if (dynamicRangeActive || metadata.double("dynamicRange") > 0.0) proven("LOCAL_TONE_ZONE_POLICY", "localToneZones=true", refs) else disabledMode("Local tone zone map is inactive for this mode.", refs))
            put(212, if (dynamicRangeActive || request.enhancementUniformSignature.contains("highlight", ignoreCase = true)) proven("HIGHLIGHT_ROLLOFF_POLICY", request.transfer, refs) else disabledMode("Highlight rolloff is inactive for this mode.", refs))
            put(213, if (dynamicRangeActive || request.enhancementUniformSignature.contains("shadow", ignoreCase = true) || request.enhancementSpatialDenoise > 0f) proven("SHADOW_DEPTH_MAPPING_POLICY", "shadowDepth=true", refs) else disabledMode("Shadow depth mapping is inactive for this mode.", refs))
            put(214, if (request.enhancementSharpening > 0f || request.enhancementUniformSignature.contains("contrast", ignoreCase = true)) proven("MIDTONE_CONTRAST_POLICY", "midtoneContrast=true", refs) else unavailable("Midtone contrast mapping is not proven.", refs))
            put(215, if (metadata.boolean("stage3ToneTemporalStabilizationProven")) proven("TONE_TEMPORAL_STABILITY_POLICY", "toneTemporal=true", refs) else sourceOnly("TONE_TEMPORAL_STABILITY_SOURCE", "Tone temporal stabilization policy/source exists, but runtime flicker proof is incomplete.", refs))
            put(216, when {
                previewRecordingParityPass -> proven("VULKAN_PREVIEW_RECORDING_PARITY", request.previewQualitySignature, refs("previewQuality=${request.previewQualitySignature}", "recordingQuality=${request.recordingQualitySignature}"))
                previewRecordingParityStatus.equals("DEFERRED_DEVELOP", ignoreCase = true) -> fallback(
                    "OFFLINE_DEVELOP_PREVIEW_RECORDING_PARITY",
                    "PREVIEW_RECORDING_PARITY_DEFERRED_TO_OFFLINE_DEVELOP",
                    "The private flat master is not a display-referred parity target; compare the preview with the final developed output.",
                    refs(
                        "previewQuality=${request.previewQualitySignature}",
                        "recordingQuality=${request.recordingQualitySignature}",
                        "failures=$previewRecordingParityFailureCodes"
                    )
                )
                previewRecordingParityStatus.equals("ISP_FALLBACK", ignoreCase = true) -> fallback("VULKAN_PREVIEW_RECORDING_PARITY", "PREVIEW_RECORDING_ISP_FALLBACK", "Preview/recording tone parity uses a visible ISP fallback path.", refs)
                else -> failed("PREVIEW_FILE_TONE_MATCH_FAILED", "Preview and recording quality signatures or parity certificate did not match.", refs("previewQuality=${request.previewQualitySignature}", "recordingQuality=${request.recordingQualitySignature}", "failures=$previewRecordingParityFailureCodes"))
            })
            putCreativeStage(217, "creative_grain_policy", metadata.boolean("creativeGrainEnabled"), cleanCreativeProfile)
            putCreativeStage(218, "creative_grain_synthesis", metadata.boolean("creativeGrainSynthesisEnabled"), cleanCreativeProfile)
            putCreativeStage(219, "highlight_halation", metadata.boolean("creativeHalationEnabled"), cleanCreativeProfile)
            putCreativeStage(220, "highlight_bloom", metadata.boolean("creativeBloomEnabled"), cleanCreativeProfile)
            putCreativeStage(221, "optional_anamorphic_streak", metadata.boolean("creativeAnamorphicStreakEnabled"), cleanCreativeProfile)
            putCreativeStage(222, "optional_vignette_gate_character", metadata.boolean("creativeVignetteGateEnabled"), cleanCreativeProfile)
            put(223, if (request.transfer.isNotBlank() || previewRecordingParityStatus.isNotBlank()) proven("TONE_CINEMA_AUTHORITY", "toneCinemaOutput=true", refs) else unavailable("Tone/cinema authority output is not proven.", refs))
        }

        private fun MutableMap<Int, SingularityStage3StageSignal>.putCreativeStage(
            id: Int,
            label: String,
            enabled: Boolean,
            cleanProfile: Boolean
        ) {
            put(id, when {
                enabled -> proven("CREATIVE_LOOK_POLICY", "$label=enabled", refs(label))
                cleanProfile -> identity("CLEAN_PRESENTATION_POLICY", "$label=identity_clean_profile", refs(label))
                else -> identity("CLEAN_PRESENTATION_POLICY", "$label=identity_default", refs(label))
            })
        }

        private fun proven(
            backend: String,
            output: String,
            refs: List<String>,
            gpuNs: Long = 0L,
            nnapiNs: Long = 0L,
            confidence: Double = 1.0
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.PROVEN,
                backend = backend,
                outputResources = listOf(output).filter { it.isNotBlank() },
                gpuDurationNs = gpuNs,
                nnapiDurationNs = nnapiNs,
                confidence = confidence,
                evidenceReferences = refs
            )

        private fun identity(
            backend: String,
            output: String,
            refs: List<String>
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.IDENTITY,
                backend = backend,
                outputResources = listOf(output).filter { it.isNotBlank() },
                evidenceReferences = refs
            )

        private fun fallback(
            backend: String,
            fallbackId: String,
            fallbackReason: String,
            refs: List<String>,
            output: String = fallbackId
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.FALLBACK,
                backend = backend,
                outputResources = listOf(output),
                fallbackId = fallbackId,
                fallbackReason = fallbackReason,
                confidence = 0.5,
                evidenceReferences = refs
            )

        private fun sourceOnly(
            backend: String,
            reason: String,
            refs: List<String>
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.SOURCE_AVAILABLE_ONLY,
                backend = backend,
                fallbackId = SingularityStage3EvidenceBundle.SOURCE_AVAILABLE_RUNTIME_NOT_PROVEN,
                fallbackReason = reason,
                confidence = 0.25,
                evidenceReferences = refs
            )

        private fun disabledMode(
            reason: String,
            refs: List<String>
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.DISABLED_BY_MODE,
                backend = "MODE_POLICY",
                fallbackReason = reason,
                confidence = 1.0,
                evidenceReferences = refs
            )

        private fun unavailable(
            reason: String,
            refs: List<String>
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.UNAVAILABLE,
                backend = "RUNTIME_EVIDENCE",
                fallbackReason = reason,
                confidence = 0.0,
                evidenceReferences = refs
            )

        private fun failed(
            fallbackId: String,
            reason: String,
            refs: List<String>
        ): SingularityStage3StageSignal =
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.FAILED,
                backend = "SINGULARITY_STAGE3_GATE",
                fallbackId = fallbackId,
                fallbackReason = reason,
                confidence = 0.0,
                evidenceReferences = refs
            )

        private fun refs(vararg values: String): List<String> =
            baseReferences + values.filter { it.isNotBlank() }
    }

    private fun String.isNeoDynamicRangeProfile(): Boolean {
        val key = uppercase(Locale.US)
        return key == "NDR10+" ||
            key == "NIVUKXVISION" ||
            key == "APEX_SHARP" ||
            key == "APEX_ZERO" ||
            key == "APEX_CINEMA" ||
            key.contains("NDR") ||
            key.contains("NIVUKX")
    }

    private fun String.containsWideGamutToken(): Boolean {
        val key = lowercase(Locale.US)
        return key.contains("2020") ||
            key.contains("aces") ||
            key.contains("wide") ||
            key.contains("neo") ||
            key.contains("p3")
    }

    private fun Map<String, String>.clean(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<String, String>.boolean(key: String, default: Boolean = false): Boolean =
        when (clean(key)?.lowercase(Locale.US)) {
            null -> default
            "true", "1", "yes", "supported", "enabled", "pass", "proven" -> true
            "false", "0", "no", "unsupported", "disabled", "fail", "unproven" -> false
            else -> default
        }

    private fun Map<String, String>.int(key: String): Int =
        clean(key)?.toIntOrNull() ?: 0

    private fun Map<String, String>.long(key: String): Long =
        clean(key)?.toLongOrNull() ?: 0L

    private fun Map<String, String>.double(key: String): Double =
        clean(key)?.toDoubleOrNull() ?: 0.0
}
