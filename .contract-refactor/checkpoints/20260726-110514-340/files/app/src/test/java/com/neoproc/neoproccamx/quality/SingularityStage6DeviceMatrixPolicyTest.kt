package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage6DeviceMatrixPolicyTest {

    @Test
    fun canonicalMatrixMatchesStage6ProductionRequirements() {
        assertEquals(9, SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.size)
        assertTrue(SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.contains("NDR10X-SINGULARITY"))
        assertTrue(SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.contains("NLOG-SINGULARITY"))
        assertTrue(SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.contains("NRAW-SINGULARITY"))
        assertTrue(SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.contains("NPV10-SINGULARITY-MASTER"))
        assertEquals(18, SingularityStage6DeviceMatrixPolicy.REQUIRED_SCENES.size)
        assertEquals(8, SingularityStage6DeviceMatrixPolicy.REQUIRED_CHARTS.size)
        assertEquals(9, SingularityStage6DeviceMatrixPolicy.REQUIRED_ENDURANCE.size)
        assertEquals(6, SingularityStage6DeviceMatrixPolicy.REQUIRED_REVIEW_SURFACES.size)
        assertEquals(16, SingularityStage6DeviceMatrixPolicy.REQUIRED_FUNCTIONAL_CHECKS.size)
    }

    @Test
    fun missingDeviceLabEvidenceCapsAtCoreHonestAndRecordsEveryAuthority() {
        val bundle = SingularityStage6DeviceMatrixPolicy.evaluate(
            completeInput().copy(
                deviceFingerprint = "",
                buildId = "",
                calibrationId = "",
                testedCombinationId = "",
                testedModes = emptySet(),
                completedScenes = emptySet(),
                completedCharts = emptySet(),
                completedEnduranceScenarios = emptySet(),
                completedReviewSurfaces = emptySet(),
                completedFunctionalChecks = emptySet(),
                deviceLabStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                deviceCapabilitiesStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                functionalStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                modeMatrixStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                sceneMatrixStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                chartMatrixStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                enduranceStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                performanceStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                largeScreenReviewStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                uiStatus = SingularityStage6ValidationStatus.NOT_TESTED,
                nativePathStatus = SingularityStage6ValidationStatus.NOT_TESTED
            )
        )

        assertEquals(SingularityStage6ValidationStatus.NOT_TESTED, bundle.overallStatus)
        assertEquals(SingularityReleaseClass.CORE_HONEST, bundle.releaseCap)
        assertEquals(11, bundle.authorityResults.size)
        assertFalse(bundle.deviceLabValidated)
        assertFalse(bundle.testedDeviceModeCombination)
        assertTrue(bundle.missingRequirements.any { it.contains("SCENE_MATRIX") })
    }

    @Test
    fun completeMatrixAllowsSingularity100Eligibility() {
        val bundle = SingularityStage6DeviceMatrixPolicy.evaluate(completeInput())

        assertEquals(SingularityStage6ValidationStatus.PASS, bundle.overallStatus)
        assertEquals(SingularityReleaseClass.SINGULARITY_100000, bundle.releaseCap)
        assertTrue(bundle.deviceLabValidated)
        assertTrue(bundle.testedDeviceModeCombination)
        assertTrue(bundle.largeScreenReviewPassed)
        assertTrue(bundle.enduranceValidationPassed)
        assertTrue(bundle.nativePathValidated)
        assertTrue(bundle.missingRequirements.isEmpty())
        assertTrue(bundle.toMetadata().getValue("singularityStage6ReportJson").contains("\"overallStatus\":\"PASS\""))
    }

    @Test
    fun falsePassWithMissingSceneCoverageFailsClosed() {
        val bundle = SingularityStage6DeviceMatrixPolicy.evaluate(
            completeInput().copy(
                completedScenes = SingularityStage6DeviceMatrixPolicy.REQUIRED_SCENES - "night street",
                sceneMatrixStatus = SingularityStage6ValidationStatus.PASS
            )
        )

        assertEquals(SingularityStage6ValidationStatus.FAIL, bundle.overallStatus)
        assertEquals(SingularityReleaseClass.SINGULARITY_25, bundle.releaseCap)
        assertTrue(bundle.missingRequirements.any { it.contains("night street") })
        assertTrue(
            bundle.authorityResults
                .single { it.authority == SingularityStage6Authority.SCENE_MATRIX }
                .failureCode
                .isNotBlank()
        )
    }

    private fun completeInput(): SingularityStage6DeviceMatrixInput {
        val outputMode = "NDR10X-SINGULARITY"
        val combinationId = SingularityStage6DeviceMatrixPolicy.combinationId(
            deviceFingerprint = "google/panther/prod",
            cameraId = "0",
            outputMode = outputMode,
            resolution = "3840x2160",
            fps = 60
        )
        return SingularityStage6DeviceMatrixInput(
            recordingId = "stage6",
            deviceFingerprint = "google/panther/prod",
            buildId = "UP1A.240000.001",
            deviceClass = "HIGH_END_VALIDATED",
            calibrationId = "lab-calibration-2026-06-08",
            cameraId = "0",
            outputMode = outputMode,
            modeId = outputMode,
            resolution = "3840x2160",
            fps = 60,
            thresholdSetId = "panther-4k60-ndr",
            thresholdSetHash = "sha256-stage6",
            testedCombinationId = combinationId,
            testedModes = SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES,
            unavailableModes = emptySet(),
            completedFunctionalChecks = SingularityStage6DeviceMatrixPolicy.REQUIRED_FUNCTIONAL_CHECKS,
            completedScenes = SingularityStage6DeviceMatrixPolicy.REQUIRED_SCENES,
            completedCharts = SingularityStage6DeviceMatrixPolicy.REQUIRED_CHARTS,
            completedEnduranceScenarios = SingularityStage6DeviceMatrixPolicy.REQUIRED_ENDURANCE,
            completedReviewSurfaces = SingularityStage6DeviceMatrixPolicy.REQUIRED_REVIEW_SURFACES,
            deviceLabStatus = SingularityStage6ValidationStatus.PASS,
            deviceCapabilitiesStatus = SingularityStage6ValidationStatus.PASS,
            functionalStatus = SingularityStage6ValidationStatus.PASS,
            modeMatrixStatus = SingularityStage6ValidationStatus.PASS,
            sceneMatrixStatus = SingularityStage6ValidationStatus.PASS,
            chartMatrixStatus = SingularityStage6ValidationStatus.PASS,
            enduranceStatus = SingularityStage6ValidationStatus.PASS,
            performanceStatus = SingularityStage6ValidationStatus.PASS,
            largeScreenReviewStatus = SingularityStage6ValidationStatus.PASS,
            uiStatus = SingularityStage6ValidationStatus.PASS,
            nativePathStatus = SingularityStage6ValidationStatus.PASS,
            artifactReferences = mapOf(
                "deviceCapabilities" to "stage6/device-capabilities.json",
                "sceneMatrix" to "stage6/scene-matrix.json",
                "endurance" to "stage6/endurance.json",
                "performance" to "stage6/performance.json",
                "largeScreenReview" to "stage6/large-screen-review.json",
                "nativePath" to "stage6/native-path.json"
            )
        )
    }
}
