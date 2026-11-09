package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityReleaseClass
import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixPolicy
import com.neoproc.neoproccamx.quality.SingularityStage6ValidationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage6RecordingDeviceMatrixPolicyTest {

    @Test
    fun adapterBuildsCompleteStage6MetadataFromRuntimeProof() {
        val request = request()
        val bundle = SingularityStage6RecordingDeviceMatrixPolicy.fromQualityRequest(
            request = request,
            runtimeMetadata = completeRuntimeMetadata()
        )
        val metadata = SingularityStage6RecordingDeviceMatrixPolicy.metadataFor(request, bundle)

        assertEquals(SingularityStage6ValidationStatus.PASS, bundle.overallStatus)
        assertEquals(SingularityReleaseClass.SINGULARITY_100000, bundle.releaseCap)
        assertTrue(bundle.deviceLabValidated)
        assertTrue(metadata.getValue("singularityStage6ReportJson").contains("\"deviceLabValidated\":true"))
        assertTrue(metadata.getValue("singularityStage6ArtifactRelativePath").endsWith("stage6-device-matrix-report.json"))
    }

    @Test
    fun adapterDoesNotTrustLegacyLargeScreenAndEnduranceBooleans() {
        val bundle = SingularityStage6RecordingDeviceMatrixPolicy.fromQualityRequest(
            request = request(),
            runtimeMetadata = mapOf(
                "largeScreenReviewPassed" to "true",
                "enduranceValidationPassed" to "true"
            )
        )

        assertEquals(SingularityStage6ValidationStatus.NOT_TESTED, bundle.overallStatus)
        assertEquals(SingularityReleaseClass.CORE_HONEST, bundle.releaseCap)
        assertFalse(bundle.largeScreenReviewPassed)
        assertFalse(bundle.enduranceValidationPassed)
    }

    private fun request(): RecordingQualityEvidenceRequest =
        RecordingQualityEvidenceRequest(
            mode = EliteVideoPipelineMode.HDR,
            selectedProfile = "NDR10+",
            activeTransport = "BT.2100_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            codec = "hevc",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            width = 3840,
            height = 2160,
            fps = 60,
            bitrateBps = 120_000_000,
            selectedResolution = "3840x2160",
            actualResolution = "3840x2160",
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "OFF",
            rawSensorOriginal = false,
            captureTruthSignature = "capture",
            previewQualitySignature = "quality",
            recordingQualitySignature = "quality",
            expectedFrameCount = 120,
            actualFrameCount = 120,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = null,
            ffprobeValidated = false,
            mediaInfoValidated = false,
            deviceLogValidated = false,
            recordingId = "stage6",
            recordedFileName = "stage6.mp4",
            recordedFileBytes = 4096,
            mediaStoreValidated = true
        )

    private fun completeRuntimeMetadata(): Map<String, String> {
        val outputMode = "NDR10X-SINGULARITY"
        val combinationId = SingularityStage6DeviceMatrixPolicy.combinationId(
            deviceFingerprint = "google/panther/prod",
            cameraId = "0",
            outputMode = outputMode,
            resolution = "3840x2160",
            fps = 60
        )
        return mapOf(
            "singularityStage6DeviceFingerprint" to "google/panther/prod",
            "singularityStage6BuildId" to "UP1A.240000.001",
            "singularityStage6DeviceClass" to "HIGH_END_VALIDATED",
            "singularityStage6CalibrationId" to "lab-calibration-2026-06-08",
            "singularityStage6CameraId" to "0",
            "singularityStage6TestedCombinationId" to combinationId,
            "singularityStage6TestedModeIds" to SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.joinToString(),
            "singularityStage6CompletedFunctionalChecks" to
                SingularityStage6DeviceMatrixPolicy.REQUIRED_FUNCTIONAL_CHECKS.joinToString(),
            "singularityStage6CompletedScenes" to SingularityStage6DeviceMatrixPolicy.REQUIRED_SCENES.joinToString(),
            "singularityStage6CompletedCharts" to SingularityStage6DeviceMatrixPolicy.REQUIRED_CHARTS.joinToString(),
            "singularityStage6CompletedEnduranceScenarios" to
                SingularityStage6DeviceMatrixPolicy.REQUIRED_ENDURANCE.joinToString(),
            "singularityStage6CompletedReviewSurfaces" to
                SingularityStage6DeviceMatrixPolicy.REQUIRED_REVIEW_SURFACES.joinToString(),
            "singularityStage6DeviceLabStatus" to "PASS",
            "singularityStage6DeviceCapabilitiesStatus" to "PASS",
            "singularityStage6FunctionalStatus" to "PASS",
            "singularityStage6ModeMatrixStatus" to "PASS",
            "singularityStage6SceneMatrixStatus" to "PASS",
            "singularityStage6ChartMatrixStatus" to "PASS",
            "singularityStage6EnduranceStatus" to "PASS",
            "singularityStage6PerformanceStatus" to "PASS",
            "singularityStage6LargeScreenReviewStatus" to "PASS",
            "singularityStage6UiStatus" to "PASS",
            "singularityStage6NativePathStatus" to "PASS",
            "singularityThresholdSetId" to "panther-4k60-ndr",
            "singularityThresholdSetHash" to "sha256-stage6"
        )
    }
}
