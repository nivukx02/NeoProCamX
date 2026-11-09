package com.neoproc.neoproccamx.quality

internal data class SingularityOutputEvidence(
    val outputModeRequested: String,
    val outputModeActual: String,
    val finalized: Boolean,
    val playable: Boolean,
    val recordedFileBytes: Long,
    val smfSidecarFinalized: Boolean,
    val nestedSidecarsFinalized: Boolean,
    val shaderRegistryValidated: Boolean,
    val modelRegistryValidated: Boolean,
    val lutRegistryValidated: Boolean,
    val lookRegistryValidated: Boolean,
    val largeScreenReviewPassed: Boolean = false,
    val enduranceValidationPassed: Boolean = false,
    val corruptOutputDetected: Boolean = false,
    val criticalThermalCollapse: Boolean = false,
    val repeatedDroppedFramePattern: Boolean = false,
    val metadataFalseClaimDetected: Boolean = false,
    val failureReason: String = ""
)

internal data class SingularityReleaseGateInput(
    val requestedReleaseClass: SingularityReleaseClass,
    val transportClassification: SingularityTransportClassification,
    val stageEvidence: List<SingularityStageEvidence>,
    val qualityGateResults: List<SingularityQualityGateResult>,
    val outputEvidence: SingularityOutputEvidence,
    val officialImaxCertificationClaimed: Boolean = false,
    val officialDolbyVisionCertificationClaimed: Boolean = false,
    val officialHdr10PlusCertificationClaimed: Boolean = false,
    val officialHdr10CertificationClaimed: Boolean = false,
    val compatibilityProfiles: List<String> = emptyList()
)

internal data class SingularityReleaseDecision(
    val releaseClass: SingularityReleaseClass,
    val releaseLabel: String,
    val qualityClass: String,
    val transportClassification: SingularityTransportClassification,
    val reasons: List<String>,
    val blockingCodes: List<String>,
    val stageLedgerComplete: Boolean,
    val mandatoryGateSummary: String,
    val compatibilityProfiles: List<String>
) {
    val releasable: Boolean
        get() = releaseClass != SingularityReleaseClass.NO_RELEASE

    fun toMetadata(): Map<String, String> = mapOf(
        "singularityReleasePolicyVersion" to SingularityReleaseGate.RELEASE_POLICY_VERSION,
        "singularityReleaseLabel" to releaseLabel,
        "singularityQualityClass" to qualityClass,
        "singularityTransportClassification" to transportClassification.name,
        "singularityStageLedgerComplete" to stageLedgerComplete.toString(),
        "singularityMandatoryGateSummary" to mandatoryGateSummary,
        "singularityBlockingCodes" to blockingCodes.joinToString(separator = ","),
        "singularityReleaseReasons" to reasons.joinToString(separator = "|"),
        "singularityCompatibilityProfiles" to compatibilityProfiles.joinToString(separator = ",")
    )
}

internal object SingularityReleaseGate {
    const val RELEASE_POLICY_VERSION = "SINGULARITY_RELEASE_POLICY_1"

    fun evaluate(input: SingularityReleaseGateInput): SingularityReleaseDecision {
        val reasons = mutableListOf<String>()
        val blockingCodes = mutableListOf<String>()

        fun noRelease(code: String, reason: String): SingularityReleaseDecision {
            blockingCodes += code
            reasons += reason
            return decision(
                releaseClass = SingularityReleaseClass.NO_RELEASE,
                input = input,
                reasons = reasons,
                blockingCodes = blockingCodes,
                stageLedgerComplete = stageLedgerComplete(input.stageEvidence),
                mandatoryGateSummary = mandatoryGateSummary(input.qualityGateResults)
            )
        }

        if (input.officialImaxCertificationClaimed) {
            return noRelease("OFFICIAL_IMAX_CERTIFICATION_FALSE_CLAIM", "External IMAX certification must remain false.")
        }
        if (input.officialDolbyVisionCertificationClaimed) {
            return noRelease("OFFICIAL_DOLBY_VISION_CERTIFICATION_FALSE_CLAIM", "Dolby Vision certification must remain false.")
        }
        if (input.officialHdr10PlusCertificationClaimed) {
            return noRelease("OFFICIAL_HDR10_PLUS_CERTIFICATION_FALSE_CLAIM", "HDR10+ certification must remain false.")
        }
        if (input.officialHdr10CertificationClaimed) {
            return noRelease("OFFICIAL_HDR10_CERTIFICATION_FALSE_CLAIM", "HDR10 certification must remain false.")
        }
        if (input.outputEvidence.metadataFalseClaimDetected) {
            return noRelease("METADATA_FALSE_CLAIM_DETECTED", "Metadata honesty failure blocks release.")
        }
        if (input.outputEvidence.corruptOutputDetected || !input.outputEvidence.finalized) {
            return noRelease("OUTPUT_NOT_FINALIZED", input.outputEvidence.failureReason.ifBlank { "Output finalization failed." })
        }
        if (!input.outputEvidence.playable || input.outputEvidence.recordedFileBytes <= 0L) {
            return noRelease("OUTPUT_NOT_PLAYABLE", "Output must be non-empty and playable.")
        }
        if (!input.outputEvidence.smfSidecarFinalized || !input.outputEvidence.nestedSidecarsFinalized) {
            return noRelease("SIDECAR_FINALIZATION_MISSING", "SMF-Singularity and nested sidecars must finalize.")
        }

        var maxClass = input.requestedReleaseClass

        if (!input.transportClassification.isSingularityTransport()) {
            maxClass = maxClass.atMost(SingularityReleaseClass.CORE_HONEST)
            reasons += "Transport ${input.transportClassification} is compatibility or unproven."
        }

        val stageValidation = validateStageEvidence(input.stageEvidence)
        if (!stageValidation.complete) {
            maxClass = maxClass.atMost(SingularityReleaseClass.CORE_HONEST)
            reasons += "Stage ledger incomplete: ${stageValidation.summary}"
        }
        if (stageValidation.duplicateStageIds.isNotEmpty()) {
            return noRelease("DUPLICATE_STAGE_EVIDENCE", "Stage ledger contains duplicate stage ids.")
        }
        if (input.stageEvidence.any { it.executionState == SingularityStageExecutionState.FAILED_RECORDING_STOPPED }) {
            return noRelease("STAGE_FAILED_RECORDING_STOPPED", "A stage reported failed recording stopped.")
        }
        if (input.stageEvidence.any { it.executionState == SingularityStageExecutionState.FAILED_WITH_DOWNGRADE }) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_25)
            reasons += "At least one stage failed with a visible downgrade."
        }
        val nonCreativeReductions = input.stageEvidence.filter { evidence ->
            evidence.isReduction && evidence.globalStageId !in CLEAN_CREATIVE_IDENTITY_STAGE_IDS
        }
        if (nonCreativeReductions.isNotEmpty()) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Validated reductions are present in ${nonCreativeReductions.size} non-creative stages."
        }

        val gates = qualityGateMap(input.qualityGateResults)
        val duplicateGateIds = input.qualityGateResults
            .groupingBy { it.gateId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateGateIds.isNotEmpty()) {
            return noRelease(
                "DUPLICATE_QUALITY_GATE_EVIDENCE",
                "Quality gate ledger contains duplicate ids: ${duplicateGateIds.joinToString { it.name }}."
            )
        }
        val missingGateIds = SingularityQualityGateId.entries.filterNot { gates.containsKey(it) }
        if (missingGateIds.isNotEmpty()) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_50)
            reasons += "Missing quality gates: ${missingGateIds.joinToString { it.name }}."
        }
        val nonMandatoryCanonicalGates = input.qualityGateResults.filterNot { it.mandatory }
        if (nonMandatoryCanonicalGates.isNotEmpty()) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Canonical gates marked non-mandatory: ${nonMandatoryCanonicalGates.joinToString { it.gateId.name }}."
        }
        val failedMandatory = input.qualityGateResults.filter {
            it.mandatory && it.status == SingularityQualityGateStatus.FAIL
        }
        if (failedMandatory.any { it.gateId == SingularityQualityGateId.QG16 }) {
            return noRelease("QG16_METADATA_HONESTY_FAILED", "Metadata honesty gate failed.")
        }
        if (failedMandatory.isNotEmpty()) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_25)
            reasons += "Mandatory quality gates failed: ${failedMandatory.joinToString { it.gateId.name }}."
        }
        val notPassedMandatory = input.qualityGateResults.filter {
            it.mandatory && it.status != SingularityQualityGateStatus.PASS
        }
        if (notPassedMandatory.isNotEmpty()) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Mandatory gates not all PASS: ${notPassedMandatory.joinToString { it.gateId.name + '=' + it.status.name }}."
        }

        if (!input.outputEvidence.shaderRegistryValidated) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Shader manifest/registry validation is not proven."
        }
        if (!input.outputEvidence.modelRegistryValidated) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Model manifest/registry validation is not proven."
        }
        if (!input.outputEvidence.lutRegistryValidated || !input.outputEvidence.lookRegistryValidated) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "LUT/look manifest validation is not proven."
        }
        if (input.outputEvidence.criticalThermalCollapse) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_50)
            reasons += "Critical thermal collapse reduced the release class."
        }
        if (input.outputEvidence.repeatedDroppedFramePattern) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_50)
            reasons += "Repeated dropped-frame pattern reduced the release class."
        }
        if (!input.outputEvidence.largeScreenReviewPassed || !input.outputEvidence.enduranceValidationPassed) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Large-screen review or endurance validation is not proven."
        }
        if (input.outputEvidence.outputModeRequested != input.outputEvidence.outputModeActual) {
            maxClass = maxClass.atMost(SingularityReleaseClass.SINGULARITY_75)
            reasons += "Requested and actual output modes differ and the fallback remains visible."
        }

        if (maxClass == SingularityReleaseClass.SINGULARITY_100000) {
            reasons += "All mandatory Singularity-100000 transport, stage, gate, output, registry, sidecar, endurance, and review evidence passed."
        }
        if (maxClass == SingularityReleaseClass.CORE_HONEST && reasons.isEmpty()) {
            reasons += "Stable compatibility output is truthful but does not prove Singularity transport."
        }

        return decision(
            releaseClass = maxClass,
            input = input,
            reasons = reasons.ifEmpty { listOf("Release class evaluated without blocking reductions.") },
            blockingCodes = blockingCodes,
            stageLedgerComplete = stageValidation.complete,
            mandatoryGateSummary = mandatoryGateSummary(input.qualityGateResults)
        )
    }

    private fun SingularityTransportClassification.isSingularityTransport(): Boolean =
        this != SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY &&
            this != SingularityTransportClassification.UNPROVEN

    private fun qualityGateMap(
        results: List<SingularityQualityGateResult>
    ): Map<SingularityQualityGateId, SingularityQualityGateResult> =
        results.associateBy { it.gateId }

    private fun mandatoryGateSummary(results: List<SingularityQualityGateResult>): String {
        val counts = results.groupingBy { it.status }.eachCount()
        return SingularityQualityGateStatus.entries.joinToString(separator = " ") { status ->
            "${status.name}=${counts[status] ?: 0}"
        }
    }

    private fun stageLedgerComplete(stageEvidence: List<SingularityStageEvidence>): Boolean =
        validateStageEvidence(stageEvidence).complete

    private fun validateStageEvidence(stageEvidence: List<SingularityStageEvidence>): StageValidation {
        val ids = stageEvidence.map { it.globalStageId }
        val idSet = ids.toSet()
        val duplicateIds = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        val missingIds = (0 until SingularityFrameGraphRegistry.STAGE_COUNT).filterNot { idSet.contains(it) }
        val complete = duplicateIds.isEmpty() && missingIds.isEmpty() && stageEvidence.size == SingularityFrameGraphRegistry.STAGE_COUNT
        return StageValidation(
            complete = complete,
            missingStageIds = missingIds,
            duplicateStageIds = duplicateIds,
            summary = "present=${stageEvidence.size} missing=${missingIds.size} duplicate=${duplicateIds.size}"
        )
    }

    private fun decision(
        releaseClass: SingularityReleaseClass,
        input: SingularityReleaseGateInput,
        reasons: List<String>,
        blockingCodes: List<String>,
        stageLedgerComplete: Boolean,
        mandatoryGateSummary: String
    ): SingularityReleaseDecision =
        SingularityReleaseDecision(
            releaseClass = releaseClass,
            releaseLabel = releaseClass.label,
            qualityClass = releaseClass.label,
            transportClassification = input.transportClassification,
            reasons = reasons,
            blockingCodes = blockingCodes,
            stageLedgerComplete = stageLedgerComplete,
            mandatoryGateSummary = mandatoryGateSummary,
            compatibilityProfiles = input.compatibilityProfiles
        )

    private data class StageValidation(
        val complete: Boolean,
        val missingStageIds: List<Int>,
        val duplicateStageIds: List<Int>,
        val summary: String
    )

    private val CLEAN_CREATIVE_IDENTITY_STAGE_IDS = setOf(217, 218, 219, 220, 221, 222)
}
