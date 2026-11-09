package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrPreviewStopLifecycleContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    private val viewModel =
        source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
    private val lineage =
        source("src/main/java/com/neoproc/neoproccamx/ui/Camera2FrameLineageCoordinator.kt")
    private val retirement =
        source("src/main/java/com/neoproc/neoproccamx/ui/NativeResourceRetirementCoordinator.kt")

    @Test
    fun `normal stop drains ingress then preview before lineage freeze and preview ownership resume`() {
        val stop = viewModel
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun shouldUseUncompressedCinemaPipeline()")
        val boundary = stop
            .substringAfter("if (sensorLinearNdrOwnershipWasActive) {")
            .substringBefore("val rawCaptureStopResult = stopRawCaptureIfNeeded()")

        val ingress = boundary.indexOf(
            "sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)"
        )
        val previewDrain = boundary.indexOf(
            "drainSensorLinearNdrRecordingPreviewBoundary(\"normal-stop\")"
        )
        val freeze = boundary.indexOf("freezeCamera2FrameLineageSession()", previewDrain)
        val encoderDetach = stop.indexOf(
            "deferHardwareSourceRebuildUntilAfterEncoderStop = true",
            freeze
        )
        val ownershipReturn = stop.indexOf(
            "sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()",
            encoderDetach
        )
        val resume = stop.indexOf("vulkanRuntime.resumeSensorLinearNdrPreviewAfterDrain()")

        assertTrue(ingress >= 0)
        assertTrue(previewDrain > ingress)
        assertTrue(freeze > previewDrain)
        assertTrue(encoderDetach > freeze)
        assertTrue(ownershipReturn > encoderDetach)
        assertTrue(resume > ownershipReturn)
        assertTrue(stop.substring(ownershipReturn, resume).contains("sensorLinearDemosaicReturnedToPreview"))
        assertTrue(stop.substring(ownershipReturn, resume).contains("sensorLinearLineageFrozen"))
        assertTrue(stop.substring(ownershipReturn, resume).contains("sensorLinearPreviewWorkerQuiesced"))

        val detachHelper = viewModel
            .substringAfter("private suspend fun detachInternalRecordingFeedBeforeEncoderStop(")
            .substringBefore("private fun stopRawCaptureIfNeeded()")
        assertTrue(detachHelper.contains("\"recording-stop\""))
        assertTrue(detachHelper.contains("detachAllGpuEncoderSurfacesWithProof("))
        assertTrue(detachHelper.contains("core/offscreen GPU encoder detach was not acknowledged"))
    }

    @Test
    fun `preview failure is latched as clip failure without weakening recording gates`() {
        val helper = viewModel
            .substringAfter("private fun drainSensorLinearNdrRecordingPreviewBoundary(")
            .substringBefore("private fun beginSensorPipelineSession(")

        assertTrue(helper.contains("drainSensorLinearNdrRecordingPreview(requireHealthy = false)"))
        assertTrue(helper.contains("snapshot.lastFailure"))
        assertTrue(helper.contains("snapshot.outstandingTasks"))
        assertTrue(helper.contains("sensorLinearNdrRecordingFailure.compareAndSet("))
        assertFalse(helper.contains("setOffscreenEncoderTargetFrameRate"))
        assertFalse(helper.contains("videoBitrate"))
        assertFalse(helper.contains("recording.resolution"))
    }

    @Test
    fun `failed start drains preview before freeze and resumes only after preview ownership returns`() {
        val cleanup = viewModel
            .substringAfter("private suspend fun cleanupFailedRecordingStart(")
            .substringBefore("private suspend fun awaitNextVulkanImportStatsOrTimeout(")

        val ingress = cleanup.indexOf(
            "sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)"
        )
        val previewDrain = cleanup.indexOf(
            "drainSensorLinearNdrRecordingPreviewBoundary(\"startup-failure\")"
        )
        val freeze = cleanup.indexOf("freezeCamera2FrameLineageSession()", previewDrain)
        val encoderDetach = cleanup.indexOf(
            "detachAllGpuEncoderSurfacesWithProof(\"failed-start-cleanup\")",
            freeze
        )
        val ownershipReturn = cleanup.indexOf(
            "sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()",
            encoderDetach
        )
        val resume = cleanup.indexOf("vulkanRuntime.resumeSensorLinearNdrPreviewAfterDrain()")

        assertTrue(ingress >= 0)
        assertTrue(previewDrain > ingress)
        assertTrue(freeze > previewDrain)
        assertTrue(encoderDetach > freeze)
        assertTrue(ownershipReturn > encoderDetach)
        assertTrue(resume > ownershipReturn)
    }

    @Test
    fun `teardown drains preview then freezes lineage before native destruction`() {
        val lease = retirement.substringAfter("internal class CameraNativeResourceRetirementLease(")
        val ingressProof = lease.indexOf("if (!ingressRetired)")
        val previewDrain = lease.indexOf(
            "vulkanRuntime.drainSensorLinearNdrRecordingPreview(requireHealthy = false)"
        )
        val lineageFreeze = lease.indexOf(
            "retireSafely(\"recording frame-lineage boundary\""
        )
        val encoderDetach = lease.indexOf(
            "vulkanRuntime.detachAllEncoderSurfacesWithProof()"
        )
        val onCleared = viewModel
            .substringAfter("override fun onCleared()")
            .substringBefore("private fun buildVulkanPreviewTransform(")
        val captureLineageDrain = onCleared.indexOf(
            "drainAndShutdownCamera2LineageForTeardown()"
        )
        val callbackFreeze = onCleared.indexOf("freezeCamera2FrameLineageSession()")
        val callbackDetach = onCleared.indexOf(
            "vulkanRuntime.setFrameLineageSubmissionListener(null)"
        )

        assertTrue(previewDrain > ingressProof)
        assertTrue(lineageFreeze > previewDrain)
        assertTrue(encoderDetach > lineageFreeze)
        assertTrue(captureLineageDrain >= 0)
        assertTrue(callbackFreeze > captureLineageDrain)
        assertTrue(callbackDetach > callbackFreeze)
        assertFalse(onCleared.contains("camera2LineageExecutor.shutdownNow()"))
        assertFalse(onCleared.contains("camera2LineageIngress.clear()"))
    }

    @Test
    fun `teardown lineage barrier drains FIFO and cannot finish ahead of a rescheduled drain`() {
        val captureIngress = viewModel
            .substringAfter("private fun recordCamera2FrameLineageCaptureResult(")
            .substringBefore("private fun drainAndShutdownCamera2LineageForTeardown(")
        val terminalDrain = lineage
            .substringAfter("private fun sealAndDrainCaptureIngress(")
            .substringBefore("private fun recordCaptureIngress(")
        val shutdown = lineage
            .substringAfter("fun drainAndShutdownForTeardown(")
            .substringBefore("private fun drainIngressQueue(")

        assertTrue(captureIngress.contains(
            "camera2FrameLineage.recordCaptureResult(result, captureSessionGeneration)"
        ))
        assertTrue(lineage.contains("synchronized(ingressLifecycleLock)"))
        assertTrue(lineage.indexOf("ingress.offer(") < lineage.indexOf("scheduleDrain()"))
        assertTrue(terminalDrain.contains(
            "executor.submit { drainIngressQueue() }"
        ))
        assertFalse(terminalDrain.contains("submit { Unit }"))
        assertFalse(terminalDrain.substringAfter("if (!barrierReached) return false")
            .contains("drainScheduled.get()"))
        assertTrue(shutdown.contains("executor.shutdown()"))
        assertTrue(shutdown.contains("executor.awaitTermination("))
        assertTrue(shutdown.contains("!drainScheduled.get()"))
    }
}
