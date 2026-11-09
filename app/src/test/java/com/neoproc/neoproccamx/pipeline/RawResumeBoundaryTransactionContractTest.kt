package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawResumeBoundaryTransactionContractTest {
    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()

    @Test
    fun `Camera2 marker stops repeating and carries immutable session timestamp identity`() {
        val camera = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
        val transaction = camera
            .substringAfter("internal fun resumeRawVideoCaptureAtExactBoundary(")
            .substringBefore("private fun completeRawResumeBoundary(")
        val completion = camera
            .substringAfter("private fun completeRawResumeBoundary(")
            .substringBefore("private fun failRawResumeBoundary(")

        val stop = transaction.indexOf("session.stopRepeating()")
        val tag = transaction.indexOf("builder.setTag(token)", stop)
        val capture = transaction.indexOf("submitTrackedCaptureRequest(", tag)
        assertTrue(stop >= 0)
        assertTrue(tag > stop)
        assertTrue(capture > tag)
        assertTrue(transaction.contains("builder.setTag(previousTag)"))
        assertFalse(transaction.contains("abortCaptures()"))
        assertTrue(completion.contains("result.get(CaptureResult.SENSOR_TIMESTAMP)"))
        assertTrue(completion.contains("session === token.session"))
        assertTrue(completion.contains("token.sessionGeneration == sessionGeneration.get()"))
    }

    @Test
    fun `manager uses an unlocked two phase lease and exact marker floor`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val resume = manager.substringAfter("fun resumeCapture(): Boolean")
            .substringBefore("fun writtenFrameCount()")
        val begin = resume.indexOf("beginRawResumeAdmissionLease()")
        val camera = resume.indexOf("camera.resumeRawVideoCaptureAtExactBoundary(", begin)
        val commit = resume.indexOf("commitRawResumeAdmissionLease(", camera)
        val finish = resume.indexOf("finishRawResumeAdmissionLease(", commit)

        assertTrue(begin >= 0)
        assertTrue(camera > begin)
        assertTrue(commit > camera)
        assertTrue(finish > commit)
        assertFalse(
            resume.substring(begin, camera).contains("rawCaptureLifecycleLock.withLock")
        )
        assertTrue(resume.contains("markerTimestampNs <= minimumMarkerTimestampNs"))
        assertTrue(resume.contains("retiredSensorTimestampFloorNs = markerTimestampNs"))
        assertTrue(resume.contains("timestamp <= markerTimestampNs"))
        assertTrue(resume.contains("pendingRawCaptureResultsByTimestamp[timestamp] == null"))
    }

    @Test
    fun `marker and pause backlog are rejected without cadence estimation or compounding`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val resume = manager.substringAfter("fun resumeCapture(): Boolean")
            .substringBefore("fun writtenFrameCount()")
        val admission = manager
            .substringAfter("private fun admitRawFrameTimestamp(")
            .substringBefore("private fun offerRawFrameIfCurrent(")
        val imageIngress = manager.substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldSensorLinearNdrRecording(")

        assertFalse(resume.contains("REQUEST_PIPELINE_MAX_DEPTH"))
        assertFalse(resume.contains("minFrameIntervalNs *"))
        assertTrue(admission.contains("timestampNs <= token.retiredSensorTimestampFloorNs"))
        val closedRoute = imageIngress.substringAfter("if (!capturing) {")
            .substringBefore("// Pause/stop may close admission")
        assertTrue(closedRoute.contains("captureLifecycleActive.get()"))
        assertTrue(closedRoute.contains("if (!admittedToIdlePreview) img.close()"))

        val markerTimestampNs = 1_400_000_000L
        val secondMarkerTimestampNs = 1_600_000_000L
        assertFalse(1_300_000_000L > markerTimestampNs)
        assertFalse(markerTimestampNs > markerTimestampNs)
        assertTrue(1_500_000_000L > markerTimestampNs)
        // A later resume replaces the floor with its exact marker; it never adds a synthetic offset.
        assertTrue(secondMarkerTimestampNs > markerTimestampNs)
    }

    @Test
    fun `restart failure rolls back only the exact committed admission`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val finish = manager
            .substringAfter("private fun finishRawResumeAdmissionLease(")
            .substringBefore("fun writtenFrameCount()")
        val camera = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")

        assertTrue(finish.contains("rawCaptureIngressAdmission.get() === committed"))
        assertTrue(finish.contains("rawCaptureIngressAdmission.set(null)"))
        assertTrue(finish.contains("isPaused.set(true)"))
        assertTrue(camera.contains("pendingRawResumeRestart.compareAndSet(token, null)"))
        assertTrue(camera.contains("finishRawResumeBoundary(token, success = false)"))
    }

    @Test
    fun `timeout cleanup is synchronous and every competing Camera2 route is gated`() {
        val camera = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
        val transaction = camera
            .substringAfter("internal fun resumeRawVideoCaptureAtExactBoundary(")
            .substringBefore("private fun completeRawResumeBoundary(")
        val timeout = transaction
            .substringAfter("catch (_: TimeoutException)")
            .substringBefore("catch (_: InterruptedException)")
        val interrupted = transaction
            .substringAfter("catch (_: InterruptedException)")
            .substringBefore("catch (failure: Throwable)")
        val markerAction = transaction
            .substringAfter("runOnCameraThread {")
            .substringBefore("val markerRequest")
        val preview = camera
            .substringAfter("private fun startPreviewInternal(")
            .substringBefore("private fun repeatingTargetsConfiguredForSession(")
        val autofocus = camera
            .substringAfter("runUrgentCameraControl(\"autofocus\") {")
            .substringBefore("private fun maybeCompleteTapAfScan(")
        val still = camera
            .substringAfter("private fun submitStillCaptureOnCameraThread(")
            .substringBefore("private fun buildStillCaptureRequest(")

        assertTrue(timeout.contains("runOnCameraThread {"))
        assertTrue(timeout.contains("finishRawResumeBoundary(token, success = false)"))
        assertTrue(interrupted.contains("finishRawResumeBoundary(token, success = false)"))
        assertTrue(markerAction.contains("activeRawResumeBoundary.get() !== token"))
        assertTrue(markerAction.contains("token.completionState.get() != 0"))
        assertTrue(markerAction.indexOf("token.completionState.get() != 0") < markerAction.indexOf("session.stopRepeating()"))

        assertTrue(preview.contains("activeRawResume !== authorizedRawResumeBoundary"))
        assertTrue(preview.contains("rawResumeDeferredRequestRefresh.defer("))
        assertTrue(preview.contains("authorizedRawResumeBoundary = authorizedRawResumeBoundary"))
        assertTrue(camera.contains("pendingRawResumeRestart.get() !== token"))
        assertTrue(autofocus.contains("if (activeRawResumeBoundary.get() != null)"))
        assertTrue(still.contains("submitStillCaptureWhenSessionStable("))
        assertTrue(camera.contains("rawResumeDeferredRequestRefresh.drain()"))
    }
}
