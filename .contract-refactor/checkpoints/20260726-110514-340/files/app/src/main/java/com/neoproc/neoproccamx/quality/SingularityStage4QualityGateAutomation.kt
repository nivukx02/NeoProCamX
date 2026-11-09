package com.neoproc.neoproccamx.quality

import java.util.Locale

internal data class SingularityStage4QualityGateInput(
    val recordingId: String,
    val outputMode: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val expectedFrameCount: Long,
    val actualFrameCount: Long,
    val droppedFrames: Int,
    val maxAllowedDroppedFrames: Int,
    val recordedFileBytes: Long,
    val finalized: Boolean,
    val playable: Boolean,
    val previewQualitySignature: String,
    val recordingQualitySignature: String,
    val thresholdSetId: String = SingularityStage4QualityGateAutomation.DEFAULT_THRESHOLD_SET_ID,
    val thresholdSetHash: String = SingularityStage4QualityGateAutomation.DEFAULT_THRESHOLD_SET_HASH,
    val metadata: Map<String, String> = emptyMap()
)

internal data class SingularityStage4QualityGateBundle(
    val recordingId: String,
    val outputMode: String,
    val thresholdSetId: String,
    val thresholdSetHash: String,
    val results: List<SingularityQualityGateResult>,
    val artifactReferences: Map<String, String>
) {
    init {
        require(results.map { it.gateId }.toSet() == SingularityQualityGateId.entries.toSet()) {
            "Stage 4 must emit QG01..QG16 exactly once"
        }
        require(results.size == SingularityQualityGateId.entries.size) {
            "Stage 4 quality gates must be unique"
        }
    }

    val mandatoryNotPassed: List<SingularityQualityGateId>
        get() = results.filter { it.blocksHighestClass }.map { it.gateId }

    val releaseReadiness: String
        get() = when {
            results.any {
                it.gateId == SingularityQualityGateId.QG16 &&
                    it.status == SingularityQualityGateStatus.FAIL
            } -> "NO_RELEASE_METADATA_FAILURE"
            results.any { it.status == SingularityQualityGateStatus.FAIL } -> "BLOCKING_QG_FAILURE"
            mandatoryNotPassed.isNotEmpty() -> "BLOCKS_SINGULARITY_100000_UNTIL_TESTED"
            else -> "READY_FOR_RELEASE_GATE_INPUT"
        }

    fun toMetadata(prefix: String = "singularityStage4"): Map<String, String> = buildMap {
        put("${prefix}Version", SingularityStage4QualityGateAutomation.VERSION)
        put("${prefix}ReportVersion", REPORT_VERSION)
        put("${prefix}GatePolicyVersion", SingularityThresholdAssetContract.GATE_POLICY_VERSION)
        put("${prefix}ThresholdSetId", thresholdSetId)
        put("${prefix}ThresholdSetHash", thresholdSetHash)
        put("${prefix}RecordingId", recordingId)
        put("${prefix}OutputMode", outputMode)
        put("${prefix}GateCount", results.size.toString())
        put("${prefix}GateIds", results.joinToString(separator = ",") { it.gateId.name })
        put("${prefix}GateSummary", results.statusSummary())
        put("${prefix}MandatoryNotPassedIds", mandatoryNotPassed.joinToString(separator = ",") { it.name })
        put(
            "${prefix}FailureCodes",
            results.filter { it.failureCode.isNotBlank() }.joinToString(separator = ",") { it.failureCode }
        )
        put("${prefix}ReleaseReadiness", releaseReadiness)
        put("${prefix}ReportJson", toJsonText())
        artifactReferences.forEach { (key, value) ->
            put("${prefix}Artifact.$key", value)
        }
        results.forEach { result ->
            put("${prefix}.${result.gateId.name}", result.compactRecord())
        }
    }

    fun toJsonText(): String = buildString {
        append("{")
        append("\"formatVersion\":\"").append(REPORT_VERSION).append("\",")
        append("\"automationVersion\":\"").append(SingularityStage4QualityGateAutomation.VERSION).append("\",")
        append("\"gatePolicyVersion\":\"").append(SingularityThresholdAssetContract.GATE_POLICY_VERSION).append("\",")
        append("\"thresholdSetId\":\"").append(thresholdSetId.jsonEscaped()).append("\",")
        append("\"thresholdSetHash\":\"").append(thresholdSetHash.jsonEscaped()).append("\",")
        append("\"recordingId\":\"").append(recordingId.jsonEscaped()).append("\",")
        append("\"outputMode\":\"").append(outputMode.jsonEscaped()).append("\",")
        append("\"gateSummary\":\"").append(results.statusSummary().jsonEscaped()).append("\",")
        append("\"releaseReadiness\":\"").append(releaseReadiness).append("\",")
        append("\"mandatoryNotPassed\":")
        append(mandatoryNotPassed.map { it.name }.toJsonArray())
        append(",\"artifactReferences\":")
        append(artifactReferences.toJsonObject())
        append(",\"qualityGates\":[")
        append(results.joinToString(separator = ",") { it.toJsonObject() })
        append("]}")
    }

    companion object {
        const val REPORT_VERSION = "SINGULARITY_STAGE4_QG_REPORT_1"
    }
}

internal object SingularityStage4QualityGateAutomation {
    const val VERSION = "SINGULARITY_STAGE4_QG_AUTOMATION_V1"
    const val DEFAULT_THRESHOLD_SET_ID = "foundation-generic-1080p25"
    const val DEFAULT_THRESHOLD_SET_HASH = "foundation-generic-qg-v1"

    private val SPECS: Map<SingularityQualityGateId, Stage4GateSpec> = listOf(
        Stage4GateSpec(SingularityQualityGateId.QG01, "percent", 0.080, 0.120, Direction.LOWER_IS_BETTER, 247),
        Stage4GateSpec(SingularityQualityGateId.QG02, "percent", 0.020, 0.050, Direction.LOWER_IS_BETTER, 247),
        Stage4GateSpec(SingularityQualityGateId.QG03, "normalized", 0.040, 0.080, Direction.LOWER_IS_BETTER, 247),
        Stage4GateSpec(SingularityQualityGateId.QG04, "delta", 0.120, 0.200, Direction.LOWER_IS_BETTER, 247),
        Stage4GateSpec(SingularityQualityGateId.QG05, "delta", 0.150, 0.250, Direction.LOWER_IS_BETTER, 248),
        Stage4GateSpec(SingularityQualityGateId.QG06, "deltaE", 3.000, 5.000, Direction.LOWER_IS_BETTER, 248),
        Stage4GateSpec(SingularityQualityGateId.QG07, "normalized", 0.780, 0.650, Direction.HIGHER_IS_BETTER, 248),
        Stage4GateSpec(SingularityQualityGateId.QG08, "normalized", 0.800, 0.650, Direction.HIGHER_IS_BETTER, 248),
        Stage4GateSpec(SingularityQualityGateId.QG09, "normalized", 0.020, 0.060, Direction.LOWER_IS_BETTER, 249),
        Stage4GateSpec(SingularityQualityGateId.QG10, "percent", 0.030, 0.080, Direction.LOWER_IS_BETTER, 250),
        Stage4GateSpec(SingularityQualityGateId.QG11, "deltaE", 2.500, 4.500, Direction.LOWER_IS_BETTER, 250),
        Stage4GateSpec(SingularityQualityGateId.QG12, "normalized", 0.070, 0.140, Direction.LOWER_IS_BETTER, 251),
        Stage4GateSpec(SingularityQualityGateId.QG13, "delta", 0.020, 0.050, Direction.LOWER_IS_BETTER, 251),
        Stage4GateSpec(SingularityQualityGateId.QG14, "minutes", 10.000, 5.000, Direction.HIGHER_IS_BETTER, 252),
        Stage4GateSpec(SingularityQualityGateId.QG15, "count", 0.000, 2.000, Direction.LOWER_IS_BETTER, 252),
        Stage4GateSpec(SingularityQualityGateId.QG16, "boolean", 1.000, 1.000, Direction.HIGHER_IS_BETTER, 255)
    ).associateBy { it.gateId }

    fun evaluate(input: SingularityStage4QualityGateInput): SingularityStage4QualityGateBundle {
        val context = Stage4Context(input)
        val results = SingularityQualityGateId.entries.map { gateId ->
            when (gateId) {
                SingularityQualityGateId.QG01 -> context.numericGate(
                    gateId,
                    "EDGE_OVERSHOOT_EXCEEDS_LIMIT",
                    "stage4EdgeOvershootPercent",
                    "qg01EdgeOvershootPercent",
                    "edgeOvershootPercent",
                    "omegaEdgeOvershootPercent",
                    "detailEdgeOvershootPercent"
                )
                SingularityQualityGateId.QG02 -> context.numericGate(
                    gateId,
                    "TEMPORAL_SHIMMER_EXCEEDS_LIMIT",
                    "stage4TemporalShimmerPercent",
                    "qg02TemporalShimmerPercent",
                    "temporalShimmerPercent"
                )
                SingularityQualityGateId.QG03 -> context.numericGate(
                    gateId,
                    "CHROMA_CRAWL_EXCEEDS_LIMIT",
                    "stage4ChromaCrawlScore",
                    "qg03ChromaCrawlScore",
                    "chromaCrawlScore"
                )
                SingularityQualityGateId.QG04 -> context.numericGate(
                    gateId,
                    "HIGHLIGHT_ROLLOFF_EXCEEDS_LIMIT",
                    "stage4HighlightRolloffError",
                    "qg04HighlightRolloffError",
                    "highlightRolloffError"
                )
                SingularityQualityGateId.QG05 -> context.numericGate(
                    gateId,
                    "SHADOW_DEPTH_ERROR_EXCEEDS_LIMIT",
                    "stage4ShadowDepthError",
                    "qg05ShadowDepthError",
                    "shadowDepthError"
                )
                SingularityQualityGateId.QG06 -> context.numericGate(
                    gateId,
                    "SKIN_DELTA_E_EXCEEDS_LIMIT",
                    "stage4SkinDeltaE",
                    "qg06SkinDeltaE",
                    "skinDeltaE",
                    "skinToneDeltaE"
                )
                SingularityQualityGateId.QG07 -> context.numericGate(
                    gateId,
                    "FINE_TEXTURE_RETENTION_BELOW_LIMIT",
                    "stage4FineTextureRetention",
                    "qg07FineTextureRetention",
                    "fineTextureRetention",
                    "detailTextureConfidence"
                )
                SingularityQualityGateId.QG08 -> context.numericGate(
                    gateId,
                    "TEXT_STABILITY_BELOW_LIMIT",
                    "stage4TextStabilityScore",
                    "qg08TextStabilityScore",
                    "textStabilityScore"
                )
                SingularityQualityGateId.QG09 -> context.neuralResolutionHonesty()
                SingularityQualityGateId.QG10 -> context.numericGate(
                    gateId,
                    "TONE_FLICKER_EXCEEDS_LIMIT",
                    "stage4ToneFlickerPercent",
                    "qg10ToneFlickerPercent",
                    "toneFlickerPercent"
                )
                SingularityQualityGateId.QG11 -> context.numericGate(
                    gateId,
                    "NEUTRAL_DELTA_E_EXCEEDS_LIMIT",
                    "stage4NeutralDeltaE",
                    "qg11NeutralDeltaE",
                    "neutralDeltaE",
                    "colorNeutralDeltaE",
                    "omegaColorDeltaE"
                )
                SingularityQualityGateId.QG12 -> context.encodeSurvival()
                SingularityQualityGateId.QG13 -> context.previewMatch()
                SingularityQualityGateId.QG14 -> context.thermalEndurance()
                SingularityQualityGateId.QG15 -> context.droppedFrameControl()
                SingularityQualityGateId.QG16 -> context.metadataHonesty()
            }
        }
        return SingularityStage4QualityGateBundle(
            recordingId = input.recordingId,
            outputMode = input.outputMode,
            thresholdSetId = input.thresholdSetId.ifBlank { DEFAULT_THRESHOLD_SET_ID },
            thresholdSetHash = input.thresholdSetHash.ifBlank { DEFAULT_THRESHOLD_SET_HASH },
            results = results,
            artifactReferences = context.artifactReferences()
        )
    }

    private class Stage4Context(private val input: SingularityStage4QualityGateInput) {
        private val metadata: Map<String, String> = input.metadata

        fun numericGate(
            gateId: SingularityQualityGateId,
            failureCode: String,
            vararg keys: String
        ): SingularityQualityGateResult {
            val spec = spec(gateId)
            val metric = metric(*keys)
                ?: return notTested(spec, "${gateId.name}_MEASURED_ARTIFACT_MISSING")
            return measured(spec, metric, failureCode)
        }

        fun neuralResolutionHonesty(): SingularityQualityGateResult {
            val spec = spec(SingularityQualityGateId.QG09)
            val neuralClaimed = metadata.boolean("stage4NeuralResolutionClaimed") ||
                metadata.boolean("neuralResolutionClaimed") ||
                (181..188).any { id ->
                    val state = stageState("singularityStage3", id)
                    state == SingularityStageExecutionState.EXECUTED.name ||
                        state == SingularityStageExecutionState.FALLBACK_EXECUTED.name
                }
            if (!neuralClaimed) {
                return SingularityQualityGateResult(
                    gateId = spec.gateId,
                    status = SingularityQualityGateStatus.NOT_APPLICABLE,
                    mandatory = true,
                    measuredValue = null,
                    threshold = spec.thresholdLabel,
                    unit = spec.unit,
                    evidenceReference = "stage4:${spec.gateId.name}:neural-resolution-inactive",
                    failureCode = "",
                    responsibleStageId = spec.responsibleStageId,
                    fallbackDecision = "NEURAL_RESOLUTION_DISABLED_NO_CLAIM",
                    recordingImpact = "Blocks SINGULARITY-100000 until a measured neural-resolution path is active or the release policy marks it optional."
                )
            }
            val metric = metric(
                "stage4HallucinationRisk",
                "qg09HallucinationRisk",
                "hallucinationRisk",
                "neuralHallucinationRisk"
            ) ?: return notTested(spec, "QG09_HALLUCINATION_RISK_ARTIFACT_MISSING")
            return measured(spec, metric, "NEURAL_HALLUCINATION_RISK_EXCEEDS_LIMIT")
        }

        fun encodeSurvival(): SingularityQualityGateResult {
            val spec = spec(SingularityQualityGateId.QG12)
            if (!input.finalized || input.recordedFileBytes <= 0L || stageState("singularityStage2", 238) == SingularityStageExecutionState.FAILED_RECORDING_STOPPED.name) {
                return failed(
                    spec = spec,
                    measuredValue = 1.0,
                    evidenceReference = "stage4:QG12:container-finalization",
                    failureCode = "QG12_OUTPUT_NOT_FINALIZED",
                    recordingImpact = "Output cannot pass encode survival without a finalized non-empty file."
                )
            }
            if (!input.playable) {
                return notTested(spec, "QG12_PLAYBACK_OR_DECODE_PROBE_MISSING")
            }
            val metric = metric(
                "stage4EncodedVsPreEncodeLoss",
                "qg12EncodedVsPreEncodeLoss",
                "encodedVsPreEncodeLoss"
            ) ?: return notTested(spec, "QG12_ENCODED_VS_PRE_ENCODE_ARTIFACT_MISSING")
            return measured(spec, metric, "ENCODE_SURVIVAL_LOSS_EXCEEDS_LIMIT")
        }

        fun previewMatch(): SingularityQualityGateResult {
            val spec = spec(SingularityQualityGateId.QG13)
            val parityFailed = metadata.clean("previewRecordingParityStatus").equals("FAIL", ignoreCase = true) ||
                metadata.clean("stage3PreviewRecordingParityStatus").equals("FAIL", ignoreCase = true) ||
                stageState("singularityStage3", 216) == SingularityStageExecutionState.FAILED_WITH_DOWNGRADE.name ||
                (
                    input.previewQualitySignature.isNotBlank() &&
                        input.recordingQualitySignature.isNotBlank() &&
                        input.previewQualitySignature != input.recordingQualitySignature
                    )
            if (parityFailed) {
                return failed(
                    spec = spec,
                    measuredValue = 1.0,
                    evidenceReference = "stage4:QG13:preview-recording-parity",
                    failureCode = "QG13_PREVIEW_RECORDING_PARITY_FAILED",
                    recordingImpact = "Preview/file parity failed and blocks high-end release."
                )
            }
            val metric = metric(
                "stage4PreviewFileDelta",
                "qg13PreviewFileDelta",
                "previewFileDelta",
                "previewRecordingParityDelta"
            ) ?: return notTested(spec, "QG13_PREVIEW_FILE_COMPARISON_ARTIFACT_MISSING")
            return measured(spec, metric, "PREVIEW_FILE_DELTA_EXCEEDS_LIMIT")
        }

        fun thermalEndurance(): SingularityQualityGateResult {
            val spec = spec(SingularityQualityGateId.QG14)
            if (
                metadata.boolean("criticalThermalCollapse") ||
                metadata.boolean("thermalCriticalCollapse") ||
                metadata.boolean("encoderThermalDowngradeActive")
            ) {
                return failed(
                    spec = spec,
                    measuredValue = 0.0,
                    evidenceReference = "stage4:QG14:thermal-collapse",
                    failureCode = "QG14_CRITICAL_THERMAL_COLLAPSE",
                    recordingImpact = "Critical thermal collapse blocks sustained high-end release."
                )
            }
            val metric = metric(
                "stage4ValidatedEnduranceMinutes",
                "qg14ValidatedEnduranceMinutes",
                "validatedEnduranceMinutes",
                "thermalEnduranceMinutes"
            ) ?: return notTested(spec, "QG14_ENDURANCE_ARTIFACT_MISSING")
            return measured(spec, metric, "THERMAL_ENDURANCE_BELOW_LIMIT")
        }

        fun droppedFrameControl(): SingularityQualityGateResult {
            val dynamicSpec = spec(SingularityQualityGateId.QG15).copy(
                pass = input.maxAllowedDroppedFrames.toDouble().coerceAtLeast(0.0),
                warn = input.maxAllowedDroppedFrames.toDouble().coerceAtLeast(0.0) + 2.0
            )
            if (input.expectedFrameCount <= 0L || input.actualFrameCount <= 0L) {
                return notTested(dynamicSpec, "QG15_CADENCE_EVIDENCE_MISSING")
            }
            val cadenceDeficit = (input.expectedFrameCount - input.actualFrameCount)
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val effectiveDroppedFrames = maxOf(input.droppedFrames, cadenceDeficit)
            return measured(
                spec = dynamicSpec,
                metric = Metric(
                    value = effectiveDroppedFrames.toDouble().coerceAtLeast(0.0),
                    evidenceReference =
                        "stage4:QG15:reportedDropped=${input.droppedFrames};effectiveDropped=$effectiveDroppedFrames;" +
                            "expected=${input.expectedFrameCount};actual=${input.actualFrameCount}"
                ),
                failureCode = "DROPPED_FRAME_LIMIT_EXCEEDED"
            )
        }

        fun metadataHonesty(): SingularityQualityGateResult {
            val spec = spec(SingularityQualityGateId.QG16)
            val failures = buildList {
                if (metadata["singularityStage2MappedStageCount"] != "48") add("STAGE2_EVIDENCE_MISSING")
                if (metadata["singularityStage3MappedStageCount"] != "192") add("STAGE3_EVIDENCE_MISSING")
                if (!metadata.boolean("proprietaryProfilePreserved", default = true) ||
                    !metadata.boolean("singularityStage3ProprietaryProfilePreserved", default = true)
                ) {
                    add("PROPRIETARY_PROFILE_NOT_PRESERVED")
                }
                listOf(
                    "vendorHdrCertification",
                    "officialHdr10PlusMetadataWritten",
                    "vendorCertifiedHdr10Plus",
                    "vendorCertifiedDolbyVision",
                    "dolbyRpuWritten",
                    "externalHdrStandardSubstitution",
                    "singularityStage3VendorHdrCertificationClaimed",
                    "officialImaxCertificationClaimed",
                    "officialDolbyVisionCertificationClaimed",
                    "officialHdr10PlusCertificationClaimed",
                    "officialHdr10CertificationClaimed",
                    "metadataFalseClaimDetected"
                ).forEach { key ->
                    if (metadata.boolean(key)) add("${key.uppercase(Locale.US)}_FALSE_CLAIM")
                }
            }
            return if (failures.isEmpty()) {
                SingularityQualityGateResult(
                    gateId = spec.gateId,
                    status = SingularityQualityGateStatus.PASS,
                    mandatory = true,
                    measuredValue = 1.0,
                    threshold = spec.thresholdLabel,
                    unit = spec.unit,
                    evidenceReference = "stage4:QG16:stage2-stage3-vendor-claim-audit",
                    failureCode = "",
                    responsibleStageId = spec.responsibleStageId,
                    fallbackDecision = "NONE",
                    recordingImpact = "NONE"
                )
            } else {
                failed(
                    spec = spec,
                    measuredValue = 0.0,
                    evidenceReference = "stage4:QG16:${failures.joinToString(separator = ";")}",
                    failureCode = failures.first(),
                    recordingImpact = "False or missing metadata evidence blocks release."
                )
            }
        }

        fun artifactReferences(): Map<String, String> = buildMap {
            (metadata.clean("stage4EncodedVsPreEncodeLossArtifact")
                ?: metadata.clean("encodedVsPreEncodeLossArtifact"))
                ?.takeIf { it.isNotBlank() }?.let {
                    put("encodedVsPreEncode", it)
                }
            (metadata.clean("stage4PreviewFileComparisonArtifact")
                ?: metadata.clean("previewFileComparisonArtifact"))
                ?.takeIf { it.isNotBlank() }?.let {
                    put("previewFileComparison", it)
                }
            metadata.clean("stage4SceneMatrixArtifact")?.takeIf { it.isNotBlank() }?.let {
                put("sceneMatrix", it)
            }
            metadata.clean("stage4EnduranceArtifact")?.takeIf { it.isNotBlank() }?.let {
                put("endurance", it)
            }
        }

        private fun metric(vararg keys: String): Metric? =
            keys.asSequence()
                .mapNotNull { key ->
                    val value = metadata.clean(key)?.numericValueOrNull()
                    value?.let { Metric(it, "stage4:$key") }
                }
                .firstOrNull()

        private fun measured(
            spec: Stage4GateSpec,
            metric: Metric,
            failureCode: String
        ): SingularityQualityGateResult {
            val status = when (spec.direction) {
                Direction.LOWER_IS_BETTER -> when {
                    metric.value <= spec.pass -> SingularityQualityGateStatus.PASS
                    metric.value <= spec.warn -> SingularityQualityGateStatus.WARN
                    else -> SingularityQualityGateStatus.FAIL
                }
                Direction.HIGHER_IS_BETTER -> when {
                    metric.value >= spec.pass -> SingularityQualityGateStatus.PASS
                    metric.value >= spec.warn -> SingularityQualityGateStatus.WARN
                    else -> SingularityQualityGateStatus.FAIL
                }
            }
            return SingularityQualityGateResult(
                gateId = spec.gateId,
                status = status,
                mandatory = true,
                measuredValue = metric.value,
                threshold = spec.thresholdLabel,
                unit = spec.unit,
                evidenceReference = metric.evidenceReference,
                failureCode = if (status == SingularityQualityGateStatus.FAIL) failureCode else "",
                responsibleStageId = spec.responsibleStageId,
                fallbackDecision = if (status == SingularityQualityGateStatus.PASS) "NONE" else "QUALITY_GATE_REVIEW_REQUIRED",
                recordingImpact = when (status) {
                    SingularityQualityGateStatus.PASS -> "NONE"
                    SingularityQualityGateStatus.WARN -> "Caps or reviews highest class until calibrated device review accepts warning."
                    SingularityQualityGateStatus.FAIL -> "Blocks the gate's Singularity release class."
                    else -> "Blocks SINGULARITY-100000 until measured."
                }
            )
        }

        private fun notTested(
            spec: Stage4GateSpec,
            reason: String
        ): SingularityQualityGateResult =
            SingularityQualityGateResult(
                gateId = spec.gateId,
                status = SingularityQualityGateStatus.NOT_TESTED,
                mandatory = true,
                measuredValue = null,
                threshold = spec.thresholdLabel,
                unit = spec.unit,
                evidenceReference = "stage4:${spec.gateId.name}:not-tested",
                failureCode = "",
                responsibleStageId = spec.responsibleStageId,
                fallbackDecision = reason,
                recordingImpact = "Blocks SINGULARITY-100000 until deterministic artifact evidence exists."
            )

        private fun failed(
            spec: Stage4GateSpec,
            measuredValue: Double,
            evidenceReference: String,
            failureCode: String,
            recordingImpact: String
        ): SingularityQualityGateResult =
            SingularityQualityGateResult(
                gateId = spec.gateId,
                status = SingularityQualityGateStatus.FAIL,
                mandatory = true,
                measuredValue = measuredValue,
                threshold = spec.thresholdLabel,
                unit = spec.unit,
                evidenceReference = evidenceReference,
                failureCode = failureCode,
                responsibleStageId = spec.responsibleStageId,
                fallbackDecision = "QUALITY_GATE_FAILED",
                recordingImpact = recordingImpact
            )

        private fun stageState(prefix: String, stageId: Int): String? {
            val token = stageId.toString().padStart(3, '0')
            return metadata.entries
                .firstOrNull { (key, _) -> key.startsWith("$prefix.stage$token.") }
                ?.value
                ?.substringBefore('|')
        }
    }

    private fun spec(gateId: SingularityQualityGateId): Stage4GateSpec =
        SPECS.getValue(gateId)
}

private enum class Direction {
    LOWER_IS_BETTER,
    HIGHER_IS_BETTER
}

private data class Stage4GateSpec(
    val gateId: SingularityQualityGateId,
    val unit: String,
    val pass: Double,
    val warn: Double,
    val direction: Direction,
    val responsibleStageId: Int
) {
    val thresholdLabel: String
        get() = when (direction) {
            Direction.LOWER_IS_BETTER ->
                "foundation-generic ${gateId.name} pass<=${pass.fixed3()} warn<=${warn.fixed3()}"
            Direction.HIGHER_IS_BETTER ->
                "foundation-generic ${gateId.name} pass>=${pass.fixed3()} warn>=${warn.fixed3()}"
        }
}

private data class Metric(
    val value: Double,
    val evidenceReference: String
)

private fun Map<String, String>.boolean(key: String, default: Boolean = false): Boolean =
    clean(key)?.toBooleanStrictOrNull() ?: default

private fun Map<String, String>.clean(key: String): String? =
    get(key)?.trim()?.takeIf { it.isNotBlank() }

private fun String.numericValueOrNull(): Double? {
    val stripped = trim().removeSuffix("%").trim()
    return stripped.toDoubleOrNull()
}

private fun List<SingularityQualityGateResult>.statusSummary(): String =
    SingularityQualityGateStatus.entries.joinToString(separator = " ") { status ->
        "${status.name}=${count { it.status == status }}"
    }

private fun SingularityQualityGateResult.compactRecord(): String =
    buildString {
        append(status.name)
        append("|mandatory=").append(mandatory)
        append("|measured=").append(measuredValue?.fixed4() ?: "NA")
        append("|threshold=").append(threshold.replace('|', '/'))
        append("|unit=").append(unit)
        append("|stage=").append(responsibleStageId ?: "")
        if (failureCode.isNotBlank()) append("|failure=").append(failureCode)
        append("|fallback=").append(fallbackDecision.replace('|', '/'))
        append("|evidence=").append(evidenceReference.replace('|', '/'))
    }

private fun SingularityQualityGateResult.toJsonObject(): String =
    buildString {
        append("{")
        append("\"gateId\":\"").append(gateId.name).append("\",")
        append("\"title\":\"").append(gateId.title.jsonEscaped()).append("\",")
        append("\"status\":\"").append(status.name).append("\",")
        append("\"mandatory\":").append(mandatory).append(",")
        append("\"measuredValue\":").append(measuredValue?.fixed6() ?: "null").append(",")
        append("\"threshold\":\"").append(threshold.jsonEscaped()).append("\",")
        append("\"unit\":\"").append(unit.jsonEscaped()).append("\",")
        append("\"evidenceReference\":\"").append(evidenceReference.jsonEscaped()).append("\",")
        append("\"failureCode\":\"").append(failureCode.jsonEscaped()).append("\",")
        append("\"responsibleStageId\":").append(responsibleStageId ?: "null").append(",")
        append("\"fallbackDecision\":\"").append(fallbackDecision.jsonEscaped()).append("\",")
        append("\"recordingImpact\":\"").append(recordingImpact.jsonEscaped()).append("\"")
        append("}")
    }

private fun Double.fixed3(): String = String.format(Locale.US, "%.3f", this)

private fun Double.fixed4(): String = String.format(Locale.US, "%.4f", this)

private fun Double.fixed6(): String = String.format(Locale.US, "%.6f", this)

private fun List<String>.toJsonArray(): String =
    joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${it.jsonEscaped()}\"" }

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
