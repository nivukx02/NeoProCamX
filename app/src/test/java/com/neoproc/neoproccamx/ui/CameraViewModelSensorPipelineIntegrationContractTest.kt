package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraViewModelSensorPipelineIntegrationContractTest {
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val lineageCoordinator = File(
        "src/main/java/com/neoproc/neoproccamx/ui/Camera2FrameLineageCoordinator.kt"
    ).readText()

    @Test
    fun `camera2 result feeds sensor identity without replacing still metadata delivery`() {
        val callback = source
            .substringAfter("camera.onCaptureResult = { result ->")
            .substringBefore("camera.onSessionGenerationAdvanced")

        val stillCaptureDelivery = callback.indexOf("stillCapture.onCaptureResult(result)")
        val sensorPipelineDelivery = callback.indexOf("pipeline.onCaptureResult(result, sessionGeneration)")

        assertTrue(callback.contains("camera.onCaptureResultWithSessionGeneration"))
        assertTrue(callback.contains("recordCamera2FrameLineageCaptureResult(result, sessionGeneration)"))
        assertTrue(stillCaptureDelivery >= 0)
        assertTrue(sensorPipelineDelivery > stillCaptureDelivery)
        assertTrue(callback.contains("catch (exception: Exception)"))
        assertTrue(callback.contains("Sensor-frame diagnostics rejected a Camera2 capture result"))
    }

    @Test
    fun `confirmed compositor presentation advances display lifecycle`() {
        val callback = source
            .substringAfter("private fun recordCompositedPreviewFrameLineageSubmission")
            .substringBefore("private fun camera2FrameLineageRuntimeMetadata")

        assertTrue(callback.contains("pipeline.onDisplayedFrame(event.sensorTimestampNs)"))
        assertTrue(callback.contains("successful visible-window swap"))
        assertTrue(callback.contains("event?.let(::publishPreviewDisplayPresent)"))
        assertTrue(callback.contains("camera2FrameLineage.recordPreviewPresentation("))
        assertTrue(lineageCoordinator.contains("activeAccumulator.recordPreviewSubmission("))
        assertTrue(lineageCoordinator.contains("maybeFreezeIfReadyLocked()"))
    }

    @Test
    fun `recording lineage uses the exact Camera2 session generation carried by callbacks`() {
        val beginSession = source
            .substringAfter("private fun beginCamera2FrameLineageSession(")
            .substringBefore("private fun freezeCamera2FrameLineageSession()")

        assertTrue(beginSession.contains("val generation = camera.sessionGeneration.get()"))
        assertTrue(beginSession.contains("camera2FrameLineage.begin(generation"))
        assertTrue(lineageCoordinator.contains(
            "Camera2FrameLineageAccumulator(expectedSessionGeneration = generation)"
        ))
        assertTrue(!beginSession.contains("SystemClock.elapsedRealtime"))
    }

    @Test
    fun `recording lineage rebases when Camera2 replaces the prearmed session`() {
        val generationCallback = source
            .substringAfter("camera.onSessionGenerationAdvanced = { generation ->")
            .substringBefore("camera.onSessionGenerationConfigured")
        val rebase = source
            .substringAfter("private fun rebaseCamera2FrameLineageSessionIfActive(generation: Long)")
            .substringBefore("private fun freezeCamera2FrameLineageSession()")

        assertTrue(generationCallback.contains("rebaseCamera2FrameLineageSessionIfActive(generation)"))
        assertTrue(rebase.contains("camera2FrameLineage.rebaseIfActive(generation)"))
        assertTrue(lineageCoordinator.contains("if (!recordingActive || accumulator == null)"))
        assertTrue(lineageCoordinator.contains("generation == sessionGeneration"))
        assertTrue(lineageCoordinator.contains(
            "Camera2FrameLineageAccumulator(expectedSessionGeneration = generation)"
        ))
        assertTrue(lineageCoordinator.contains("sessionRebaseCount++"))
        assertTrue(lineageCoordinator.contains("discardedPreRebaseCaptureResults"))
    }

    @Test
    fun `confirmed container commits advance recording lifecycle and listener is released`() {
        val initialization = source
            .substringAfter("gpuRecording.runtimeEvidenceMetadataProvider")
            .substringBefore("thermalStatusCoordinator =")
        val teardown = source.substringAfter("override fun onCleared()")

        assertTrue(initialization.contains("gpuRecording.recordedFrameCommitListener"))
        assertTrue(initialization.contains("pipeline.onRecordedFrame(sensorTimestampNs)"))
        assertTrue(teardown.contains("gpuRecording.recordedFrameCommitListener = null"))
        assertTrue(teardown.contains("gpuRecording::destroy"))
        assertTrue(
            teardown.indexOf("gpuRecording.recordedFrameCommitListener = null") <
                teardown.indexOf("gpuRecording::destroy")
        )
    }

    @Test
    fun `ViewModel teardown transfers native owners without joining on lifecycle thread`() {
        val teardown = source
            .substringAfter("override fun onCleared()")
            .substringBefore("private fun buildVulkanPreviewTransform")
        val request = teardown.indexOf("requestSensorLinearNdrRecordingIngressTeardown()")
        val lease = teardown.indexOf("CameraNativeResourceRetirementLease(")
        val schedule = teardown.indexOf("nativeResourceRetirement.schedule(")

        assertTrue(request >= 0)
        assertTrue(lease > request)
        assertTrue(schedule > lease)
        assertTrue(teardown.contains("camera::destroy"))
        assertTrue(teardown.contains("camera.onYuvFrame = null"))
        assertTrue(teardown.contains("camera.onHardwareFrame = null"))
        assertTrue(teardown.contains("camera.onCaptureResult = null"))
        assertTrue(teardown.contains("camera.onCameraPipelineWarning = null"))
        assertTrue(!teardown.contains("camera.closeReadersInsideFramePipelineBarrier = null"))
        assertTrue(teardown.contains("previewGlCompositor.setOnFramePresentedListener(null)"))
        assertTrue(teardown.contains("hdr10PqPreviewCompositor.setOnProjectOwnedFramePresentedListener(null)"))
        assertTrue(teardown.contains("persistentPreviewOutputRouter.setOnFramePresentedListener(null)"))
        assertTrue(teardown.contains("gpuRecording.runtimeEvidenceMetadataProvider = null"))
        assertTrue(teardown.contains("npv10Recording.runtimeEvidenceMetadataProvider = null"))
        assertTrue(teardown.contains("recordingForegroundProtection::stop"))
        assertTrue(teardown.contains("viewModelTeardownStarted.set(true)"))
        assertTrue(teardown.contains("aiCameraPipeline.detachListenerForTeardown()"))
        assertTrue(teardown.contains("stillCapture.detachRuntimeProvidersForTeardown()"))
        assertTrue(!teardown.contains("stopSensorLinearNdrRecordingIngress("))
        assertTrue(!teardown.contains("camera.destroy()"))
        assertTrue(!teardown.contains("vulkanRuntime.close()"))
    }
}
