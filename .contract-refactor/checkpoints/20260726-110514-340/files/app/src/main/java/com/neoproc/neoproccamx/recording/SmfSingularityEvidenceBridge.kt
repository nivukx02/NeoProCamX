package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.OmegaCapabilityGate
import com.neoproc.neoproccamx.quality.OmegaQualityTruthReport
import com.neoproc.neoproccamx.quality.SingularityStage2EvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage3EvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage4QualityGateAutomation
import com.neoproc.neoproccamx.quality.SingularityStage5HighEndEnginePolicy
import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixPolicy

internal data class SmfSingularityEvidenceBundle(
    val nestedMetadataReferences: List<SmfSingularityNestedMetadataReference>,
    val evidenceReferences: Map<String, String>
)

internal object SmfSingularityEvidenceBridge {
    const val NEO_PROFILE_TRUTH_METADATA_VERSION = "NeoProfileTruthMetadata-1"
    const val OMEGA_QUALITY_TRUTH_REPORT_VERSION = OmegaCapabilityGate.GENERATION
    const val RECORDING_QUALITY_CERTIFICATE_VERSION = RecordingQualityEvidencePolicy.GENERATION
    const val NPV10_CONTAINER_VERSION = "NPV10-${Npv10ContainerPolicy.VERSION}"

    fun fromExistingEvidence(
        recordingQualityCertificate: RecordingQualityEvidenceCertificate? = null,
        neoProfileTruthMetadata: NeoProfileTruthMetadata? = null,
        omegaQualityTruthReport: OmegaQualityTruthReport? = null,
        npv10PostRecordAudit: Npv10PostRecordAudit? = null,
        nivukxTmbEvidenceReference: String = "",
        nivukxVisionEvidenceReference: String = "",
        vulkanEvidenceReference: String = "",
        thermalEvidenceReference: String = "",
        encoderEvidenceReference: String = ""
    ): SmfSingularityEvidenceBundle {
        val nested = buildList {
            recordingQualityCertificate?.let {
                add(
                    SmfSingularityNestedMetadataReference(
                        schemaId = "RecordingQualityEvidenceCertificate",
                        version = RECORDING_QUALITY_CERTIFICATE_VERSION,
                        evidenceReference = it.request.recordingId.ifBlank { "recording-quality-certificate" },
                        finalized = it.status == RecordingQualityEvidenceStatus.PASS
                    )
                )
                if (it.metadata["singularityStage2MappedStageCount"] == "48") {
                    add(
                        SmfSingularityNestedMetadataReference(
                            schemaId = "SingularityStage2Evidence",
                            version = SingularityStage2EvidenceBundle.VERSION,
                            evidenceReference = it.metadata["singularityStage2TransportClassification"]
                                ?.takeIf { value -> value.isNotBlank() }
                                ?: it.request.recordingId.ifBlank { "singularity-stage2-evidence" },
                            finalized = it.metadata["singularityStage2CaptureStageCount"] == "32" &&
                                it.metadata["singularityStage2OutputStageCount"] == "16"
                        )
                    )
                }
                if (it.metadata["singularityStage3MappedStageCount"] == "192") {
                    add(
                        SmfSingularityNestedMetadataReference(
                            schemaId = "SingularityStage3Evidence",
                            version = SingularityStage3EvidenceBundle.VERSION,
                            evidenceReference = it.metadata["singularityStage3ExecutionSummary"]
                                ?.takeIf { value -> value.isNotBlank() }
                                ?: it.request.recordingId.ifBlank { "singularity-stage3-evidence" },
                            finalized = it.metadata["singularityStage3StageIds"] ==
                                (32..223).joinToString(separator = ",") { id ->
                                    id.toString().padStart(3, '0')
                                }
                        )
                    )
                }
                if (it.metadata["singularityStage4GateCount"] == "16") {
                    add(
                        SmfSingularityNestedMetadataReference(
                            schemaId = "SingularityStage4QualityGates",
                            version = SingularityStage4QualityGateAutomation.VERSION,
                            evidenceReference = it.metadata["singularityStage4ArtifactRelativePath"]
                                ?.takeIf { value -> value.isNotBlank() }
                                ?: it.metadata["singularityStage4GateSummary"]
                                    ?.takeIf { value -> value.isNotBlank() }
                                ?: it.request.recordingId.ifBlank { "singularity-stage4-qg-results" },
                            finalized = it.metadata["singularityStage4GateIds"] ==
                                "QG01,QG02,QG03,QG04,QG05,QG06,QG07,QG08,QG09,QG10,QG11,QG12,QG13,QG14,QG15,QG16"
                        )
                    )
                }
                if (it.metadata["singularityStage5EngineCount"] == "12") {
                    add(
                        SmfSingularityNestedMetadataReference(
                            schemaId = "SingularityStage5HighEndEngines",
                            version = SingularityStage5HighEndEnginePolicy.VERSION,
                            evidenceReference = it.metadata["singularityStage5ArtifactRelativePath"]
                                ?.takeIf { value -> value.isNotBlank() }
                                ?: it.metadata["singularityStage5StatusSummary"]
                                    ?.takeIf { value -> value.isNotBlank() }
                                ?: it.request.recordingId.ifBlank { "singularity-stage5-engine-report" },
                            finalized = it.metadata["singularityStage5EngineIds"]
                                ?.split(',')
                                ?.filter { value -> value.isNotBlank() }
                                ?.size == 12
                        )
                    )
                }
                if (it.metadata["singularityStage6AuthorityCount"] == "11") {
                    add(
                        SmfSingularityNestedMetadataReference(
                            schemaId = "SingularityStage6ProductionDeviceMatrix",
                            version = SingularityStage6DeviceMatrixPolicy.VERSION,
                            evidenceReference = it.metadata["singularityStage6ArtifactRelativePath"]
                                ?.takeIf { value -> value.isNotBlank() }
                                ?: it.metadata["singularityStage6CurrentCombinationId"]
                                    ?.takeIf { value -> value.isNotBlank() }
                                ?: it.request.recordingId.ifBlank { "singularity-stage6-device-matrix" },
                            finalized = it.metadata["singularityStage6OverallStatus"] == "PASS" &&
                                it.metadata["singularityStage6DeviceLabValidated"] == "true"
                        )
                    )
                }
            }
            neoProfileTruthMetadata?.let {
                add(
                    SmfSingularityNestedMetadataReference(
                        schemaId = "NeoProfileTruthMetadata",
                        version = NEO_PROFILE_TRUTH_METADATA_VERSION,
                        evidenceReference = it.activeTransport,
                        finalized = it.proprietaryProfilePreserved &&
                            !it.vendorHdrCertification &&
                            !it.vendorCertifiedHdr10Plus &&
                            !it.vendorCertifiedDolbyVision &&
                            !it.dolbyRpuWritten
                    )
                )
            }
            omegaQualityTruthReport?.let {
                add(
                    SmfSingularityNestedMetadataReference(
                        schemaId = "OmegaQualityTruthReport",
                        version = OMEGA_QUALITY_TRUTH_REPORT_VERSION,
                        evidenceReference = it.overallStatus.name,
                        finalized = it.truthful
                    )
                )
            }
            npv10PostRecordAudit?.let {
                add(
                    SmfSingularityNestedMetadataReference(
                        schemaId = "NPV10 container version",
                        version = NPV10_CONTAINER_VERSION,
                        evidenceReference = it.sessionHash.ifBlank { "npv10-post-record-audit" },
                        finalized = it.auditStatus == Npv10AuditStatus.PASS
                    )
                )
            }
            nivukxTmbEvidenceReference.takeIf { it.isNotBlank() }?.let {
                add(
                    SmfSingularityNestedMetadataReference(
                        schemaId = "nivukx-tmb-draft-12",
                        version = "draft-12",
                        evidenceReference = it,
                        finalized = true
                    )
                )
            }
            nivukxVisionEvidenceReference.takeIf { it.isNotBlank() }?.let {
                add(
                    SmfSingularityNestedMetadataReference(
                        schemaId = "nivukx-vision-a1-a15-v1",
                        version = "a1-a15-v1",
                        evidenceReference = it,
                        finalized = true
                    )
                )
            }
        }
        val references = buildMap {
            recordingQualityCertificate?.let {
                put("recordingQualityEvidenceStatus", it.status.name)
                put("recordingQualityFailureCodes", it.failures.joinToString(separator = ",") { failure -> failure.code.name })
                it.metadata
                    .filterKeys { key ->
                        key.startsWith("singularityStage2") ||
                            key.startsWith("singularityStage3") ||
                            key.startsWith("singularityStage4") ||
                            key.startsWith("singularityStage5") ||
                            key.startsWith("singularityStage6")
                    }
                    .forEach { (key, value) -> put(key, value) }
            }
            neoProfileTruthMetadata?.let {
                put("neoProfileSelectedProfile", it.selectedProfile)
                put("neoProfileActiveTransport", it.activeTransport)
                put("neoProfileNoVendorCertification", (
                    !it.vendorHdrCertification &&
                        !it.vendorCertifiedHdr10Plus &&
                        !it.vendorCertifiedDolbyVision &&
                        !it.dolbyRpuWritten
                    ).toString()
                )
            }
            omegaQualityTruthReport?.let {
                put("omegaTruthful", it.truthful.toString())
                put("omegaOverallStatus", it.overallStatus.name)
            }
            npv10PostRecordAudit?.let {
                put("npv10AuditStatus", it.auditStatus.name)
                put("npv10FrameCount", it.frameCount.toString())
                put("npv10SessionHash", it.sessionHash)
            }
            vulkanEvidenceReference.takeIf { it.isNotBlank() }?.let { put("vulkanTransportEvidence", it) }
            thermalEvidenceReference.takeIf { it.isNotBlank() }?.let { put("thermalMemoryDropEvidence", it) }
            encoderEvidenceReference.takeIf { it.isNotBlank() }?.let { put("encoderOutputEvidence", it) }
        }
        return SmfSingularityEvidenceBundle(
            nestedMetadataReferences = nested,
            evidenceReferences = references
        )
    }
}
