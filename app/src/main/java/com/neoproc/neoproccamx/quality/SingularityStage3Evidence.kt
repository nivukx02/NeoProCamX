package com.neoproc.neoproccamx.quality

import java.util.Locale

internal enum class SingularityStage3SignalState {
    PROVEN,
    IDENTITY,
    FALLBACK,
    SOURCE_AVAILABLE_ONLY,
    DISABLED_BY_MODE,
    UNAVAILABLE,
    FAILED
}

internal data class SingularityStage3StageSignal(
    val state: SingularityStage3SignalState,
    val backend: String,
    val inputResources: List<String> = emptyList(),
    val outputResources: List<String> = emptyList(),
    val fallbackId: String = "",
    val fallbackReason: String = "",
    val gpuDurationNs: Long = 0L,
    val nnapiDurationNs: Long = 0L,
    val cpuControlDurationNs: Long = 0L,
    val confidence: Double = 1.0,
    val evidenceReferences: List<String> = emptyList()
)

internal data class SingularityStage3PipelineEvidence(
    val recordingId: String = "",
    val outputMode: String = "",
    val proprietaryProfilePreserved: Boolean = true,
    val vendorHdrCertificationClaimed: Boolean = false,
    val previewRecordingParityStatus: String = "",
    val previewRecordingParityFailureCodes: String = "",
    val stageSignals: Map<Int, SingularityStage3StageSignal>,
    val evidenceReferences: List<String> = emptyList()
)

internal data class SingularityStage3EvidenceBundle(
    val stages: List<SingularityStageEvidence>,
    val proprietaryProfilePreserved: Boolean,
    val vendorHdrCertificationClaimed: Boolean,
    val previewRecordingParityStatus: String,
    val previewRecordingParityFailureCodes: String
) {
    init {
        require(stages.map { it.globalStageId } == STAGE_RANGE.toList()) {
            "Stage 3 evidence must cover stages 032..223 in order"
        }
    }

    fun toMetadata(prefix: String = "singularityStage3"): Map<String, String> = buildMap {
        put("${prefix}Version", VERSION)
        put("${prefix}MappedStageCount", stages.size.toString())
        put("${prefix}StageIds", stages.stageIdsCsv())
        put("${prefix}BlockIds", (2..13).joinToString(separator = ",") { it.toString().padStart(2, '0') })
        put("${prefix}ExecutionSummary", stages.executionSummary())
        put("${prefix}ReductionStageIds", stages.filter { it.isReduction }.stageIdsCsv())
        put("${prefix}FailureStageIds", stages.filter { it.isBlockingFailure }.stageIdsCsv())
        put("${prefix}SourceOnlyStageIds", stages.filter { stage ->
            stage.fallbackId == SOURCE_AVAILABLE_RUNTIME_NOT_PROVEN
        }.stageIdsCsv())
        put("${prefix}IdentityStageIds", stages.filter {
            it.executionState == SingularityStageExecutionState.IDENTITY_PASS
        }.stageIdsCsv())
        put("${prefix}ProprietaryProfilePreserved", proprietaryProfilePreserved.toString())
        put("${prefix}VendorHdrCertificationClaimed", vendorHdrCertificationClaimed.toString())
        put("${prefix}PreviewRecordingParityStatus", previewRecordingParityStatus)
        put("${prefix}PreviewRecordingParityFailureCodes", previewRecordingParityFailureCodes)
        blockSummaries().forEach { (block, summary) ->
            put("${prefix}.block${block.toString().padStart(2, '0')}", summary)
        }
        stages.forEach { stage ->
            put("${prefix}.stage${stage.globalStageId.toStageIdToken()}.${stage.stageName}", stage.compactRecord())
        }
    }

    private fun blockSummaries(): Map<Int, String> =
        stages
            .groupBy { it.blockIndex }
            .mapValues { (_, blockStages) -> blockStages.executionSummary() }

    companion object {
        const val VERSION = "SINGULARITY_STAGE3_EXISTING_PIPELINE_MAP_V1"
        val STAGE_RANGE: IntRange = 32..223
        const val SOURCE_AVAILABLE_RUNTIME_NOT_PROVEN = "SOURCE_AVAILABLE_RUNTIME_NOT_PROVEN"
    }
}

internal object SingularityStage3EvidenceMapper {
    fun map(evidence: SingularityStage3PipelineEvidence): SingularityStage3EvidenceBundle =
        SingularityStage3EvidenceBundle(
            stages = SingularityStage3EvidenceBundle.STAGE_RANGE.map { id ->
                stage(id, evidence.stageSignals[id] ?: defaultSignalFor(id, evidence))
            },
            proprietaryProfilePreserved = evidence.proprietaryProfilePreserved,
            vendorHdrCertificationClaimed = evidence.vendorHdrCertificationClaimed,
            previewRecordingParityStatus = evidence.previewRecordingParityStatus,
            previewRecordingParityFailureCodes = evidence.previewRecordingParityFailureCodes
        )

    private fun defaultSignalFor(
        id: Int,
        evidence: SingularityStage3PipelineEvidence
    ): SingularityStage3StageSignal {
        val definition = SingularityFrameGraphRegistry.stageById(id)
            ?: error("Unknown Singularity Stage 3 id $id")
        return if (id in 217..222) {
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.IDENTITY,
                backend = "CLEAN_PRESENTATION_POLICY",
                outputResources = listOf("creative_stage_identity_in_clean_detail_profile"),
                evidenceReferences = evidence.evidenceReferences + "stage3:${definition.smfRecordKey}"
            )
        } else {
            SingularityStage3StageSignal(
                state = SingularityStage3SignalState.UNAVAILABLE,
                backend = "SINGULARITY_STAGE3_EVIDENCE",
                fallbackId = "STAGE3_RUNTIME_EVIDENCE_MISSING",
                fallbackReason = "No runtime evidence was provided for ${definition.stageName}.",
                evidenceReferences = evidence.evidenceReferences + "stage3:${definition.smfRecordKey}"
            )
        }
    }

    private fun stage(id: Int, signal: SingularityStage3StageSignal): SingularityStageEvidence {
        val definition = SingularityFrameGraphRegistry.stageById(id)
            ?: error("Unknown Singularity Stage 3 id $id")
        val executionState = signal.state.toExecutionState()
        val fallbackId = when {
            signal.fallbackId.isNotBlank() -> signal.fallbackId
            signal.state == SingularityStage3SignalState.FALLBACK ->
                "${definition.stageName.uppercase(Locale.US)}_FALLBACK"
            signal.state == SingularityStage3SignalState.SOURCE_AVAILABLE_ONLY ->
                SingularityStage3EvidenceBundle.SOURCE_AVAILABLE_RUNTIME_NOT_PROVEN
            signal.state == SingularityStage3SignalState.DISABLED_BY_MODE ->
                "${definition.stageName.uppercase(Locale.US)}_DISABLED_BY_MODE"
            signal.state == SingularityStage3SignalState.UNAVAILABLE ->
                "${definition.stageName.uppercase(Locale.US)}_UNAVAILABLE"
            signal.state == SingularityStage3SignalState.FAILED ->
                "${definition.stageName.uppercase(Locale.US)}_FAILED"
            else -> ""
        }
        val fallbackReason = when {
            signal.fallbackReason.isNotBlank() -> signal.fallbackReason
            signal.state == SingularityStage3SignalState.FALLBACK ->
                "Stage executed through a visible compatibility fallback."
            signal.state == SingularityStage3SignalState.SOURCE_AVAILABLE_ONLY ->
                "Shader/model/source authority exists, but runtime dispatch evidence is not proven."
            signal.state == SingularityStage3SignalState.DISABLED_BY_MODE ->
                "Stage is not active for the selected mode or tier."
            signal.state == SingularityStage3SignalState.UNAVAILABLE ->
                "Stage evidence is unavailable on this device/session."
            signal.state == SingularityStage3SignalState.FAILED ->
                "Stage failed or caused a visible quality downgrade."
            else -> ""
        }
        return SingularityStageEvidence.fromDefinition(
            definition = definition,
            executionBackend = signal.backend.ifBlank { "SINGULARITY_STAGE3_EVIDENCE" },
            executionState = executionState,
            inputResources = signal.inputResources.filter { it.isNotBlank() },
            outputResources = signal.outputResources.filter { it.isNotBlank() },
            fallbackId = fallbackId,
            fallbackReason = fallbackReason,
            gpuDurationNs = signal.gpuDurationNs.coerceAtLeast(0L),
            nnapiDurationNs = signal.nnapiDurationNs.coerceAtLeast(0L),
            cpuControlDurationNs = signal.cpuControlDurationNs.coerceAtLeast(0L),
            confidence = signal.confidence.coerceIn(0.0, 1.0),
            evidenceReferences = signal.evidenceReferences.filter { it.isNotBlank() }.ifEmpty {
                listOf("stage3:${definition.smfRecordKey}")
            }
        )
    }

    private fun SingularityStage3SignalState.toExecutionState(): SingularityStageExecutionState =
        when (this) {
            SingularityStage3SignalState.PROVEN -> SingularityStageExecutionState.EXECUTED
            SingularityStage3SignalState.IDENTITY -> SingularityStageExecutionState.IDENTITY_PASS
            SingularityStage3SignalState.FALLBACK -> SingularityStageExecutionState.FALLBACK_EXECUTED
            SingularityStage3SignalState.SOURCE_AVAILABLE_ONLY -> SingularityStageExecutionState.DISABLED_BY_DEVICE
            SingularityStage3SignalState.DISABLED_BY_MODE -> SingularityStageExecutionState.DISABLED_BY_TIER
            SingularityStage3SignalState.UNAVAILABLE -> SingularityStageExecutionState.DISABLED_BY_DEVICE
            SingularityStage3SignalState.FAILED -> SingularityStageExecutionState.FAILED_WITH_DOWNGRADE
        }
}

private fun Int.toStageIdToken(): String = toString().padStart(3, '0')

private fun List<SingularityStageEvidence>.stageIdsCsv(): String =
    joinToString(separator = ",") { it.globalStageId.toStageIdToken() }

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
        if (gpuDurationNs > 0L) append("|gpuNs=").append(gpuDurationNs)
        if (nnapiDurationNs > 0L) append("|nnapiNs=").append(nnapiDurationNs)
        if (cpuControlDurationNs > 0L) append("|cpuNs=").append(cpuControlDurationNs)
        append("|refs=").append(evidenceReferences.joinToString(separator = ";").replace('|', '/'))
    }
