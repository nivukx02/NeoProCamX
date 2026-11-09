package com.neoproc.neoproccamx.quality

import java.util.Locale

internal data class SingularityStage5QcEvidenceInput(
    val recordingId: String,
    val stage2Mapped: Boolean,
    val stage3Mapped: Boolean,
    val stage5EnginesMapped: Boolean,
    val transportClassification: SingularityTransportClassification,
    val qualityGateResults: List<SingularityQualityGateResult>,
    val metadataFalseClaimDetected: Boolean,
    val sidecarPlanReady: Boolean,
    val outputFinalized: Boolean,
    val outputPlayable: Boolean
)

internal data class SingularityStage5QcEvidenceBundle(
    val stages: List<SingularityStageEvidence>
) {
    init {
        require(stages.map { it.globalStageId } == STAGE_RANGE.toList()) {
            "Stage 5 QC evidence must cover stages 240..255 in order"
        }
    }

    fun toMetadata(prefix: String = "singularityStage5Qc"): Map<String, String> = buildMap {
        put("${prefix}Version", VERSION)
        put("${prefix}MappedStageCount", stages.size.toString())
        put("${prefix}StageIds", stages.joinToString(separator = ",") { it.globalStageId.toString().padStart(3, '0') })
        put("${prefix}ExecutionSummary", stages.executionSummary())
        put("${prefix}FailureStageIds", stages.filter { it.isBlockingFailure }.joinToString(separator = ",") { it.globalStageId.toString().padStart(3, '0') })
        stages.forEach { stage ->
            put("${prefix}.stage${stage.globalStageId.toString().padStart(3, '0')}.${stage.stageName}", stage.compactRecord())
        }
    }

    companion object {
        const val VERSION = "SINGULARITY_STAGE5_QC_METADATA_V1"
        val STAGE_RANGE: IntRange = 240..255
    }
}

internal object SingularityStage5QcEvidenceMapper {
    fun map(input: SingularityStage5QcEvidenceInput): SingularityStage5QcEvidenceBundle =
        SingularityStage5QcEvidenceBundle(
            stages = SingularityStage5QcEvidenceBundle.STAGE_RANGE.map { id ->
                stage(id, signalFor(id, input))
            }
        )

    private fun signalFor(
        id: Int,
        input: SingularityStage5QcEvidenceInput
    ): QcSignal {
        val gates = input.qualityGateResults.associateBy { it.gateId }
        fun gateState(vararg gateIds: SingularityQualityGateId): QcSignal {
            val selected = gateIds.mapNotNull { gates[it] }
            if (selected.size != gateIds.size) {
                return QcSignal(
                    state = SingularityStageExecutionState.DISABLED_BY_DEVICE,
                    backend = "SINGULARITY_QG_AUTOMATION",
                    fallbackId = "QUALITY_GATE_EVIDENCE_MISSING",
                    fallbackReason = "One or more required quality gates are missing.",
                    evidence = listOf("recordingId=${input.recordingId}")
                )
            }
            val failed = selected.firstOrNull { it.status == SingularityQualityGateStatus.FAIL }
            if (failed != null) {
                return QcSignal(
                    state = if (failed.gateId == SingularityQualityGateId.QG16) {
                        SingularityStageExecutionState.FAILED_WITH_DOWNGRADE
                    } else {
                        SingularityStageExecutionState.FALLBACK_EXECUTED
                    },
                    backend = "SINGULARITY_QG_AUTOMATION",
                    fallbackId = failed.failureCode.ifBlank { "${failed.gateId.name}_FAILED" },
                    fallbackReason = failed.recordingImpact,
                    evidence = selected.map { "${it.gateId.name}=${it.status.name}" }
                )
            }
            val notPassed = selected.filter { it.status != SingularityQualityGateStatus.PASS }
            return if (notPassed.isEmpty()) {
                QcSignal(
                    state = SingularityStageExecutionState.EXECUTED,
                    backend = "SINGULARITY_QG_AUTOMATION",
                    evidence = selected.map { "${it.gateId.name}=PASS" }
                )
            } else {
                QcSignal(
                    state = SingularityStageExecutionState.FALLBACK_EXECUTED,
                    backend = "SINGULARITY_QG_AUTOMATION",
                    fallbackId = "QUALITY_GATE_NOT_ALL_PASS",
                    fallbackReason = "Mandatory gate evidence exists but is not all PASS.",
                    evidence = selected.map { "${it.gateId.name}=${it.status.name}" }
                )
            }
        }
        return when (id) {
            240 -> if (input.stage2Mapped && input.stage3Mapped && input.stage5EnginesMapped) {
                QcSignal(SingularityStageExecutionState.EXECUTED, "SINGULARITY_STAGE_LEDGER", evidence = listOf("stage2=true", "stage3=true", "stage5Engines=true"))
            } else {
                QcSignal(SingularityStageExecutionState.FALLBACK_EXECUTED, "SINGULARITY_STAGE_LEDGER", "STAGE_LEDGER_PARTIAL", "One or more evidence blocks are incomplete.", listOf("stage2=${input.stage2Mapped}", "stage3=${input.stage3Mapped}", "stage5Engines=${input.stage5EnginesMapped}"))
            }
            241 -> QcSignal(SingularityStageExecutionState.EXECUTED, "SMF_SINGULARITY_ENVELOPE", evidence = listOf("smfEnvelopePlanned=true"))
            242 -> if (input.stage2Mapped) QcSignal(SingularityStageExecutionState.EXECUTED, "CAMERA2_EVIDENCE", evidence = listOf("stage2Capture=true")) else QcSignal(SingularityStageExecutionState.DISABLED_BY_DEVICE, "CAMERA2_EVIDENCE", "CAMERA_EVIDENCE_MISSING", "Camera evidence is incomplete.", listOf())
            243 -> if (input.transportClassification != SingularityTransportClassification.UNPROVEN) QcSignal(SingularityStageExecutionState.EXECUTED, "TRANSPORT_EVIDENCE", evidence = listOf("transport=${input.transportClassification.name}")) else QcSignal(SingularityStageExecutionState.DISABLED_BY_DEVICE, "TRANSPORT_EVIDENCE", "TRANSPORT_UNPROVEN", "Transport classification is unproven.", listOf("transport=UNPROVEN"))
            244 -> QcSignal(SingularityStageExecutionState.FALLBACK_EXECUTED, "MODEL_MANIFEST_EVIDENCE", "NEURAL_OPTIONAL_OR_UNAVAILABLE", "Model authorities are recorded; unavailable neural SR remains disabled unless runtime proof exists.", listOf("neuralRuntimeTruth=visible"))
            245 -> if (input.outputFinalized && input.outputPlayable) QcSignal(SingularityStageExecutionState.EXECUTED, "ENCODER_OUTPUT_EVIDENCE", evidence = listOf("finalized=true", "playable=true")) else QcSignal(SingularityStageExecutionState.FALLBACK_EXECUTED, "ENCODER_OUTPUT_EVIDENCE", "OUTPUT_PLAYABILITY_NOT_FULLY_PROVEN", "Finalized/playable proof is incomplete.", listOf("finalized=${input.outputFinalized}", "playable=${input.outputPlayable}"))
            246 -> QcSignal(SingularityStageExecutionState.EXECUTED, "THERMAL_MEMORY_DROP_EVIDENCE", evidence = listOf("qg14=${gates[SingularityQualityGateId.QG14]?.status?.name ?: "MISSING"}", "qg15=${gates[SingularityQualityGateId.QG15]?.status?.name ?: "MISSING"}"))
            247 -> gateState(SingularityQualityGateId.QG01, SingularityQualityGateId.QG02, SingularityQualityGateId.QG03, SingularityQualityGateId.QG04)
            248 -> gateState(SingularityQualityGateId.QG05, SingularityQualityGateId.QG06, SingularityQualityGateId.QG07, SingularityQualityGateId.QG08)
            249 -> gateState(SingularityQualityGateId.QG09)
            250 -> gateState(SingularityQualityGateId.QG10, SingularityQualityGateId.QG11)
            251 -> gateState(SingularityQualityGateId.QG12, SingularityQualityGateId.QG13)
            252 -> gateState(SingularityQualityGateId.QG14, SingularityQualityGateId.QG15, SingularityQualityGateId.QG16)
            253 -> QcSignal(SingularityStageExecutionState.EXECUTED, "SINGULARITY_RELEASE_GATE", evidence = listOf("releaseGateInputReady=true"))
            254 -> if (input.sidecarPlanReady) QcSignal(SingularityStageExecutionState.EXECUTED, "SIDECAR_FINALIZATION", evidence = listOf("stage4AndStage5Sidecars=true")) else QcSignal(SingularityStageExecutionState.FALLBACK_EXECUTED, "SIDECAR_FINALIZATION", "SIDECAR_PLAN_INCOMPLETE", "Sidecar finalization plan is incomplete.", listOf())
            255 -> if (input.metadataFalseClaimDetected) QcSignal(SingularityStageExecutionState.FAILED_WITH_DOWNGRADE, "FINAL_RELEASE_SEAL", "METADATA_FALSE_CLAIM_DETECTED", "Metadata honesty failed; final seal cannot claim a high class.", listOf("metadataFalseClaim=true")) else QcSignal(SingularityStageExecutionState.EXECUTED, "FINAL_RELEASE_SEAL", evidence = listOf("metadataFalseClaim=false"))
            else -> error("Unknown Stage 5 QC stage $id")
        }
    }

    private fun stage(id: Int, signal: QcSignal): SingularityStageEvidence {
        val definition = SingularityFrameGraphRegistry.stageById(id)
            ?: error("Unknown Singularity Stage 5 QC id $id")
        return SingularityStageEvidence.fromDefinition(
            definition = definition,
            executionBackend = signal.backend,
            executionState = signal.state,
            fallbackId = signal.fallbackId,
            fallbackReason = signal.fallbackReason,
            confidence = if (signal.state == SingularityStageExecutionState.EXECUTED) 1.0 else 0.65,
            evidenceReferences = signal.evidence.ifEmpty { listOf("stage5qc:${definition.smfRecordKey}") }
        )
    }

    private data class QcSignal(
        val state: SingularityStageExecutionState,
        val backend: String,
        val fallbackId: String = "",
        val fallbackReason: String = "",
        val evidence: List<String> = emptyList()
    )
}

private fun List<SingularityStageEvidence>.executionSummary(): String =
    SingularityStageExecutionState.entries.joinToString(separator = " ") { state ->
        "${state.name}=${count { it.executionState == state }}"
    }

private fun SingularityStageEvidence.compactRecord(): String =
    buildString {
        append(executionState.name)
        append("|backend=").append(executionBackend)
        append("|confidence=").append(String.format(Locale.US, "%.3f", confidence))
        if (fallbackId.isNotBlank()) append("|fallbackId=").append(fallbackId)
        if (fallbackReason.isNotBlank()) append("|fallbackReason=").append(fallbackReason.replace('|', '/'))
        append("|refs=").append(evidenceReferences.joinToString(separator = ";").replace('|', '/'))
    }
