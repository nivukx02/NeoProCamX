package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2FrameLineageStopOrderContractTest {

    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `sensor linear NDR lineage freezes only after its accepted tail drains`() {
        val source = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val stopBody = source
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private suspend fun stopCustomRawRecordingSession()")
        val orderedBoundary = stopBody
            .substringAfter("if (sensorLinearNdrOwnershipWasActive) {")
            .substringBefore("val rawCaptureStopResult = stopRawCaptureIfNeeded()")
        val drain = orderedBoundary.indexOf(
            "sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)"
        )
        val freeze = orderedBoundary.indexOf("freezeCamera2FrameLineageSession()")
        val finalMetadataRefresh =
            orderedBoundary.lastIndexOf("refreshRecordingSceneMetadataForFinalization()")

        assertTrue("protected ingress tail must be drained", drain >= 0)
        assertTrue("lineage must freeze after tail publication", freeze > drain)
        assertTrue(
            "tail failure must still close the certificate",
            orderedBoundary.substring(drain, freeze).contains("finally")
        )
        assertEquals(
            "both sensor-linear and non-sensor stop paths must freeze exactly once",
            2,
            Regex("freezeCamera2FrameLineageSession\\(\\)")
                .findAll(orderedBoundary)
                .count()
        )
        assertTrue(
            "final publication metadata must be rebuilt after either lineage freeze",
            finalMetadataRefresh > orderedBoundary.lastIndexOf("freezeCamera2FrameLineageSession()")
        )
    }

    @Test
    fun `terminal lineage metadata is refreshed before recording teardown`() {
        val source = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val stopBody = source
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private suspend fun stopCustomRawRecordingSession()")
        val refresh = stopBody.indexOf("refreshRecordingSceneMetadataForFinalization()")
        val rawStop = stopBody.indexOf("val rawCaptureStopResult = stopRawCaptureIfNeeded()")
        val encoderStop = stopBody.indexOf("val path = gpuRecording.stop()")
        val helper = source
            .substringAfter("private fun refreshRecordingSceneMetadataForFinalization()")
            .substringBefore("private fun recordPreviewAdmission")

        assertTrue("terminal metadata refresh must exist", refresh >= 0)
        assertTrue("terminal metadata must precede RAW/camera teardown", refresh < rawStop)
        assertTrue("terminal metadata must precede encoder finalization", refresh < encoderStop)
        assertTrue(helper.contains("gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata("))
        assertTrue(helper.contains("selection = lastRecordingSelection"))
    }

    @Test
    fun `bounded capture window remains open for endpoint catch up`() {
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/Camera2FrameLineageCoordinator.kt"
        )
        val captureIngress = coordinator
            .substringAfter("private fun recordCaptureIngress(accepted: CaptureIngress)")
            .substringBefore("private fun maybeFreezeIfReadyLocked()")
        val readyGate = coordinator
            .substringAfter("private fun maybeFreezeIfReadyLocked()")
            .substringBefore("private fun metadataLocked()")

        assertTrue(captureIngress.contains("captureAdmissionClosed.set(true)"))
        assertTrue(captureIngress.contains("captureWindowLastTimestampNs"))
        assertTrue(!captureIngress.contains("recordingActive = false"))
        assertTrue(readyGate.contains("camera2FrameLineageCertified"))
        assertTrue(!readyGate.contains("trackedCaptureResults >= MAX_TRACKED_CAPTURE_RESULTS"))
    }

    @Test
    fun `camera and image timestamps are normalized before lineage accounting`() {
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/Camera2FrameLineageCoordinator.kt"
        )
        val submission = coordinator
            .substringAfter("fun recordSubmission(")
            .substringBefore("fun recordEncoderSubmission(")

        assertTrue(coordinator.contains("timestampTimebase.observeReference("))
        assertTrue(submission.contains("submission.rawSensorTimestampNs"))
        assertTrue(submission.contains("val canonicalTimestampNs = normalizeTimestamp("))
        assertTrue(coordinator.contains("toMetadata(\"camera2FrameLineageTimestamp\")"))
    }
}
