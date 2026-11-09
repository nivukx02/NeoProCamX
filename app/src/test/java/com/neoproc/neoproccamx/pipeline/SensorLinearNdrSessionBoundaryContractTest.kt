package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrSessionBoundaryContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `Camera2 metadata is rejected after the authoritative generation advances`() {
        val controller = source(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        )
        val callback = controller
            .substringAfter("override fun onCaptureCompleted(")
            .substringBefore("override fun onCaptureFailed(")
        val publication = controller
            .substringAfter("private fun processResult(r: CaptureResult, captureSessionGeneration: Long)")
            .substringBefore("private fun processColorStabilityEvidence")
        val deferredRunner = controller
            .substringAfter("private val captureResultDiagnosticsRunnable = Runnable {")
            .substringBefore("// Edge-trigger for the active-override engage/disengage log.")
        val deferredPublication = controller
            .substringAfter(
                "private fun processDeferredCaptureResultDiagnostics(work: DeferredCaptureResultDiagnostics)"
            )
            .substringBefore("private fun processResult(")

        assertTrue(callback.contains("binding.generation != sessionGeneration.get()"))
        assertTrue(publication.contains("captureSessionGeneration != sessionGeneration.get()"))
        assertTrue(
            publication.indexOf("captureSessionGeneration != sessionGeneration.get()") <
                publication.indexOf("enqueueCaptureResultDiagnostics(")
        )
        assertTrue(
            deferredRunner.indexOf("work.captureSessionGeneration == sessionGeneration.get()") <
                deferredRunner.indexOf("processDeferredCaptureResultDiagnostics(work)")
        )
        assertTrue(deferredPublication.contains("sessionGeneration = work.captureSessionGeneration"))
        assertTrue(
            deferredPublication.indexOf("sessionGeneration = work.captureSessionGeneration") <
                deferredPublication.indexOf("latestMeasuredColorSnapshot = measuredColorSnapshot")
        )
        assertTrue(!deferredPublication.contains("sessionGeneration = sessionGeneration.get()"))
    }

    @Test
    fun `idle RAW submission and completion retain and validate immutable Camera2 lineage`() {
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt"
        )
        val processor = coordinator
            .substringAfter("rawVideoManager.rawPreviewProcessor =")
            .substringBefore("if (sensorDebayer && sensorLinearPathActive)")
        val completion = coordinator.substringAfter(
            "private class CompletedRawFramePresentationSink("
        )

        assertTrue(processor.contains("isCameraSessionGenerationCurrent(submissionIdentity.sessionGeneration)"))
        assertTrue(processor.contains("cameraSessionGeneration = submissionIdentity.sessionGeneration"))
        assertTrue(
            completion.contains(
                "completed.cameraSessionGeneration != submissionIdentity.sessionGeneration"
            )
        )
        assertTrue(completion.contains("isSessionGenerationCurrent(submissionIdentity.sessionGeneration)"))
        assertTrue(completion.contains("sessionGeneration = completed.cameraSessionGeneration"))
    }

    @Test
    fun `stale native completion is acknowledged once without encoder publication`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val resolution = runtime
            .substringAfter("private fun publishOrRetireSensorLinearNdrRecordingFrameLocked(")
            .substringBefore("private fun publishCompletedSensorLinearNdrRecordingFrame(")
        val staleCheck = resolution.indexOf("NdrRawCompletionIdentityPolicy.resolve(")
        val dispatchIdentity = resolution.indexOf(
            "dispatchIdentity = frame.renderProfileIdentity",
            staleCheck
        )
        val currentIdentity = resolution.indexOf(
            "currentIdentity = currentNdrRenderBundleSnapshot()?.identity",
            dispatchIdentity
        )
        val cameraSession = resolution.indexOf(
            "isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)",
            currentIdentity
        )
        val staleDecision = resolution.indexOf(
            "NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE",
            cameraSession
        )
        val staleAcknowledge = resolution.indexOf(
            "acknowledgeRawDemosaicCompletion(frame)",
            staleDecision
        )

        assertTrue(staleCheck >= 0)
        assertTrue(dispatchIdentity > staleCheck)
        assertTrue(currentIdentity > dispatchIdentity)
        assertTrue(cameraSession > currentIdentity)
        assertTrue(staleDecision > cameraSession)
        assertTrue(staleAcknowledge > staleDecision)
        assertTrue(resolution.contains("encoderAccepted = false"))
        assertTrue(resolution.contains("stale NDR completion retired without encoder publication"))
    }

    @Test
    fun `final NDR encoder and lineage boundaries validate the frame generation`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val encoder = runtime
            .substringAfter("private fun submitLatestSensorLinearNdrFrame(")
            .substringBefore("fun submitRawSensorBytes(")
        val viewModel = source(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        val lineageListener = viewModel
            .substringAfter("vulkanRuntime.setFrameLineageSubmissionListener lineage@")
            .substringBefore("camera.onCameraPipelineWarning")

        val firstValidation = encoder.indexOf(
            "isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)"
        )
        val nativeSubmit = encoder.indexOf("submitSensorLinearAcesCgWithInputReadFence(")
        val receiptCommit = encoder.indexOf(
            "sensorLinearNdrEncoderInputReads.commitAccepted(",
            nativeSubmit
        )
        val lineagePublish = encoder.indexOf("emitFrameLineageSubmission(")
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt"
        )
        val recordingPreviewSink = coordinator
            .substringAfter("private class SensorLinearNdrRecordingPreviewSink(")
            .substringBefore("private class CompletedRawFramePresentationSink(")

        assertTrue(firstValidation >= 0 && firstValidation < nativeSubmit)
        assertTrue(receiptCommit > nativeSubmit && receiptCommit < lineagePublish)
        assertTrue(encoder.contains("cameraSessionGeneration = cameraSessionGeneration"))
        assertTrue(lineageListener.contains("submission.cameraSessionGeneration"))
        assertTrue(lineageListener.contains("camera.sessionGeneration.get()"))
        assertTrue(
            recordingPreviewSink.contains(
                "isSessionGenerationCurrent(completed.cameraSessionGeneration)"
            )
        )
        assertTrue(
            recordingPreviewSink.indexOf(
                "isSessionGenerationCurrent(completed.cameraSessionGeneration)"
            ) < recordingPreviewSink.indexOf("presenter.present(")
        )
        val presenter = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/SensorLinearNdrPreviewPresenter.kt"
        )
        assertTrue(presenter.contains("cameraSessionGeneration = sessionGeneration"))
        val previewSubmission = runtime
            .substringAfter("fun submitLatestSensorLinearNdrPreviewFrame(")
            .substringBefore("fun releaseSensorLinearNdrPreviewSurface()")
        assertTrue(
            previewSubmission.contains(
                "isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)"
            )
        )
    }
}
