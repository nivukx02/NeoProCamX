package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStillCaptureIsolationContractTest {
    private val viewModelSource =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val stillCaptureSource =
        File("src/main/java/com/neoproc/neoproccamx/capture/StillCapture.kt").readText()

    @Test
    fun recordingUsesResourceSafetyGateInsteadOfWaitingForPhotoPublication() {
        val lifecycleWiring = viewModelSource.contractSection(
            start = "private val recordingStopRestartLifecycle",
            end = "private val sensorLinearNdrRecordingFailure"
        )
        val startRecordingDeclaration = viewModelSource.lineSequence()
            .first { it.trimStart().startsWith("fun startRecording()") }
            .trim()
        val claimAdmissionBody = viewModelSource.contractSection(
            start = "private fun claimRecordingStartAdmission(): Boolean {",
            end = "private fun beginClaimedRecordingStart()"
        )
        val recordingAdmissionPolicy = viewModelSource.contractSection(
            start = "fun isRecordingAdmissionReadyForStart(): Boolean =",
            end = "fun startRecording()"
        )

        assertTrue(
            startRecordingDeclaration ==
                "fun startRecording() = recordingStopRestartLifecycle.requestStart()"
        )
        assertTrue(lifecycleWiring.contains("claimStartAdmission = ::claimRecordingStartAdmission"))
        assertTrue(claimAdmissionBody.contains("if (!isRecordingAdmissionReadyForStart())"))
        assertFalse(claimAdmissionBody.contains("stillCapture.isCaptureInFlight()"))
        assertTrue(recordingAdmissionPolicy.contains("!stillCapture.blocksRecordingAdmission()"))
        assertTrue(
            recordingAdmissionPolicy.contains("!camera.isStillCaptureSessionRestorationPending()")
        )
        assertTrue(
            stillCaptureSource.contains(
                "fun blocksRecordingAdmission(): Boolean = sessionCoordinator.blocksRecordingAdmission()"
            )
        )
    }

    @Test
    fun stillShutterContinuesToRejectOverlappingFullResolutionCapture() {
        val captureBody = viewModelSource.substringAfter("fun captureHighQualityPhoto(): Boolean {")
            .substringBefore("fun isHighQualityPhotoCaptureInFlight()")

        assertTrue(captureBody.contains("stillCapture.isCaptureInFlight()"))
    }

    @Test
    fun secondaryProcessingTimeoutCannotReconfigureRecordingAfterBuffersAreReleased() {
        val controllerFailure = stillCaptureSource.substringAfter(
            "private fun handleControllerCaptureFailure"
        ).substringBefore("private fun failOwnedCapture")
        val ownedFailure = stillCaptureSource.substringAfter(
            "private fun failOwnedCapture"
        ).substringBefore("private fun recoverCaptureSessionAfterFailure")

        listOf(controllerFailure, ownedFailure).forEach { failurePath ->
            assertTrue(failurePath.contains("sessionCoordinator.blocksRecordingAdmission()"))
            assertTrue(failurePath.contains("!sessionCoordinator.hasAcquiredCameraBuffers()"))
            assertTrue(failurePath.contains("recoverCaptureSessionAfterFailure"))
        }
    }

    private fun String.contractSection(start: String, end: String): String {
        assertTrue("Missing source contract marker: $start", contains(start))
        val afterStart = substringAfter(start)
        assertTrue("Missing source contract delimiter after $start: $end", afterStart.contains(end))
        return afterStart.substringBefore(end)
    }
}
