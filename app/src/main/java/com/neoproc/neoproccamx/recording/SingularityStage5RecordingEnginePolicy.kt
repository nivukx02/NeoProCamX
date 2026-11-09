package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityStage5EngineBundle
import com.neoproc.neoproccamx.quality.SingularityStage5EngineInput
import com.neoproc.neoproccamx.quality.SingularityStage5HighEndEnginePolicy

internal object SingularityStage5RecordingEnginePolicy {
    fun fromQualityRequest(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): SingularityStage5EngineBundle {
        val metadata = request.detailMetadata + runtimeMetadata
        return SingularityStage5HighEndEnginePolicy.evaluate(
            SingularityStage5EngineInput(
                recordingId = request.recordingId.ifBlank { request.recordedFileName },
                outputMode = request.selectedProfile,
                sourceBitDepth = request.sourceBitDepth,
                encodedBitDepth = request.encodedBitDepth,
                width = request.width,
                height = request.height,
                fps = request.fps,
                metadata = metadata + request.runtimeEvidenceMetadata()
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
        bundle: SingularityStage5EngineBundle
    ): Map<String, String> =
        bundle.toMetadata() + mapOf(
            "singularityStage5ArtifactRelativePath" to
                SingularityStage5EngineArtifactStore.relativeReportPath(
                    request.recordingId.ifBlank { request.recordedFileName }
                )
        )

    private fun RecordingQualityEvidenceRequest.runtimeEvidenceMetadata(): Map<String, String> =
        buildMap {
            put("gpuProcessingValidated", gpuProcessingValidated.toString())
            put("processedRecordingFramesValidated", processedRecordingFramesValidated.toString())
            put("zeroCopyValidated", zeroCopyValidated.toString())
            put("performanceTelemetryValidated", performanceTelemetryValidated.toString())
            put("gpuProcessingPath", gpuProcessingPath)
            put("enhancementTemporalBlend", enhancementTemporalBlend.toString())
            put("enhancementSpatialDenoise", enhancementSpatialDenoise.toString())
            put("enhancementChromaDenoise", enhancementChromaDenoise.toString())
            put("enhancementSharpening", enhancementSharpening.toString())
            put("enhancementSubjectDetailGain", enhancementSubjectDetailGain.toString())
            put("enhancementDetailProfile", enhancementDetailProfile)
            put("enhancementUniformSignature", enhancementUniformSignature)
            put("previewEnhancementUniformSignature", previewEnhancementUniformSignature)
            put("recordingEnhancementUniformSignature", recordingEnhancementUniformSignature)
            put("selectedProfile", selectedProfile)
            put("normalizedHdrVersion", normalizedHdrVersion)
            put("normalizedLogVersion", normalizedLogVersion)
            put("npv10PayloadFormat", npv10PayloadFormat)
            putAll(noiseMetrics.toMetadata())
        }
}
