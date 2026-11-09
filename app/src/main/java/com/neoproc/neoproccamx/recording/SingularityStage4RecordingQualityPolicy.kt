package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityStage4QualityGateAutomation
import com.neoproc.neoproccamx.quality.SingularityStage4QualityGateBundle
import com.neoproc.neoproccamx.quality.SingularityStage4QualityGateInput
import com.neoproc.neoproccamx.quality.SingularityStageExecutionState

internal object SingularityStage4RecordingQualityPolicy {
    fun fromQualityRequest(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): SingularityStage4QualityGateBundle {
        val metadata = request.detailMetadata + runtimeMetadata
        return SingularityStage4QualityGateAutomation.evaluate(
            SingularityStage4QualityGateInput(
                recordingId = request.recordingId.ifBlank { request.recordedFileName },
                outputMode = request.selectedProfile,
                width = request.width,
                height = request.height,
                fps = request.fps,
                expectedFrameCount = request.expectedFrameCount,
                actualFrameCount = request.actualFrameCount,
                droppedFrames = request.droppedFrames,
                maxAllowedDroppedFrames = request.maxAllowedDroppedFrames,
                recordedFileBytes = request.recordedFileBytes,
                finalized = request.finalizedByEvidence(metadata),
                playable = request.playableByEvidence(metadata),
                previewQualitySignature = request.previewQualitySignature,
                recordingQualitySignature = request.recordingQualitySignature,
                thresholdSetId = metadata.clean("singularityThresholdSetId")
                    ?: metadata.clean("singularityStage4ThresholdSetId")
                    ?: SingularityStage4QualityGateAutomation.DEFAULT_THRESHOLD_SET_ID,
                thresholdSetHash = metadata.clean("singularityThresholdSetHash")
                    ?: metadata.clean("singularityStage4ThresholdSetHash")
                    ?: SingularityStage4QualityGateAutomation.DEFAULT_THRESHOLD_SET_HASH,
                metadata = metadata
            )
        )
    }

    fun metadataFor(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): Map<String, String> =
        metadataFor(
            request = request,
            bundle = fromQualityRequest(request, runtimeMetadata)
        )

    fun metadataFor(
        request: RecordingQualityEvidenceRequest,
        bundle: SingularityStage4QualityGateBundle
    ): Map<String, String> =
        bundle.toMetadata() + mapOf(
            "singularityStage4ArtifactRelativePath" to
                SingularityQualityGateArtifactStore.relativeReportPath(
                    request.recordingId.ifBlank { request.recordedFileName }
                )
        )

    private fun RecordingQualityEvidenceRequest.finalizedByEvidence(
        metadata: Map<String, String>
    ): Boolean =
        recordedFileBytes > 0L &&
            (
                mediaStoreValidated ||
                    mediaInfoValidated ||
                    mode == EliteVideoPipelineMode.NPV10 ||
                    metadata.stageState("singularityStage2", 238) == SingularityStageExecutionState.EXECUTED.name
                )

    private fun RecordingQualityEvidenceRequest.playableByEvidence(
        metadata: Map<String, String>
    ): Boolean =
        metadata.boolean("stage2PlayableValidated") ||
            metadata.boolean("playableOutputValidated") ||
            metadata.stageState("singularityStage2", 239) == SingularityStageExecutionState.EXECUTED.name ||
            (
                probe != null &&
                    (ffprobeValidated || mediaInfoValidated)
                ) ||
            (
                mode == EliteVideoPipelineMode.NPV10 &&
                    mediaInfoValidated &&
                    npv10MasterAvailable &&
                    recordedFileBytes > 0L
                )

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
}
