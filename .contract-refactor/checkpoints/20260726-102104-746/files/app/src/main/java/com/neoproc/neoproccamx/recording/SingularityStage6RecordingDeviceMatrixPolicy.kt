package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixBundle
import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixInput
import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixPolicy
import com.neoproc.neoproccamx.quality.SingularityStage6ValidationStatus

internal object SingularityStage6RecordingDeviceMatrixPolicy {
    fun fromQualityRequest(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): SingularityStage6DeviceMatrixBundle {
        val metadata = request.detailMetadata + runtimeMetadata
        val outputMode = request.singularityOutputMode()
        val modeId = request.singularityModeId(outputMode)
        return SingularityStage6DeviceMatrixPolicy.evaluate(
            SingularityStage6DeviceMatrixInput(
                recordingId = request.recordingId.ifBlank { request.recordedFileName },
                deviceFingerprint = metadata.clean("singularityStage6DeviceFingerprint").orEmpty(),
                buildId = metadata.clean("singularityStage6BuildId").orEmpty(),
                deviceClass = metadata.clean("singularityStage6DeviceClass").orEmpty(),
                calibrationId = metadata.clean("singularityStage6CalibrationId").orEmpty(),
                cameraId = metadata.clean("singularityStage6CameraId").orEmpty(),
                outputMode = outputMode,
                modeId = modeId,
                resolution = request.actualResolution.ifBlank { "${request.width}x${request.height}" },
                fps = request.fps,
                thresholdSetId = metadata.clean("singularityThresholdSetId")
                    ?: metadata.clean("singularityStage4ThresholdSetId")
                    ?: "",
                thresholdSetHash = metadata.clean("singularityThresholdSetHash")
                    ?: metadata.clean("singularityStage4ThresholdSetHash")
                    ?: "",
                testedCombinationId = metadata.clean("singularityStage6TestedCombinationId").orEmpty(),
                testedModes = metadata.csv("singularityStage6TestedModeIds"),
                unavailableModes = metadata.csv("singularityStage6UnavailableModeIds"),
                completedFunctionalChecks = metadata.csv("singularityStage6CompletedFunctionalChecks"),
                completedScenes = metadata.csv("singularityStage6CompletedScenes"),
                completedCharts = metadata.csv("singularityStage6CompletedCharts"),
                completedEnduranceScenarios = metadata.csv("singularityStage6CompletedEnduranceScenarios"),
                completedReviewSurfaces = metadata.csv("singularityStage6CompletedReviewSurfaces"),
                deviceLabStatus = metadata.status("singularityStage6DeviceLabStatus"),
                deviceCapabilitiesStatus = metadata.status("singularityStage6DeviceCapabilitiesStatus"),
                functionalStatus = metadata.status("singularityStage6FunctionalStatus"),
                modeMatrixStatus = metadata.status("singularityStage6ModeMatrixStatus"),
                sceneMatrixStatus = metadata.status("singularityStage6SceneMatrixStatus"),
                chartMatrixStatus = metadata.status("singularityStage6ChartMatrixStatus"),
                enduranceStatus = metadata.status("singularityStage6EnduranceStatus"),
                performanceStatus = metadata.status("singularityStage6PerformanceStatus"),
                largeScreenReviewStatus = metadata.status("singularityStage6LargeScreenReviewStatus"),
                uiStatus = metadata.status("singularityStage6UiStatus"),
                nativePathStatus = metadata.status("singularityStage6NativePathStatus"),
                artifactReferences = metadata.entries
                    .filter { (key, value) ->
                        key.startsWith("singularityStage6Artifact.") && value.isNotBlank()
                    }
                    .associate { (key, value) ->
                        key.removePrefix("singularityStage6Artifact.") to value.trim()
                    }
            )
        )
    }

    fun metadataFor(
        request: RecordingQualityEvidenceRequest,
        bundle: SingularityStage6DeviceMatrixBundle
    ): Map<String, String> =
        bundle.toMetadata() + mapOf(
            "singularityStage6ArtifactRelativePath" to
                SingularityStage6DeviceMatrixArtifactStore.relativeReportPath(
                    request.recordingId.ifBlank { request.recordedFileName }
                )
        )

    private fun RecordingQualityEvidenceRequest.singularityOutputMode(): String =
        when (mode) {
            EliteVideoPipelineMode.NPV10 -> "NPV10-SINGULARITY-MASTER"
            EliteVideoPipelineMode.RAW -> "NRAW-SINGULARITY"
            EliteVideoPipelineMode.LOG -> "NLOG-SINGULARITY"
            EliteVideoPipelineMode.HDR -> "NDR10X-SINGULARITY"
            EliteVideoPipelineMode.PRO -> "PRO-SDR-SINGULARITY"
            EliteVideoPipelineMode.NORMAL -> "NORMAL-SDR-SINGULARITY"
        }

    private fun RecordingQualityEvidenceRequest.singularityModeId(outputMode: String): String =
        when (mode) {
            EliteVideoPipelineMode.NPV10,
            EliteVideoPipelineMode.RAW,
            EliteVideoPipelineMode.LOG,
            EliteVideoPipelineMode.HDR -> outputMode
            EliteVideoPipelineMode.PRO,
            EliteVideoPipelineMode.NORMAL -> when {
                width >= 3800 && fps == 24 -> "4K24"
                width >= 3800 && fps == 25 -> "4K25"
                width >= 3800 && fps == 60 -> "4K60"
                height >= 1000 && fps == 25 -> "1080p25"
                height >= 1000 && fps == 60 -> "1080p60"
                else -> "${width}x${height}@${fps}"
            }
        }

    private fun Map<String, String>.status(key: String): SingularityStage6ValidationStatus =
        clean(key)
            ?.uppercase()
            ?.let { token -> SingularityStage6ValidationStatus.entries.firstOrNull { it.name == token } }
            ?: SingularityStage6ValidationStatus.NOT_TESTED

    private fun Map<String, String>.csv(key: String): Set<String> =
        clean(key)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toCollection(linkedSetOf())
            ?: emptySet()

    private fun Map<String, String>.clean(key: String): String? =
        get(key)?.trim()?.takeIf { it.isNotBlank() }
}
