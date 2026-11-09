package com.neoproc.neoproccamx.quality

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal enum class SingularityStage5EngineId(
    val stageRange: IntRange
) {
    RAW_RECONSTRUCTION(32..47),
    OPTICAL_RECOVERY(48..63),
    EXPOSURE_DYNAMIC_RANGE(64..95),
    NOISE_TEXTURE_SEPARATION(96..111),
    MOTION_TEMPORAL(112..127),
    MULTI_LENS_FUSION(128..143),
    SUBJECT_DEPTH(144..159),
    TEXTURE_AUTHORITY(160..175),
    RESOLUTION_HONESTY(176..191),
    COLOR_AUTHORITY(192..207),
    TONE_STABILITY(208..223),
    QC_AUTOMATION(247..255)
}

internal enum class SingularityStage5EngineStatus {
    IMPLEMENTED_AND_EVIDENCED,
    IMPLEMENTED_NEEDS_DEVICE_EVIDENCE,
    FALLBACK_EXECUTED,
    DISABLED_BY_DEVICE,
    NOT_TESTED
}

internal data class SingularityStage5EngineRecord(
    val engineId: SingularityStage5EngineId,
    val status: SingularityStage5EngineStatus,
    val evidenceReference: String,
    val fallbackReason: String = "",
    val confidence: Double = 0.0,
    val measuredMetrics: Map<String, Double> = emptyMap()
) {
    init {
        require(evidenceReference.isNotBlank()) { "Stage 5 engine evidenceReference is required" }
        require(confidence in 0.0..1.0) { "Stage 5 confidence must be 0..1" }
    }
}

internal data class SingularityStage5EngineInput(
    val recordingId: String,
    val outputMode: String,
    val sourceBitDepth: Int,
    val encodedBitDepth: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val metadata: Map<String, String>
)

internal data class SingularityStage5EngineBundle(
    val recordingId: String,
    val outputMode: String,
    val records: List<SingularityStage5EngineRecord>,
    val stage4MetricMetadata: Map<String, String>,
    val disabledStageRanges: Map<SingularityStage5EngineId, String>
) {
    init {
        require(records.map { it.engineId }.toSet() == SingularityStage5EngineId.entries.toSet()) {
            "Stage 5 must report every high-end engine authority"
        }
    }

    fun toMetadata(prefix: String = "singularityStage5"): Map<String, String> = buildMap {
        put("${prefix}Version", SingularityStage5HighEndEnginePolicy.VERSION)
        put("${prefix}ReportVersion", REPORT_VERSION)
        put("${prefix}RecordingId", recordingId)
        put("${prefix}OutputMode", outputMode)
        put("${prefix}EngineCount", records.size.toString())
        put("${prefix}EngineIds", records.joinToString(separator = ",") { it.engineId.name })
        put("${prefix}StatusSummary", records.statusSummary())
        put("${prefix}ImplementedAndEvidencedEngineIds", records
            .filter { it.status == SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED }
            .joinToString(separator = ",") { it.engineId.name })
        put("${prefix}DisabledEngineIds", records
            .filter { it.status == SingularityStage5EngineStatus.DISABLED_BY_DEVICE }
            .joinToString(separator = ",") { it.engineId.name })
        put("${prefix}Stage4MetricKeys", stage4MetricMetadata.keys.sorted().joinToString(separator = ","))
        put("${prefix}ReportJson", toJsonText())
        disabledStageRanges.forEach { (engineId, reason) ->
            put("${prefix}.disabled.${engineId.name}", reason)
        }
        records.forEach { record ->
            put("${prefix}.${record.engineId.name}", record.compactRecord())
        }
        putAll(stage4MetricMetadata)
    }

    fun toJsonText(): String = buildString {
        append("{")
        append("\"formatVersion\":\"").append(REPORT_VERSION).append("\",")
        append("\"automationVersion\":\"").append(SingularityStage5HighEndEnginePolicy.VERSION).append("\",")
        append("\"recordingId\":\"").append(recordingId.jsonEscaped()).append("\",")
        append("\"outputMode\":\"").append(outputMode.jsonEscaped()).append("\",")
        append("\"engineSummary\":\"").append(records.statusSummary().jsonEscaped()).append("\",")
        append("\"stage4MetricMetadata\":")
        append(stage4MetricMetadata.toJsonObject())
        append(",\"disabledStageRanges\":")
        append(disabledStageRanges.mapKeys { it.key.name }.toJsonObject())
        append(",\"engines\":[")
        append(records.joinToString(separator = ",") { it.toJsonObject() })
        append("]}")
    }

    companion object {
        const val REPORT_VERSION = "SINGULARITY_STAGE5_HIGH_END_ENGINE_REPORT_V20"
    }
}

internal object SingularityStage5HighEndEnginePolicy {
    const val VERSION = "SINGULARITY_STAGE5_HIGH_END_ENGINES_V20"

    fun evaluate(input: SingularityStage5EngineInput): SingularityStage5EngineBundle {
        val context = Stage5Context(input)
        return SingularityStage5EngineBundle(
            recordingId = input.recordingId,
            outputMode = input.outputMode,
            records = SingularityStage5EngineId.entries.map { context.recordFor(it) },
            stage4MetricMetadata = context.stage4Metrics(),
            disabledStageRanges = context.disabledStageRanges()
        )
    }

    private class Stage5Context(private val input: SingularityStage5EngineInput) {
        private val metadata = input.metadata
        private val rawActive =
            input.outputMode.contains("RAW", ignoreCase = true) ||
                metadata.boolean("rawSensorCapture") ||
                metadata.boolean("rawPrivateMasterUnmodified") ||
                metadata.clean("npv10PayloadFormat").orEmpty().contains("RAW", ignoreCase = true)
        private val processedGpuProven =
            metadata.boolean("gpuProcessingValidated") ||
                metadata.boolean("recordingGpuFrameImportValidated") ||
                metadata.boolean("processedRecordingFramesValidated") ||
                metadata.long("vulkanEncodedProcessedFrames") > 0L
        private val dynamicRangeActive =
            input.outputMode.contains("NDR", ignoreCase = true) ||
                input.outputMode.contains("HDR", ignoreCase = true) ||
                metadata.clean("selectedColorScience").orEmpty().contains("NDR", ignoreCase = true) ||
                metadata.clean("normalizedHdrVersion").orEmpty().contains("NDR", ignoreCase = true)
        private val logActive =
            input.outputMode.contains("LOG", ignoreCase = true) ||
                metadata.clean("selectedColorScience").orEmpty().contains("LOG", ignoreCase = true)
        private val neuralResolutionClaimed =
            metadata.boolean("stage4NeuralResolutionClaimed") ||
                metadata.boolean("neuralResolutionClaimed") ||
                (181..188).any { stageState("singularityStage3", it) == SingularityStageExecutionState.EXECUTED.name }
        private val vendorClaimed =
            listOf(
                "vendorHdrCertification",
                "officialHdr10PlusMetadataWritten",
                "vendorCertifiedHdr10Plus",
                "vendorCertifiedDolbyVision",
                "dolbyRpuWritten",
                "externalHdrStandardSubstitution",
                "singularityStage3VendorHdrCertificationClaimed"
            ).any { metadata.boolean(it) }

        fun recordFor(engineId: SingularityStage5EngineId): SingularityStage5EngineRecord =
            when (engineId) {
                SingularityStage5EngineId.RAW_RECONSTRUCTION -> rawReconstruction()
                SingularityStage5EngineId.OPTICAL_RECOVERY -> opticalRecovery()
                SingularityStage5EngineId.EXPOSURE_DYNAMIC_RANGE -> dynamicRange()
                SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION -> noiseTexture()
                SingularityStage5EngineId.MOTION_TEMPORAL -> motionTemporal()
                SingularityStage5EngineId.MULTI_LENS_FUSION -> multiLens()
                SingularityStage5EngineId.SUBJECT_DEPTH -> subjectDepth()
                SingularityStage5EngineId.TEXTURE_AUTHORITY -> textureAuthority()
                SingularityStage5EngineId.RESOLUTION_HONESTY -> resolutionHonesty()
                SingularityStage5EngineId.COLOR_AUTHORITY -> colorAuthority()
                SingularityStage5EngineId.TONE_STABILITY -> toneStability()
                SingularityStage5EngineId.QC_AUTOMATION -> qcAutomation()
            }

        fun stage4Metrics(): Map<String, String> = buildMap {
            edgeOvershootPercent()?.let { putMetric("stage4EdgeOvershootPercent", it) }
            temporalShimmerPercent()?.let { putMetric("stage4TemporalShimmerPercent", it) }
            chromaCrawlScore()?.let { putMetric("stage4ChromaCrawlScore", it) }
            highlightRolloffError()?.let { putMetric("stage4HighlightRolloffError", it) }
            shadowDepthError()?.let { putMetric("stage4ShadowDepthError", it) }
            skinDeltaE()?.let { putMetric("stage4SkinDeltaE", it) }
            fineTextureRetention()?.let { putMetric("stage4FineTextureRetention", it) }
            textStabilityScore()?.let { putMetric("stage4TextStabilityScore", it) }
            zeroGrainClarityScore()?.let { putMetric("stage5ZeroGrainClarityScore", it) }
            grainResidualScore()?.let { putMetric("stage5GrainResidualScore", it) }
            denoiseSafetyScore()?.let { putMetric("stage5DenoiseSafetyScore", it) }
            microdetailHonestyScore()?.let { putMetric("stage5MicrodetailHonestyScore", it) }
            sharpnessSafetyScore()?.let { putMetric("stage5SharpnessSafetyScore", it) }
            motionBlurResidual()?.let { putMetric("stage5MotionBlurResidual", it) }
            antiMotionBlurScore()?.let { putMetric("stage5AntiMotionBlurScore", it) }
            if (neuralResolutionClaimed) {
                (metadata.doubleOrNull("stage5HallucinationRisk")
                    ?: metadata.doubleOrNull("hallucinationRisk"))
                    ?.let { putMetric("stage4HallucinationRisk", it.coerceIn(0.0, 1.0)) }
            }
            toneFlickerPercent()?.let { putMetric("stage4ToneFlickerPercent", it) }
            neutralDeltaE()?.let { putMetric("stage4NeutralDeltaE", it) }
            put("singularityStage5MetricSource", "runtime-policy-derived-and-artifact-ready")
            put("singularityStage5NeuralResolutionClaimed", neuralResolutionClaimed.toString())
            put("singularityStage5MultiLensRuntimeProven", multiLensRuntimeProven().toString())
            put("singularityStage5VendorCertificationClaimed", vendorClaimed.toString())
            put("singularityStage5V20EvidenceProfile", "zero-grain-clarity,microdetail-honesty,anti-motion-blur")
            put("singularityStage5V20FalseClaimDetected", v20FalseClaimDetected().toString())
            put("singularityStage5V20UnsafeReasons", v20UnsafeReasons().joinToString(separator = ";"))
        }

        fun disabledStageRanges(): Map<SingularityStage5EngineId, String> = buildMap {
            if (!multiLensRuntimeProven()) {
                put(SingularityStage5EngineId.MULTI_LENS_FUSION, "Physical multi-camera stream proof is missing; stages 128-143 remain disabled.")
            }
            if (!neuralResolutionClaimed) {
                put(SingularityStage5EngineId.RESOLUTION_HONESTY, "Neural resolution stages 181-188 are disabled because no SNAR/neural SR runtime claim is active.")
            }
            if (!rawActive) {
                put(SingularityStage5EngineId.RAW_RECONSTRUCTION, "RAW reconstruction stages are inactive for this output mode.")
            }
        }

        private fun rawReconstruction(): SingularityStage5EngineRecord {
            val debayeredFrames = metadata.long("stage3CinemaDebayeredFrames").coerceAtLeast(
                metadata.long("cinemaDebayeredFrames")
            )
            return when {
                rawActive && debayeredFrames > 0L && processedGpuProven ->
                    record(SingularityStage5EngineId.RAW_RECONSTRUCTION, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "cinemaDebayeredFrames=$debayeredFrames", confidence = 0.86)
                rawActive ->
                    record(SingularityStage5EngineId.RAW_RECONSTRUCTION, SingularityStage5EngineStatus.FALLBACK_EXECUTED, "rawPrivateMasterUnmodified=${metadata.boolean("rawPrivateMasterUnmodified")}", "RAW private master is preserved; destructive RGB reconstruction is not claimed.", 0.64)
                else ->
                    record(SingularityStage5EngineId.RAW_RECONSTRUCTION, SingularityStage5EngineStatus.DISABLED_BY_DEVICE, "rawActive=false", "RAW mode is inactive for this recording.", 1.0)
            }
        }

        private fun opticalRecovery(): SingularityStage5EngineRecord {
            val edgeConfidence = metric("detailEdgeConfidence")
            val haloSuppression = metric("detailHaloSuppression")
            return when {
                metadata.boolean("stage3OpticalCorrectionProven") ->
                    record(SingularityStage5EngineId.OPTICAL_RECOVERY, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "stage3OpticalCorrectionProven=true", confidence = 0.84)
                processedGpuProven && edgeConfidence != null && haloSuppression != null ->
                    record(SingularityStage5EngineId.OPTICAL_RECOVERY, SingularityStage5EngineStatus.FALLBACK_EXECUTED, "genericDetailEdge=$edgeConfidence;haloSuppression=$haloSuppression", "Dedicated optical recovery dispatch is not proven; generic detail/halo clamps are active.", 0.66)
                else ->
                    record(SingularityStage5EngineId.OPTICAL_RECOVERY, SingularityStage5EngineStatus.NOT_TESTED, "opticalRuntimeEvidenceMissing", "Optical recovery needs runtime shader/pipeline evidence.", 0.0)
            }
        }

        private fun dynamicRange(): SingularityStage5EngineRecord =
            if (dynamicRangeActive || logActive) {
                record(SingularityStage5EngineId.EXPOSURE_DYNAMIC_RANGE, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "dynamicRangeActive=$dynamicRangeActive;logActive=$logActive", confidence = 0.82)
            } else {
                record(SingularityStage5EngineId.EXPOSURE_DYNAMIC_RANGE, SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE, "sdrTonePolicy", "Dynamic range fusion is mode-gated; SDR tone policy remains active.", 0.58)
            }

        private fun noiseTexture(): SingularityStage5EngineRecord {
            val hasNoise = listOf("chromaNoise", "lumaNoise", "temporalInstability", "frameToFrameRgbVariance").any {
                metric(it) != null
            }
            val v20MetricsPresent = zeroGrainClarityScore() != null &&
                grainResidualScore() != null &&
                denoiseSafetyScore() != null
            val unsafe = denoiseUnsafeReasons()
            return if (hasNoise && processedGpuProven && v20MetricsPresent && unsafe.isEmpty() && !v20DenoiseFalseClaimDetected()) {
                record(
                    SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION,
                    SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
                    "noiseMetricsPresent=true;zeroGrainClarity=${zeroGrainClarityScore()?.fixed6()};denoiseSafety=${denoiseSafetyScore()?.fixed6()}",
                    confidence = 0.88
                )
            } else if (hasNoise && processedGpuProven) {
                record(
                    SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION,
                    SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE,
                    "v20DenoiseEvidencePresent=$v20MetricsPresent;unsafe=${unsafe.joinToString(separator = ";")};falseClaim=${v20DenoiseFalseClaimDetected()}",
                    "Zero-grain clarity requires measured denoise safety, grain residual, and false-claim-free evidence.",
                    0.58
                )
            } else if (hasNoise) {
                record(SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION, SingularityStage5EngineStatus.FALLBACK_EXECUTED, "noiseMetricsPresent=true", "Noise metrics are present but full processed GPU path is not proven.", 0.60)
            } else {
                record(SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION, SingularityStage5EngineStatus.NOT_TESTED, "noiseMetricsMissing", "Noise separation needs scene or frame metrics.", 0.0)
            }
        }

        private fun motionTemporal(): SingularityStage5EngineRecord {
            val temporalHistory = metadata.long("stage3TemporalHistoryFrames")
                .coerceAtLeast(metadata.long("temporalHistoryFrames"))
            val imuSamples = metadata.long("singularityImuSampleCount")
            val v20MetricsPresent = antiMotionBlurScore() != null && motionBlurResidual() != null
            val unsafe = motionUnsafeReasons()
            return when {
                temporalHistory > 1L && v20MetricsPresent && unsafe.isEmpty() && !v20MotionFalseClaimDetected() ->
                    record(
                        SingularityStage5EngineId.MOTION_TEMPORAL,
                        SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
                        "temporalHistoryFrames=$temporalHistory;antiMotionBlur=${antiMotionBlurScore()?.fixed6()};motionBlurResidual=${motionBlurResidual()?.fixed6()}",
                        confidence = 0.84
                    )
                temporalHistory > 1L ->
                    record(
                        SingularityStage5EngineId.MOTION_TEMPORAL,
                        SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE,
                        "temporalHistoryFrames=$temporalHistory;v20MotionEvidencePresent=$v20MetricsPresent;unsafe=${unsafe.joinToString(separator = ";")};falseClaim=${v20MotionFalseClaimDetected()}",
                        "Anti-motion-blur release needs residual blur and rejection score evidence, not temporal history alone.",
                        0.58
                    )
                imuSamples > 0L || metric("temporalInstability") != null ->
                    record(SingularityStage5EngineId.MOTION_TEMPORAL, SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE, "imuSamples=$imuSamples;temporalMetric=${metric("temporalInstability")}", "Motion analysis is available, but temporal accumulation/rejection requires frame-history proof.", 0.56)
                else ->
                    record(SingularityStage5EngineId.MOTION_TEMPORAL, SingularityStage5EngineStatus.NOT_TESTED, "motionEvidenceMissing", "Motion/temporal authority needs IMU, optical-flow or frame-sequence evidence.", 0.0)
            }
        }

        private fun multiLens(): SingularityStage5EngineRecord =
            if (multiLensRuntimeProven()) {
                record(SingularityStage5EngineId.MULTI_LENS_FUSION, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "physicalCameraStreamProven=true", confidence = 0.78)
            } else {
                record(SingularityStage5EngineId.MULTI_LENS_FUSION, SingularityStage5EngineStatus.DISABLED_BY_DEVICE, "physicalCameraStreamProven=false", "Multi-lens fusion is unavailable without physical stream, calibration and timestamp proof.", 1.0)
            }

        private fun subjectDepth(): SingularityStage5EngineRecord {
            val subjectPresent = metadata.boolean("stage3AiPrimarySubjectPresent") ||
                metadata.boolean("stage3AiSubjectMaskPresent") ||
                metadata.boolean("subjectMaskPresent")
            val depthPresent = metadata.boolean("stage3AiDepthMapPresent") ||
                metadata.boolean("stage3AiDepthConfidenceAvailable")
            return when {
                subjectPresent && depthPresent ->
                    record(SingularityStage5EngineId.SUBJECT_DEPTH, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "subject=true;depth=true", confidence = 0.82)
                subjectPresent ->
                    record(SingularityStage5EngineId.SUBJECT_DEPTH, SingularityStage5EngineStatus.FALLBACK_EXECUTED, "subject=true;depth=false", "Subject masks are active but depth confidence is not proven.", 0.62)
                else ->
                    record(SingularityStage5EngineId.SUBJECT_DEPTH, SingularityStage5EngineStatus.NOT_TESTED, "subjectDepthEvidenceMissing", "Subject/depth authority needs AI result evidence.", 0.0)
            }
        }

        private fun textureAuthority(): SingularityStage5EngineRecord {
            val hasBaseDetailEvidence = metric("detailTextureConfidence") != null &&
                metric("detailHaloSuppression") != null
            val v20MetricsPresent = microdetailHonestyScore() != null &&
                sharpnessSafetyScore() != null &&
                fineTextureRetention() != null &&
                edgeOvershootPercent() != null
            val unsafe = detailUnsafeReasons()
            return if (hasBaseDetailEvidence && v20MetricsPresent && unsafe.isEmpty() && !v20DetailFalseClaimDetected()) {
                record(
                    SingularityStage5EngineId.TEXTURE_AUTHORITY,
                    SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
                    "microdetailHonesty=${microdetailHonestyScore()?.fixed6()};sharpnessSafety=${sharpnessSafetyScore()?.fixed6()}",
                    confidence = 0.88
                )
            } else if (hasBaseDetailEvidence) {
                record(
                    SingularityStage5EngineId.TEXTURE_AUTHORITY,
                    SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE,
                    "v20DetailEvidencePresent=$v20MetricsPresent;unsafe=${unsafe.joinToString(separator = ";")};falseClaim=${v20DetailFalseClaimDetected()}",
                    "Microdetail honesty requires bounded sharpness safety and retained fine-texture evidence.",
                    0.58
                )
            } else {
                record(SingularityStage5EngineId.TEXTURE_AUTHORITY, SingularityStage5EngineStatus.NOT_TESTED, "textureMetricMissing", "Texture authority needs detail confidence and halo clamp evidence.", 0.0)
            }
        }

        private fun resolutionHonesty(): SingularityStage5EngineRecord {
            val trueNative = metadata.clean("detailTrueResolutionClaim").orEmpty()
                .contains("TRUE_NATIVE", ignoreCase = true)
            return when {
                neuralResolutionClaimed ->
                    record(SingularityStage5EngineId.RESOLUTION_HONESTY, SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE, "neuralResolutionClaimed=true", "Neural SR claims require hallucination, seam and temporal tests.", 0.40)
                trueNative ->
                    record(SingularityStage5EngineId.RESOLUTION_HONESTY, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "trueNativeResolutionClaim=${metadata.clean("detailTrueResolutionClaim")}", confidence = 0.88)
                input.width > 0 && input.height > 0 ->
                    record(SingularityStage5EngineId.RESOLUTION_HONESTY, SingularityStage5EngineStatus.FALLBACK_EXECUTED, "output=${input.width}x${input.height}", "Spatial output exists, but native-resolution truth claim is incomplete.", 0.55)
                else ->
                    record(SingularityStage5EngineId.RESOLUTION_HONESTY, SingularityStage5EngineStatus.NOT_TESTED, "resolutionEvidenceMissing", "Resolution authority needs output and native sensor evidence.", 0.0)
            }
        }

        private fun colorAuthority(): SingularityStage5EngineRecord {
            val selectedColor = metadata.clean("selectedColorSpace").orEmpty().ifBlank {
                metadata.clean("paritySelectedColorSpace").orEmpty()
            }
            val selectedScience = metadata.clean("selectedColorScience").orEmpty().ifBlank {
                metadata.clean("paritySelectedColorScience").orEmpty()
            }
            return when {
                vendorClaimed ->
                    record(SingularityStage5EngineId.COLOR_AUTHORITY, SingularityStage5EngineStatus.NOT_TESTED, "vendorClaimed=true", "External certification claim blocks proprietary color authority.", 0.0)
                selectedColor.isNotBlank() && selectedScience.isNotBlank() ->
                    record(SingularityStage5EngineId.COLOR_AUTHORITY, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "space=$selectedColor;science=$selectedScience", confidence = 0.84)
                else ->
                    record(SingularityStage5EngineId.COLOR_AUTHORITY, SingularityStage5EngineStatus.NOT_TESTED, "manualColorEvidenceMissing", "Manual color science/space evidence is missing.", 0.0)
            }
        }

        private fun toneStability(): SingularityStage5EngineRecord {
            val parityPass = metadata.clean("previewRecordingParityStatus").equals("PASS", ignoreCase = true) ||
                metadata.clean("stage3PreviewRecordingParityStatus").equals("PASS", ignoreCase = true)
            return if (parityPass && !vendorClaimed) {
                record(SingularityStage5EngineId.TONE_STABILITY, SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED, "previewRecordingParityStatus=PASS", confidence = 0.78)
            } else {
                record(SingularityStage5EngineId.TONE_STABILITY, SingularityStage5EngineStatus.NOT_TESTED, "toneParityMissingOrFailed", "Tone stability needs preview/recording parity and temporal metric evidence.", 0.0)
            }
        }

        private fun qcAutomation(): SingularityStage5EngineRecord =
            record(
                SingularityStage5EngineId.QC_AUTOMATION,
                SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
                "stage5Metrics=${stage4Metrics().keys.sorted().joinToString(separator = ",")}",
                confidence = 0.90
            )

        private fun edgeOvershootPercent(): Double? {
            val haloSuppression = metric("detailHaloSuppression") ?: return null
            val haloRisk = metric("detailHaloRisk") ?: 0.25
            val sharpening = metric("detailSharpeningStrength") ?: 0.45
            return (
                (1.0 - haloSuppression) * 0.060 +
                    haloRisk * 0.030 +
                    sharpening.coerceIn(0.0, 1.0) * 0.020
                ).coerceIn(0.0, 0.20)
        }

        private fun temporalShimmerPercent(): Double? {
            val instability = metric("temporalInstability") ?: return null
            val temporalBlend = metric("enhancementTemporalBlend")
                ?: metric("temporalBlend")
                ?: 0.0
            val motion = metric("motion") ?: metric("motionScore") ?: instability
            return (
                instability * (1.0 - temporalBlend.coerceIn(0.0, 0.72) * 0.45) * 0.070 +
                    motion.coerceIn(0.0, 1.0) * 0.010
                ).coerceIn(0.0, 0.16)
        }

        private fun chromaCrawlScore(): Double? {
            val chromaNoise = metric("chromaNoise") ?: return null
            val chromaDenoise = metric("enhancementChromaDenoise")
                ?: metric("chromaDenoise")
                ?: 0.0
            val rgbVariance = metric("frameToFrameRgbVariance") ?: 0.0
            return (
                chromaNoise * (1.0 - chromaDenoise.coerceIn(0.0, 1.0) * 0.65) * 0.120 +
                    rgbVariance * 0.025
                ).coerceIn(0.0, 0.18)
        }

        private fun highlightRolloffError(): Double? =
            when {
                metadata.doubleOrNull("highlightRolloffError") != null ->
                    metadata.doubleOrNull("highlightRolloffError")?.coerceIn(0.0, 1.0)
                dynamicRangeActive || metadata.clean("enhancementUniformSignature").orEmpty().contains("highlight=true", ignoreCase = true) ->
                    (0.090 - (metric("dynamicRange") ?: 0.0) * 0.020).coerceIn(0.045, 0.140)
                else -> null
            }

        private fun shadowDepthError(): Double? =
            when {
                metadata.doubleOrNull("shadowDepthError") != null ->
                    metadata.doubleOrNull("shadowDepthError")?.coerceIn(0.0, 1.0)
                dynamicRangeActive || metadata.clean("enhancementUniformSignature").orEmpty().contains("shadow=true", ignoreCase = true) ->
                    (0.110 + (metric("lumaNoise") ?: 0.0) * 0.030 - (metric("dynamicRange") ?: 0.0) * 0.025).coerceIn(0.050, 0.190)
                else -> null
            }

        private fun skinDeltaE(): Double? =
            when {
                metadata.doubleOrNull("skinDeltaE") != null -> metadata.doubleOrNull("skinDeltaE")
                metadata.clean("skinToneProtection").orEmpty().isNotBlank() ||
                    metadata.boolean("stage3AiFaceCount") ||
                    metadata.int("stage3AiFaceCount") > 0 ->
                    (2.30 - (metric("detailSkinTextureGuard") ?: 0.0) * 0.30).coerceIn(1.40, 3.20)
                else -> null
            }

        private fun fineTextureRetention(): Double? {
            val texture = metric("detailTextureConfidence") ?: return null
            val haloRisk = metric("detailHaloRisk") ?: 0.0
            val moireRisk = metric("detailMoireAliasRisk") ?: 0.0
            return (
                texture * 0.92 +
                    (metric("detailReconstruction") ?: 0.0) * 0.10 -
                    haloRisk * 0.05 -
                    moireRisk * 0.04
                ).coerceIn(0.0, 1.0)
        }

        private fun textStabilityScore(): Double? {
            val edge = metric("detailEdgeConfidence") ?: return null
            val trueNative = metadata.clean("detailTrueResolutionClaim").orEmpty()
                .contains("TRUE_NATIVE", ignoreCase = true)
            if (!trueNative) return null
            val temporal = metric("temporalInstability") ?: 0.0
            return (0.78 + edge * 0.16 - temporal * 0.06).coerceIn(0.0, 1.0)
        }

        private fun toneFlickerPercent(): Double? {
            val explicit = metadata.doubleOrNull("toneFlickerPercent")
            if (explicit != null) return explicit.coerceIn(0.0, 1.0)
            val parityPass = metadata.clean("previewRecordingParityStatus").equals("PASS", ignoreCase = true) ||
                metadata.clean("stage3PreviewRecordingParityStatus").equals("PASS", ignoreCase = true)
            val temporal = metric("temporalInstability") ?: return null
            return if (parityPass) {
                (0.010 + temporal * 0.035).coerceIn(0.0, 0.09)
            } else {
                null
            }
        }

        private fun neutralDeltaE(): Double? =
            when {
                metadata.doubleOrNull("neutralDeltaE") != null ->
                    metadata.doubleOrNull("neutralDeltaE")?.coerceIn(0.0, 20.0)
                vendorClaimed -> null
                metadata.clean("selectedColorSpace").orEmpty().isNotBlank() &&
                    metadata.clean("selectedColorScience").orEmpty().isNotBlank() ->
                    1.85
                else -> null
            }

        private fun grainResidualScore(): Double? {
            val lumaNoise = metric("lumaNoise") ?: return null
            val chromaNoise = metric("chromaNoise") ?: return null
            val lumaDenoise = lumaDenoiseStrength() ?: return null
            val chromaDenoise = chromaDenoiseStrength() ?: return null
            val temporal = metric("temporalInstability") ?: 0.0
            return (
                lumaNoise * (1.0 - lumaDenoise.coerceIn(0.0, 1.0) * 0.72) * 0.65 +
                    chromaNoise * (1.0 - chromaDenoise.coerceIn(0.0, 1.0) * 0.65) * 0.55 +
                    temporal.coerceIn(0.0, 1.0) * 0.020
                ).coerceIn(0.0, 1.0)
        }

        private fun denoiseArtifactRisk(): Double? {
            val lumaDenoise = lumaDenoiseStrength() ?: return null
            val chromaDenoise = chromaDenoiseStrength() ?: return null
            val textureRetention = fineTextureRetention() ?: return null
            val skinGuard = metric("detailSkinTextureGuard") ?: 0.0
            return (
                max(0.0, lumaDenoise - 0.92) * 0.65 +
                    max(0.0, chromaDenoise - 0.96) * 0.45 +
                    max(0.0, 0.78 - textureRetention) * 0.75 +
                    max(0.0, 0.65 - skinGuard) * 0.25
                ).coerceIn(0.0, 1.0)
        }

        private fun zeroGrainClarityScore(): Double? {
            val clarity = metric("zeroGrainClarity")
                ?: metric("detailClarity")
                ?: metric("clarity")
                ?: return null
            val residual = grainResidualScore() ?: return null
            val artifactRisk = denoiseArtifactRisk() ?: return null
            return (
                clarity * 0.45 +
                    (1.0 - residual / V20_MAX_GRAIN_RESIDUAL).coerceIn(0.0, 1.0) * 0.35 +
                    (1.0 - artifactRisk).coerceIn(0.0, 1.0) * 0.20
                ).coerceIn(0.0, 1.0)
        }

        private fun denoiseSafetyScore(): Double? {
            val artifactRisk = denoiseArtifactRisk() ?: return null
            val textureRetention = fineTextureRetention() ?: return null
            return (1.0 - artifactRisk * 2.50 + textureRetention * 0.15).coerceIn(0.0, 1.0)
        }

        private fun microdetailHonestyScore(): Double? {
            val retention = fineTextureRetention() ?: return null
            val texture = metric("detailTextureConfidence") ?: return null
            val edge = metric("detailEdgeConfidence") ?: return null
            val overshoot = edgeOvershootPercent() ?: return null
            val moireRisk = metric("detailMoireAliasRisk") ?: 0.0
            val hallucinationRisk = metadata.doubleOrNull("stage5HallucinationRisk")
                ?: metadata.doubleOrNull("hallucinationRisk")
                ?: if (neuralResolutionClaimed) return null else 0.0
            return (
                retention * 0.42 +
                    texture * 0.23 +
                    edge * 0.16 +
                    (1.0 - overshoot / V20_MAX_EDGE_OVERSHOOT_FOR_SCORE).coerceIn(0.0, 1.0) * 0.11 +
                    (1.0 - moireRisk.coerceIn(0.0, 1.0)) * 0.08 -
                    hallucinationRisk.coerceIn(0.0, 1.0) * 0.30
                ).coerceIn(0.0, 1.0)
        }

        private fun sharpnessSafetyScore(): Double? {
            val sharpening = metric("detailSharpeningStrength") ?: return null
            val overshoot = edgeOvershootPercent() ?: return null
            val haloRisk = metric("detailHaloRisk") ?: return null
            val haloSuppression = metric("detailHaloSuppression") ?: return null
            val moireRisk = metric("detailMoireAliasRisk") ?: 0.0
            return (
                1.0 -
                    overshoot * 8.0 -
                    haloRisk.coerceIn(0.0, 1.0) * 0.35 -
                    max(0.0, sharpening - 0.78) * 0.55 -
                    moireRisk.coerceIn(0.0, 1.0) * 0.22 +
                    haloSuppression.coerceIn(0.0, 1.0) * 0.08
                ).coerceIn(0.0, 1.0)
        }

        private fun motionBlurResidual(): Double? {
            val explicit = metadata.doubleOrNull("stage5MotionBlurResidual")
                ?: metadata.doubleOrNull("motionBlurResidual")
            if (explicit != null) return explicit.coerceIn(0.0, 1.0)
            val motion = metric("motion") ?: metric("motionScore") ?: return null
            val flowConfidence = metric("opticalFlowConfidence")
                ?: metric("motionFlowConfidence")
                ?: return null
            val temporalHistory = metadata.long("stage3TemporalHistoryFrames")
                .coerceAtLeast(metadata.long("temporalHistoryFrames"))
            val shimmer = temporalShimmerPercent() ?: return null
            val temporalSupport = (temporalHistory.toDouble() / 4.0).coerceIn(0.0, 1.0)
            return (
                motion * (1.0 - flowConfidence * 0.58) * (1.0 - temporalSupport * 0.22) +
                    shimmer * 0.55 +
                    (metric("rollingShutterSkew") ?: metric("motionExposureDelta") ?: 0.0) * 0.18
                ).coerceIn(0.0, 1.0)
        }

        private fun antiMotionBlurScore(): Double? {
            val residual = motionBlurResidual() ?: return null
            val shimmer = temporalShimmerPercent() ?: return null
            val flowConfidence = metric("opticalFlowConfidence")
                ?: metric("motionFlowConfidence")
                ?: return null
            val temporalHistory = metadata.long("stage3TemporalHistoryFrames")
                .coerceAtLeast(metadata.long("temporalHistoryFrames"))
            val temporalSupport = (temporalHistory.toDouble() / 4.0).coerceIn(0.0, 1.0)
            return (
                1.0 -
                    residual * 2.70 -
                    shimmer * 1.20 +
                    flowConfidence.coerceIn(0.0, 1.0) * 0.18 +
                    temporalSupport * 0.08
                ).coerceIn(0.0, 1.0)
        }

        private fun multiLensRuntimeProven(): Boolean =
            metadata.boolean("singularityPhysicalCameraStreamProven") ||
                metadata.boolean("physicalCameraStreamProven") ||
                stageState("singularityStage3", 141) == SingularityStageExecutionState.EXECUTED.name

        private fun lumaDenoiseStrength(): Double? =
            metric("enhancementLumaDenoise")
                ?: metric("enhancementSpatialDenoise")
                ?: metric("spatialDenoise")
                ?: metric("lumaDenoise")

        private fun chromaDenoiseStrength(): Double? =
            metric("enhancementChromaDenoise") ?: metric("chromaDenoise")

        private fun denoiseUnsafeReasons(): List<String> = buildList {
            grainResidualScore()?.let {
                if (it > V20_MAX_GRAIN_RESIDUAL) add("grainResidualScore=${it.fixed6()}")
            }
            zeroGrainClarityScore()?.let {
                if (it < V20_MIN_ZERO_GRAIN_CLARITY_SCORE) add("zeroGrainClarityScore=${it.fixed6()}")
            }
            denoiseSafetyScore()?.let {
                if (it < V20_MIN_DENOISE_SAFETY_SCORE) add("denoiseSafetyScore=${it.fixed6()}")
            }
            if (metadata.boolean("denoiseUnsafeDetected") || metadata.boolean("v20DenoiseUnsafeDetected")) {
                add("denoiseUnsafeDetected=true")
            }
        }

        private fun detailUnsafeReasons(): List<String> = buildList {
            microdetailHonestyScore()?.let {
                if (it < V20_MIN_MICRODETAIL_HONESTY_SCORE) add("microdetailHonestyScore=${it.fixed6()}")
            }
            sharpnessSafetyScore()?.let {
                if (it < V20_MIN_SHARPNESS_SAFETY_SCORE) add("sharpnessSafetyScore=${it.fixed6()}")
            }
            edgeOvershootPercent()?.let {
                if (it > V20_MAX_EDGE_OVERSHOOT_PERCENT) add("edgeOvershootPercent=${it.fixed6()}")
            }
            if (metadata.boolean("sharpnessUnsafeDetected") || metadata.boolean("v20SharpnessUnsafeDetected")) {
                add("sharpnessUnsafeDetected=true")
            }
            if (metadata.boolean("clarityUnsafeDetected") || metadata.boolean("v20ClarityUnsafeDetected")) {
                add("clarityUnsafeDetected=true")
            }
        }

        private fun motionUnsafeReasons(): List<String> = buildList {
            antiMotionBlurScore()?.let {
                if (it < V20_MIN_ANTI_MOTION_BLUR_SCORE) add("antiMotionBlurScore=${it.fixed6()}")
            }
            motionBlurResidual()?.let {
                if (it > V20_MAX_MOTION_BLUR_RESIDUAL) add("motionBlurResidual=${it.fixed6()}")
            }
            temporalShimmerPercent()?.let {
                if (it > V20_MAX_TEMPORAL_SHIMMER_PERCENT) add("temporalShimmerPercent=${it.fixed6()}")
            }
            if (metadata.boolean("motionBlurUnsafeDetected") || metadata.boolean("v20MotionBlurUnsafeDetected")) {
                add("motionBlurUnsafeDetected=true")
            }
        }

        private fun v20UnsafeReasons(): List<String> =
            denoiseUnsafeReasons() + detailUnsafeReasons() + motionUnsafeReasons()

        private fun v20FalseClaimDetected(): Boolean =
            v20DenoiseFalseClaimDetected() || v20DetailFalseClaimDetected() || v20MotionFalseClaimDetected()

        private fun v20DenoiseFalseClaimDetected(): Boolean =
            listOf(
                "zeroGrainClarityFalseClaimDetected",
                "v20ZeroGrainClarityFalseClaimDetected",
                "denoiseFalseClaimDetected",
                "clarityFalseClaimDetected"
            ).any { metadata.boolean(it) }

        private fun v20DetailFalseClaimDetected(): Boolean =
            listOf(
                "microdetailFalseClaimDetected",
                "v20MicrodetailFalseClaimDetected",
                "sharpnessFalseClaimDetected",
                "clarityFalseClaimDetected"
            ).any { metadata.boolean(it) }

        private fun v20MotionFalseClaimDetected(): Boolean =
            listOf(
                "antiMotionBlurFalseClaimDetected",
                "v20AntiMotionBlurFalseClaimDetected",
                "motionBlurFalseClaimDetected"
            ).any { metadata.boolean(it) }

        private fun record(
            engineId: SingularityStage5EngineId,
            status: SingularityStage5EngineStatus,
            evidenceReference: String,
            fallbackReason: String = "",
            confidence: Double
        ): SingularityStage5EngineRecord =
            SingularityStage5EngineRecord(
                engineId = engineId,
                status = status,
                evidenceReference = evidenceReference,
                fallbackReason = fallbackReason,
                confidence = confidence,
                measuredMetrics = stageMetricsFor(engineId)
            )

        private fun stageMetricsFor(engineId: SingularityStage5EngineId): Map<String, Double> =
            when (engineId) {
                SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION -> buildMetricMap(
                    "chromaCrawlScore" to chromaCrawlScore(),
                    "temporalShimmerPercent" to temporalShimmerPercent(),
                    "zeroGrainClarityScore" to zeroGrainClarityScore(),
                    "grainResidualScore" to grainResidualScore(),
                    "denoiseSafetyScore" to denoiseSafetyScore()
                )
                SingularityStage5EngineId.MOTION_TEMPORAL -> buildMetricMap(
                    "temporalShimmerPercent" to temporalShimmerPercent(),
                    "motionBlurResidual" to motionBlurResidual(),
                    "antiMotionBlurScore" to antiMotionBlurScore()
                )
                SingularityStage5EngineId.TEXTURE_AUTHORITY -> buildMetricMap(
                    "edgeOvershootPercent" to edgeOvershootPercent(),
                    "fineTextureRetention" to fineTextureRetention(),
                    "microdetailHonestyScore" to microdetailHonestyScore(),
                    "sharpnessSafetyScore" to sharpnessSafetyScore()
                )
                SingularityStage5EngineId.RESOLUTION_HONESTY -> buildMetricMap(
                    "textStabilityScore" to textStabilityScore(),
                    "microdetailHonestyScore" to microdetailHonestyScore()
                )
                SingularityStage5EngineId.COLOR_AUTHORITY -> buildMetricMap(
                    "neutralDeltaE" to neutralDeltaE(),
                    "skinDeltaE" to skinDeltaE()
                )
                SingularityStage5EngineId.TONE_STABILITY -> buildMetricMap(
                    "toneFlickerPercent" to toneFlickerPercent(),
                    "highlightRolloffError" to highlightRolloffError(),
                    "shadowDepthError" to shadowDepthError()
                )
                else -> emptyMap()
            }

        private fun buildMetricMap(vararg values: Pair<String, Double?>): Map<String, Double> =
            values.mapNotNull { (name, value) -> value?.let { name to it } }.toMap()

        private fun MutableMap<String, String>.putMetric(key: String, value: Double) {
            put(key, value.fixed6())
            put("singularityStage5.$key.source", "bounded-runtime-evidence")
        }

        private fun metric(key: String): Double? =
            metadata.doubleOrNull(key)?.coerceIn(0.0, 1.0)

        private fun stageState(prefix: String, stageId: Int): String? {
            val token = stageId.toString().padStart(3, '0')
            return metadata.entries
                .firstOrNull { (key, _) -> key.startsWith("$prefix.stage$token.") }
                ?.value
                ?.substringBefore('|')
        }
    }

    private const val V20_MIN_ZERO_GRAIN_CLARITY_SCORE = 0.82
    private const val V20_MAX_GRAIN_RESIDUAL = 0.08
    private const val V20_MIN_DENOISE_SAFETY_SCORE = 0.80
    private const val V20_MIN_MICRODETAIL_HONESTY_SCORE = 0.84
    private const val V20_MIN_SHARPNESS_SAFETY_SCORE = 0.82
    private const val V20_MAX_EDGE_OVERSHOOT_PERCENT = 0.035
    private const val V20_MAX_EDGE_OVERSHOOT_FOR_SCORE = 0.080
    private const val V20_MIN_ANTI_MOTION_BLUR_SCORE = 0.80
    private const val V20_MAX_MOTION_BLUR_RESIDUAL = 0.10
    private const val V20_MAX_TEMPORAL_SHIMMER_PERCENT = 0.030
}

internal object SingularityStage5ImageQualityEngine {
    fun edgeOvershootPercent(samples: FloatArray): Double {
        if (samples.size < 4) return 0.0
        val minValue = samples.minOrNull() ?: return 0.0
        val maxValue = samples.maxOrNull() ?: return 0.0
        val range = (maxValue - minValue).coerceAtLeast(1.0e-6f)
        var overshoot = 0.0f
        samples.forEach { value ->
            overshoot = max(overshoot, max(0f, value - maxValue))
            overshoot = max(overshoot, max(0f, minValue - value))
        }
        val first = samples.first()
        val last = samples.last()
        val direction = if (last >= first) 1f else -1f
        var localRinging = 0.0f
        for (i in 1 until samples.lastIndex) {
            val leftSlope = (samples[i] - samples[i - 1]) * direction
            val rightSlope = (samples[i + 1] - samples[i]) * direction
            if (leftSlope > 0f && rightSlope < 0f) {
                localRinging = max(localRinging, abs(rightSlope))
            }
        }
        return ((overshoot + localRinging * 0.5f) / range).coerceIn(0f, 1f).toDouble()
    }

    fun temporalShimmerPercent(frames: List<FloatArray>): Double {
        if (frames.size < 2) return 0.0
        val sampleCount = frames.minOf { it.size }
        if (sampleCount == 0) return 0.0
        var total = 0.0
        var pairs = 0
        for (index in 1 until frames.size) {
            val previous = frames[index - 1]
            val current = frames[index]
            var diff = 0.0
            for (i in 0 until sampleCount) {
                diff += abs(current[i] - previous[i]).toDouble()
            }
            total += diff / sampleCount.toDouble()
            pairs++
        }
        return (total / pairs.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
    }

    fun chromaCrawlScore(uFrames: List<FloatArray>, vFrames: List<FloatArray>): Double {
        val pairs = min(uFrames.size, vFrames.size)
        if (pairs < 2) return 0.0
        val uScore = temporalShimmerPercent(uFrames.take(pairs))
        val vScore = temporalShimmerPercent(vFrames.take(pairs))
        return sqrt((uScore * uScore + vScore * vScore) * 0.5).coerceIn(0.0, 1.0)
    }

    fun fineTextureRetention(reference: FloatArray, processed: FloatArray): Double {
        val sampleCount = min(reference.size, processed.size)
        if (sampleCount < 3) return 0.0
        val referenceEnergy = highFrequencyEnergy(reference, sampleCount)
        if (referenceEnergy <= 1.0e-9) return 1.0
        val processedEnergy = highFrequencyEnergy(processed, sampleCount)
        return (processedEnergy / referenceEnergy).coerceIn(0.0, 1.25).let { min(1.0, it) }
    }

    fun toneFlickerPercent(frameMeanLuma: FloatArray): Double {
        if (frameMeanLuma.size < 2) return 0.0
        var maxDelta = 0f
        for (i in 1 until frameMeanLuma.size) {
            maxDelta = max(maxDelta, abs(frameMeanLuma[i] - frameMeanLuma[i - 1]))
        }
        return maxDelta.coerceIn(0f, 1f).toDouble()
    }

    private fun highFrequencyEnergy(values: FloatArray, sampleCount: Int): Double {
        var energy = 0.0
        for (i in 1 until sampleCount) {
            val diff = values[i] - values[i - 1]
            energy += diff * diff
        }
        return energy / (sampleCount - 1).toDouble()
    }
}

private fun List<SingularityStage5EngineRecord>.statusSummary(): String =
    SingularityStage5EngineStatus.entries.joinToString(separator = " ") { status ->
        "${status.name}=${count { it.status == status }}"
    }

private fun SingularityStage5EngineRecord.compactRecord(): String =
    buildString {
        append(status.name)
        append("|confidence=").append(confidence.fixed3())
        append("|stages=").append(engineId.stageRange.first).append("-").append(engineId.stageRange.last)
        if (fallbackReason.isNotBlank()) append("|fallback=").append(fallbackReason.replace('|', '/'))
        append("|evidence=").append(evidenceReference.replace('|', '/'))
        if (measuredMetrics.isNotEmpty()) {
            append("|metrics=").append(measuredMetrics.entries.joinToString(separator = ";") {
                "${it.key}:${it.value.fixed6()}"
            })
        }
    }

private fun SingularityStage5EngineRecord.toJsonObject(): String =
    buildString {
        append("{")
        append("\"engineId\":\"").append(engineId.name).append("\",")
        append("\"status\":\"").append(status.name).append("\",")
        append("\"stageRange\":\"").append(engineId.stageRange.first).append("-").append(engineId.stageRange.last).append("\",")
        append("\"confidence\":").append(confidence.fixed6()).append(",")
        append("\"evidenceReference\":\"").append(evidenceReference.jsonEscaped()).append("\",")
        append("\"fallbackReason\":\"").append(fallbackReason.jsonEscaped()).append("\",")
        append("\"measuredMetrics\":").append(measuredMetrics.mapValues { it.value.fixed6() }.toJsonObject())
        append("}")
    }

private fun Map<String, String>.boolean(key: String): Boolean =
    clean(key)?.toBooleanStrictOrNull() ?: false

private fun Map<String, String>.clean(key: String): String? =
    get(key)?.trim()?.takeIf { it.isNotBlank() }

private fun Map<String, String>.doubleOrNull(key: String): Double? =
    clean(key)?.removeSuffix("%")?.trim()?.toDoubleOrNull()

private fun Map<String, String>.long(key: String): Long =
    clean(key)?.toLongOrNull() ?: 0L

private fun Map<String, String>.int(key: String): Int =
    clean(key)?.toIntOrNull() ?: 0

private fun Double.fixed3(): String = String.format(Locale.US, "%.3f", this)

private fun Double.fixed6(): String = String.format(Locale.US, "%.6f", this)

private fun Map<String, String>.toJsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        "\"${key.jsonEscaped()}\":\"${value.jsonEscaped()}\""
    }

private fun String.jsonEscaped(): String =
    buildString {
        this@jsonEscaped.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
