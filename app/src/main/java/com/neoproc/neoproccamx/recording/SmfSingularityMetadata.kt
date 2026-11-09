package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityFrameGraphRegistry
import com.neoproc.neoproccamx.quality.SingularityQualityGateId
import com.neoproc.neoproccamx.quality.SingularityQualityGateResult
import com.neoproc.neoproccamx.quality.SingularityReleaseDecision
import com.neoproc.neoproccamx.quality.SingularityReleaseGate
import com.neoproc.neoproccamx.quality.SingularityReleaseGateInput
import com.neoproc.neoproccamx.quality.SingularityStageEvidence
import com.neoproc.neoproccamx.quality.SingularityThresholdAssetContract

internal data class SmfSingularityNestedMetadataReference(
    val schemaId: String,
    val version: String,
    val evidenceReference: String,
    val finalized: Boolean
) {
    init {
        require(schemaId.isNotBlank()) { "schemaId is required" }
        require(version.isNotBlank()) { "version is required" }
        require(evidenceReference.isNotBlank()) { "evidenceReference is required" }
    }
}

internal data class SmfSingularityMetadata(
    val formatVersion: String = FORMAT_VERSION,
    val app: String = "NeoProCamX",
    val applicationId: String = "com.neoproc.neoproccamx",
    val pipeline: String = "VUKX_SOVEREIGN_APEX_100000_0",
    val qualityTarget: String = "SINGULARITY_LARGE_FORMAT_INTERNAL",
    val sourceDirectiveAlias: String = "EXACT_IMAX_LEVEL_INTERNAL",
    val ambitionLaw: String = "10000000000000XXXX",
    val officialImaxCertificationClaimed: Boolean = false,
    val officialDolbyVisionCertificationClaimed: Boolean = false,
    val officialHdr10PlusCertificationClaimed: Boolean = false,
    val officialHdr10CertificationClaimed: Boolean = false,
    val frameGraphStages: Int = SingularityFrameGraphRegistry.STAGE_COUNT,
    val schemaMajor: Int = 1,
    val schemaMinor: Int = 0,
    val releasePolicyVersion: String = SingularityReleaseGate.RELEASE_POLICY_VERSION,
    val gatePolicyVersion: String = SingularityThresholdAssetContract.GATE_POLICY_VERSION,
    val thresholdSetId: String,
    val thresholdSetHash: String,
    val qualityClass: String,
    val outputModeRequested: String,
    val outputModeActual: String,
    val releaseLabel: String,
    val compatibilityProfiles: List<String>,
    val stageEvidence: List<SingularityStageEvidence>,
    val qualityGateResults: List<SingularityQualityGateResult>,
    val nestedMetadataReferences: List<SmfSingularityNestedMetadataReference>,
    val evidenceReferences: Map<String, String> = emptyMap(),
    val releaseReasons: List<String> = emptyList(),
    val blockingCodes: List<String> = emptyList()
) {
    init {
        require(formatVersion == FORMAT_VERSION) { "Unsupported SMF-Singularity formatVersion $formatVersion" }
        require(app == "NeoProCamX")
        require(applicationId == "com.neoproc.neoproccamx")
        require(!officialImaxCertificationClaimed) { "Official IMAX certification flag must be false" }
        require(!officialDolbyVisionCertificationClaimed) { "Official Dolby Vision certification flag must be false" }
        require(!officialHdr10PlusCertificationClaimed) { "Official HDR10+ certification flag must be false" }
        require(!officialHdr10CertificationClaimed) { "Official HDR10 certification flag must be false" }
        require(frameGraphStages == SingularityFrameGraphRegistry.STAGE_COUNT)
        require(schemaMajor == 1)
        require(schemaMinor >= 0)
        require(thresholdSetId.isNotBlank()) { "thresholdSetId is required" }
        require(thresholdSetHash.isNotBlank()) { "thresholdSetHash is required" }
        require(qualityClass.isNotBlank()) { "qualityClass is required" }
        require(outputModeRequested.isNotBlank()) { "outputModeRequested is required" }
        require(outputModeActual.isNotBlank()) { "outputModeActual is required" }
        require(releaseLabel.isNotBlank()) { "releaseLabel is required" }
        require(stageEvidence.size == SingularityFrameGraphRegistry.STAGE_COUNT) {
            "SMF-Singularity must carry all 256 stage records"
        }
        require(stageEvidence.map { it.globalStageId }.toSet().size == SingularityFrameGraphRegistry.STAGE_COUNT) {
            "SMF-Singularity stage records must be unique"
        }
        require(qualityGateResults.map { it.gateId }.toSet() == SingularityQualityGateId.entries.toSet()) {
            "SMF-Singularity must carry QG01..QG16 results"
        }
        require(qualityGateResults.size == SingularityQualityGateId.entries.size) {
            "SMF-Singularity quality gates must be unique"
        }
    }

    fun toJsonText(): String = buildString {
        append("{\n")
        appendField("formatVersion", formatVersion, trailing = true)
        appendField("app", app, trailing = true)
        appendField("applicationId", applicationId, trailing = true)
        appendField("pipeline", pipeline, trailing = true)
        appendField("qualityTarget", qualityTarget, trailing = true)
        appendField("sourceDirectiveAlias", sourceDirectiveAlias, trailing = true)
        appendField("ambitionLaw", ambitionLaw, trailing = true)
        appendField("officialImaxCertificationClaimed", officialImaxCertificationClaimed, trailing = true)
        appendField("officialDolbyVisionCertificationClaimed", officialDolbyVisionCertificationClaimed, trailing = true)
        appendField("officialHdr10PlusCertificationClaimed", officialHdr10PlusCertificationClaimed, trailing = true)
        appendField("officialHdr10CertificationClaimed", officialHdr10CertificationClaimed, trailing = true)
        appendField("frameGraphStages", frameGraphStages, trailing = true)
        appendField("schemaMajor", schemaMajor, trailing = true)
        appendField("schemaMinor", schemaMinor, trailing = true)
        appendField("releasePolicyVersion", releasePolicyVersion, trailing = true)
        appendField("gatePolicyVersion", gatePolicyVersion, trailing = true)
        appendField("thresholdSetId", thresholdSetId, trailing = true)
        appendField("thresholdSetHash", thresholdSetHash, trailing = true)
        appendField("qualityClass", qualityClass, trailing = true)
        appendField("outputModeRequested", outputModeRequested, trailing = true)
        appendField("outputModeActual", outputModeActual, trailing = true)
        appendField("releaseLabel", releaseLabel, trailing = true)
        appendArrayField("compatibilityProfiles", compatibilityProfiles, trailing = true)
        appendArrayField("releaseReasons", releaseReasons, trailing = true)
        appendArrayField("blockingCodes", blockingCodes, trailing = true)
        append("\"nestedMetadataSchemas\": [")
        append(nestedMetadataReferences.joinToString(separator = ",") { it.toJsonObject() })
        append("],\n")
        append("\"qualityGates\": [")
        append(qualityGateResults.joinToString(separator = ",") { it.toJsonObject() })
        append("],\n")
        append("\"stageEvidence\": [")
        append(stageEvidence.joinToString(separator = ",") { it.toJsonObject() })
        append("],\n")
        append("\"evidenceReferences\": ")
        append(evidenceReferences.toJsonObject())
        append("\n}")
    }

    companion object {
        const val FORMAT_VERSION = "SMF-SINGULARITY-1"

        fun fromReleaseDecision(
            input: SingularityReleaseGateInput,
            decision: SingularityReleaseDecision,
            thresholdSetId: String,
            thresholdSetHash: String,
            nestedMetadataReferences: List<SmfSingularityNestedMetadataReference>,
            evidenceReferences: Map<String, String> = emptyMap()
        ): SmfSingularityMetadata =
            SmfSingularityMetadata(
                thresholdSetId = thresholdSetId,
                thresholdSetHash = thresholdSetHash,
                qualityClass = decision.qualityClass,
                outputModeRequested = input.outputEvidence.outputModeRequested,
                outputModeActual = input.outputEvidence.outputModeActual,
                releaseLabel = decision.releaseLabel,
                compatibilityProfiles = decision.compatibilityProfiles,
                stageEvidence = input.stageEvidence,
                qualityGateResults = input.qualityGateResults,
                nestedMetadataReferences = nestedMetadataReferences,
                evidenceReferences = evidenceReferences,
                releaseReasons = decision.reasons,
                blockingCodes = decision.blockingCodes
            )
    }
}

private fun StringBuilder.appendField(name: String, value: String, trailing: Boolean) {
    append("\"").append(name).append("\": \"").append(value.jsonEscaped()).append("\"")
    append(if (trailing) ",\n" else "\n")
}

private fun StringBuilder.appendField(name: String, value: Boolean, trailing: Boolean) {
    append("\"").append(name).append("\": ").append(value)
    append(if (trailing) ",\n" else "\n")
}

private fun StringBuilder.appendField(name: String, value: Int, trailing: Boolean) {
    append("\"").append(name).append("\": ").append(value)
    append(if (trailing) ",\n" else "\n")
}

private fun StringBuilder.appendArrayField(name: String, values: List<String>, trailing: Boolean) {
    append("\"").append(name).append("\": [")
    append(values.joinToString(separator = ",") { "\"${it.jsonEscaped()}\"" })
    append("]")
    append(if (trailing) ",\n" else "\n")
}

private fun SmfSingularityNestedMetadataReference.toJsonObject(): String =
    mapOf(
        "schemaId" to schemaId,
        "version" to version,
        "evidenceReference" to evidenceReference,
        "finalized" to finalized.toString()
    ).toJsonObject()

private fun SingularityQualityGateResult.toJsonObject(): String =
    mapOf(
        "gateId" to gateId.name,
        "status" to status.name,
        "mandatory" to mandatory.toString(),
        "measuredValue" to (measuredValue?.toString() ?: ""),
        "threshold" to threshold,
        "unit" to unit,
        "evidenceReference" to evidenceReference,
        "failureCode" to failureCode,
        "responsibleStageId" to (responsibleStageId?.toString() ?: ""),
        "fallbackDecision" to fallbackDecision,
        "recordingImpact" to recordingImpact
    ).toJsonObject()

private fun SingularityStageEvidence.toJsonObject(): String =
    buildString {
        append("{")
        append("\"globalStageId\": ").append(globalStageId).append(",")
        append("\"blockIndex\": ").append(blockIndex).append(",")
        append("\"localStageIndex\": ").append(localStageIndex).append(",")
        append("\"stageName\": \"").append(stageName.jsonEscaped()).append("\",")
        append("\"functionalAuthority\": \"").append(functionalAuthority.jsonEscaped()).append("\",")
        append("\"minimumReleaseClass\": \"").append(minimumReleaseClass.label).append("\",")
        append("\"executionBackend\": \"").append(executionBackend.jsonEscaped()).append("\",")
        append("\"executionState\": \"").append(executionState.name).append("\",")
        append("\"fallbackId\": \"").append(fallbackId.jsonEscaped()).append("\",")
        append("\"fallbackReason\": \"").append(fallbackReason.jsonEscaped()).append("\",")
        append("\"gpuDurationNs\": ").append(gpuDurationNs).append(",")
        append("\"nnapiDurationNs\": ").append(nnapiDurationNs).append(",")
        append("\"cpuControlDurationNs\": ").append(cpuControlDurationNs).append(",")
        append("\"confidence\": ").append(confidence).append(",")
        append("\"qcGateIds\": [")
        append(qcGateIds.joinToString(separator = ",") { "\"${it.name}\"" })
        append("],")
        append("\"smfRecordKey\": \"").append(smfRecordKey.jsonEscaped()).append("\",")
        append("\"inputResources\": ")
        append(inputResources.toJsonArray())
        append(",\"outputResources\": ")
        append(outputResources.toJsonArray())
        append(",\"requiredCapabilities\": ")
        append(requiredCapabilities.toList().toJsonArray())
        append(",\"evidenceReferences\": ")
        append(evidenceReferences.toJsonArray())
        append("}")
    }

private fun Map<String, String>.toJsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        "\"${key.jsonEscaped()}\": \"${value.jsonEscaped()}\""
    }

private fun List<String>.toJsonArray(): String =
    joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${it.jsonEscaped()}\"" }

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
