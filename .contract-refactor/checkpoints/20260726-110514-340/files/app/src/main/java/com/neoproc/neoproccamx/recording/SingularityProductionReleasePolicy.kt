package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityOutputEvidence
import com.neoproc.neoproccamx.quality.SingularityElite100xAuthority
import com.neoproc.neoproccamx.quality.SingularityElite100xBundle
import com.neoproc.neoproccamx.quality.SingularityElite100xInput
import com.neoproc.neoproccamx.quality.SingularityElite100xPolicy
import com.neoproc.neoproccamx.quality.SingularityQualityGateId
import com.neoproc.neoproccamx.quality.SingularityQualityGateResult
import com.neoproc.neoproccamx.quality.SingularityQualityGateStatus
import com.neoproc.neoproccamx.quality.SingularityReleaseClass
import com.neoproc.neoproccamx.quality.SingularityReleaseDecision
import com.neoproc.neoproccamx.quality.SingularityReleaseGate
import com.neoproc.neoproccamx.quality.SingularityReleaseGateInput
import com.neoproc.neoproccamx.quality.SingularityStage2EvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage3EvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage5EngineBundle
import com.neoproc.neoproccamx.quality.SingularityStage5QcEvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage5QcEvidenceInput
import com.neoproc.neoproccamx.quality.SingularityStage5QcEvidenceMapper
import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixBundle

internal data class SingularityProductionReleaseBundle(
    val qcEvidence: SingularityStage5QcEvidenceBundle,
    val stage6DeviceMatrix: SingularityStage6DeviceMatrixBundle,
    val elite100x: SingularityElite100xBundle,
    val releaseInput: SingularityReleaseGateInput,
    val releaseDecision: SingularityReleaseDecision,
    val releaseMetadata: Map<String, String>
)

internal object SingularityProductionReleasePolicy {
    fun evaluate(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>,
        stage2Bundle: SingularityStage2EvidenceBundle,
        stage3Bundle: SingularityStage3EvidenceBundle,
        stage5Bundle: SingularityStage5EngineBundle,
        stage4Bundle: com.neoproc.neoproccamx.quality.SingularityStage4QualityGateBundle
    ): SingularityProductionReleaseBundle {
        val metadata = request.detailMetadata + runtimeMetadata
        val metadataFalseClaim = metadata.boolean("metadataFalseClaimDetected") ||
            stage4Bundle.results.any {
                it.gateId == SingularityQualityGateId.QG16 &&
                    it.status == SingularityQualityGateStatus.FAIL
            }
        val stage6Bundle = SingularityStage6RecordingDeviceMatrixPolicy.fromQualityRequest(
            request = request,
            runtimeMetadata = runtimeMetadata
        )
        val outputFinalized = request.outputFinalized(metadata)
        val outputPlayable = request.outputPlayable(metadata, stage4Bundle.results)
        val elite100x = SingularityElite100xPolicy.evaluate(
            SingularityElite100xInput(
                recordingId = request.recordingId.ifBlank { request.recordedFileName },
                outputMode = request.requestedModeName(),
                codec = request.codec,
                width = request.width,
                height = request.height,
                fps = request.fps,
                bitrateBps = request.bitrateBps,
                measuredOutputBitrateBps = request.measuredOutputBitrateBps,
                sourceBitDepth = request.sourceBitDepth,
                encodedBitDepth = request.encodedBitDepth,
                storageBitDepth = request.storageBitDepth,
                rawSensorOriginal = request.rawSensorOriginal,
                expectedFrameCount = request.expectedFrameCount,
                actualFrameCount = request.actualFrameCount,
                droppedFrames = request.droppedFrames,
                maxAllowedDroppedFrames = request.maxAllowedDroppedFrames,
                transportClassification = stage2Bundle.transportDecision.classification,
                thresholdSetId = stage4Bundle.thresholdSetId,
                thresholdSetHash = stage4Bundle.thresholdSetHash,
                qualityGateResults = stage4Bundle.results,
                stage5EngineRecords = stage5Bundle.records,
                artifactReferences = stage4Bundle.artifactReferences,
                metadata = metadata + stage4Bundle.toMetadata() + stage5Bundle.toMetadata()
            )
        )
        val qcEvidence = SingularityStage5QcEvidenceMapper.map(
            SingularityStage5QcEvidenceInput(
                recordingId = request.recordingId,
                stage2Mapped = stage2Bundle.allStages.size == 48,
                stage3Mapped = stage3Bundle.stages.size == 192,
                stage5EnginesMapped = stage5Bundle.records.size == 12,
                transportClassification = stage2Bundle.transportDecision.classification,
                qualityGateResults = stage4Bundle.results,
                metadataFalseClaimDetected = metadataFalseClaim,
                sidecarPlanReady = true,
                outputFinalized = outputFinalized,
                outputPlayable = outputPlayable
            )
        )
        val releaseInput = SingularityReleaseGateInput(
            requestedReleaseClass = stage6Bundle.releaseCap.atMost(elite100x.releaseCap),
            transportClassification = stage2Bundle.transportDecision.classification,
            stageEvidence = stage2Bundle.allStages + stage3Bundle.stages + qcEvidence.stages,
            qualityGateResults = stage4Bundle.results,
            outputEvidence = SingularityOutputEvidence(
                outputModeRequested = request.requestedModeName(),
                outputModeActual = request.actualModeName(),
                finalized = outputFinalized,
                playable = outputPlayable,
                recordedFileBytes = request.recordedFileBytes,
                smfSidecarFinalized = true,
                nestedSidecarsFinalized = true,
                shaderRegistryValidated = metadata.boolean("shaderRegistryValidated") ||
                    metadata.clean("shaderManifestValidationStatus").equals("PASS", ignoreCase = true),
                modelRegistryValidated = metadata.boolean("modelRegistryValidated") ||
                    metadata.clean("modelManifestValidationStatus").equals("PASS", ignoreCase = true),
                lutRegistryValidated = metadata.boolean("lutRegistryValidated") ||
                    metadata.clean("lutManifestValidationStatus").equals("PASS", ignoreCase = true),
                lookRegistryValidated = metadata.boolean("lookRegistryValidated") ||
                    metadata.clean("lookManifestValidationStatus").equals("PASS", ignoreCase = true),
                largeScreenReviewPassed = stage6Bundle.largeScreenReviewPassed,
                enduranceValidationPassed = stage6Bundle.enduranceValidationPassed,
                corruptOutputDetected = metadata.boolean("corruptOutputDetected"),
                criticalThermalCollapse = metadata.boolean("criticalThermalCollapse"),
                repeatedDroppedFramePattern = metadata.boolean("repeatedDroppedFramePattern"),
                metadataFalseClaimDetected = metadataFalseClaim,
                failureReason = metadata.clean("singularityOutputFailureReason") ?: ""
            ),
            compatibilityProfiles =
                compatibilityProfiles(stage2Bundle.transportDecision.classification.name) +
                    elite100x.compatibilityProfiles
        )
        val decision = SingularityReleaseGate.evaluate(releaseInput)
        val frameLineageResult = elite100x.results.single {
            it.authority == SingularityElite100xAuthority.FRAME_LINEAGE_PARITY
        }
        val releaseMetadata = buildMap {
            putAll(qcEvidence.toMetadata())
            putAll(elite100x.toMetadata())
            putAll(SingularityStage6RecordingDeviceMatrixPolicy.metadataFor(request, stage6Bundle))
            putAll(decision.toMetadata())
            put("singularityProductionReleaseVersion", VERSION)
            put("singularityProductionReleaseEvaluated", "true")
            put("singularityProductionStageLedgerSize", releaseInput.stageEvidence.size.toString())
            put("singularityProductionDeviceMatrixValidated", stage6Bundle.deviceLabValidated.toString())
            put("singularityProductionTestedDeviceModeCombination", stage6Bundle.testedDeviceModeCombination.toString())
            put("singularityProductionNativePathValidated", stage6Bundle.nativePathValidated.toString())
            put("singularityProductionElite100xQualified", elite100x.qualified.toString())
            put("singularityProductionElite100xReleaseCap", elite100x.releaseCap.label)
            put("singularityProductionFrameLineageParityStatus", frameLineageResult.status.name)
            put("singularityProductionFrameLineageParityReleaseCap", frameLineageResult.releaseCap.label)
            put("singularityProductionFrameLineageParityEvidence", frameLineageResult.evidenceReference)
            put("singularityProductionFrameLineageParityMeasured", frameLineageResult.measuredValue)
            put("singularityProductionFrameLineageParityThreshold", frameLineageResult.threshold)
            put("singularityProductionFrameLineageParityBlockingCode", frameLineageResult.blockingCode)
        }
        return SingularityProductionReleaseBundle(
            qcEvidence = qcEvidence,
            stage6DeviceMatrix = stage6Bundle,
            elite100x = elite100x,
            releaseInput = releaseInput,
            releaseDecision = decision,
            releaseMetadata = releaseMetadata
        )
    }

    private fun RecordingQualityEvidenceRequest.outputFinalized(metadata: Map<String, String>): Boolean =
        recordedFileBytes > 0L &&
            (
                mediaStoreValidated ||
                    mediaInfoValidated ||
                    mode == EliteVideoPipelineMode.NPV10 ||
                    metadata.stageState("singularityStage2", 238) == "EXECUTED"
                )

    private fun RecordingQualityEvidenceRequest.outputPlayable(
        metadata: Map<String, String>,
        qualityGateResults: List<SingularityQualityGateResult>
    ): Boolean =
        metadata.boolean("stage2PlayableValidated") ||
            metadata.boolean("playableOutputValidated") ||
            metadata.stageState("singularityStage2", 239) == "EXECUTED" ||
            (
                probe != null &&
                    (ffprobeValidated || mediaInfoValidated)
                ) ||
            (
                mode == EliteVideoPipelineMode.NPV10 &&
                    mediaInfoValidated &&
                    npv10MasterAvailable &&
                    recordedFileBytes > 0L
                ) ||
            qualityGateResults.singleOrNull { it.gateId == SingularityQualityGateId.QG12 }?.status ==
            SingularityQualityGateStatus.PASS

    private fun RecordingQualityEvidenceRequest.requestedModeName(): String =
        when (mode) {
            EliteVideoPipelineMode.NPV10 -> "NPV10-SINGULARITY-MASTER"
            EliteVideoPipelineMode.RAW -> "NRAW-SINGULARITY"
            EliteVideoPipelineMode.LOG -> "NLOG-SINGULARITY"
            EliteVideoPipelineMode.HDR -> "NDR10X-SINGULARITY"
            EliteVideoPipelineMode.PRO -> "PRO-SDR-SINGULARITY"
            EliteVideoPipelineMode.NORMAL -> "NORMAL-SDR-SINGULARITY"
        }

    private fun RecordingQualityEvidenceRequest.actualModeName(): String =
        if (selectedProfile.isBlank()) requestedModeName() else selectedProfile

    private fun compatibilityProfiles(transport: String): List<String> =
        if (transport.contains("CPU") || transport.contains("UNPROVEN")) listOf("CORE-HONEST-COMPATIBILITY") else emptyList()

    private fun Map<String, String>.stageState(prefix: String, stageId: Int): String? {
        val token = stageId.toString().padStart(3, '0')
        return entries
            .firstOrNull { (key, _) -> key.startsWith("$prefix.stage$token.") }
            ?.value
            ?.substringBefore('|')
    }

    private fun Map<String, String>.boolean(key: String): Boolean =
        clean(key)?.toBooleanStrictOrNull() ?: false

    private fun Map<String, String>.clean(key: String): String? =
        get(key)?.trim()?.takeIf { it.isNotBlank() }

    const val VERSION = "SINGULARITY_PRODUCTION_RELEASE_POLICY_V20"
}
