package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Lq001ImportAndMetadataLifecycleContractTest {
    private val runtime = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
    ).readText()
    private val pipeline = File(
        "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
    ).readText()
    private val liveCapture = File(
        "src/main/java/com/neoproc/neoproccamx/niv99/android/Niv99LiveCaptureCoordinator.kt"
    ).readText()

    @Test
    fun `copied full frame analysis performs a bounded exact metadata join`() {
        val analysis = liveCapture
            .substringAfter("fun onAnalysisFrame(")
            .substringBefore("private fun rejectAnalysisFrame(")

        assertTrue(analysis.contains("ring.awaitExact("))
        assertTrue(analysis.contains("Niv99DeferredMetadataJoinPolicy.timeoutNs("))
        assertTrue(analysis.indexOf("ring.awaitExact(") < analysis.indexOf("fullFrameEstimator.estimate("))
    }

    @Test
    fun `preview import reset owns the exclusive native import boundary`() {
        val reset = runtime
            .substringAfter("fun beginPreviewImportSession(")
            .substringBefore("internal fun hasOffscreenAhbEglEncoderOwnership(")

        assertTrue(reset.contains("cameraImportSessionGate.withExclusiveMutation"))
        assertTrue(reset.indexOf("withExclusiveMutation") < reset.indexOf("bridge.cleanupImportedFrames()"))
        assertTrue(reset.indexOf("withExclusiveMutation") < reset.indexOf("resetSession(now)"))
    }

    @Test
    fun `camera generation is bound from pipeline admission through native import completion`() {
        val beginSession = pipeline
            .substringAfter("fun beginCameraSession(")
            .substringBefore("fun setCameraCalibration(")
        val trackedSubmit = pipeline
            .substringAfter("private fun submitTrackedImageToVulkan(")
            .substringBefore("private fun strictNoQualityDowngradeActive")
        val nativeSubmit = runtime
            .substringAfter("fun submitCameraImage(")
            .substringBefore("private fun emitFrameLineageSubmission(")

        assertTrue(beginSession.contains("vulkanRuntime?.beginCameraImportSession(sessionGeneration)"))
        assertTrue(
            beginSession.indexOf("beginCameraImportSession(sessionGeneration)") <
                beginSession.indexOf("activeCameraSessionGeneration.set(sessionGeneration)")
        )
        assertTrue(trackedSubmit.contains("record.registrySessionGeneration"))
        assertTrue(nativeSubmit.contains("cameraImportSessionGate.acquireSubmission(cameraSessionGeneration)"))
        assertTrue(nativeSubmit.contains("importSessionLease.close()"))
    }

    @Test
    fun `full resolution GPU submit worker contains no diagnostic snapshot policy or routing lock`() {
        val submit = pipeline
            .substringAfter("private fun submitPreparedHardwareFrame(")
            .substringBefore("private fun scheduleHardwareImportDiagnostics(")
        val diagnostics = pipeline
            .substringAfter("private fun scheduleHardwareImportDiagnostics(")
            .substringBefore("private fun markHardwareFrameNotSubmitted(")

        assertTrue(submit.contains("lastHardwareImportElapsedMs.set("))
        assertTrue(submit.contains("scheduleHardwareImportDiagnostics("))
        assertTrue(!submit.contains("realtimeDispatchTelemetry()"))
        assertTrue(!submit.contains("PipelineOrchestrator.resolveBackpressureTuning("))
        assertTrue(!submit.contains("synchronized(routingStateLock)"))
        assertTrue(!submit.contains("FrameImportRoutingPolicy.recordHardwareImport("))
        assertTrue(diagnostics.contains("processingExecutor.execute"))
        assertTrue(diagnostics.contains("realtimeDispatchTelemetry()"))
        assertTrue(diagnostics.contains("PipelineOrchestrator.resolveBackpressureTuning("))
    }
}
