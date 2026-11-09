package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrRecordingStopContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `stop keeps teardown proof independent from strict clip failure`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val stop = manager
            .substringAfter("internal fun stopSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun isSensorLinearNdrRecordingIngressActive()")

        assertTrue(stop.contains("): SensorLinearNdrRecordingStopResult"))
        assertTrue(stop.contains("ingressFailed = sensorLinearNdrRecordingFailureReported.get()"))
        assertTrue(stop.contains("return result(teardownSafe = idle)"))
        assertFalse(stop.contains("teardownSafe && !sensorLinearNdrRecordingFailureReported.get()"))
    }

    @Test
    fun `drain timeout preserves clip failure separately from mandatory teardown proof`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val stop = manager
            .substringAfter("internal fun stopSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun isSensorLinearNdrRecordingIngressActive()")
        val timeoutBranch = stop
            .substringAfter("if (!completed) {")
            .substringBefore("val idle = awaitSensorLinearNdrRecordingWorkerIdle(timeoutMs)")

        assertTrue(timeoutBranch.contains("Timed out draining ordered NDR recording ingress"))
        assertTrue(timeoutBranch.contains("awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)"))
        assertTrue(timeoutBranch.contains("return result(teardownSafe = teardownSafe)"))
        assertFalse(timeoutBranch.contains("return false"))
    }

    @Test
    fun `view model separates unsafe tail ownership from prior ingress clip failure`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val stop = viewModel
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun stopRecordingForegroundService()")
        val tailDecision = stop
            .substringAfter("if (sensorLinearNdrOwnershipWasActive) {")
            .substringBefore("val rawCaptureStopResult = stopRawCaptureIfNeeded()")
        val unsafeBranch = tailDecision
            .substringAfter("if (!ingressStopResult.teardownSafe) {")
            .substringBefore("} else if (ingressStopResult.ingressFailed) {")
        val priorClipFailureBranch = tailDecision
            .substringAfter("} else if (ingressStopResult.ingressFailed) {")
            .substringBefore("Log.w(")

        assertTrue(unsafeBranch.contains("Ordered NDR recording tail did not drain"))
        assertTrue(tailDecision.contains("protected ownership drained safely"))
        // The manager publishes failureReported before invoking its listener outside the lifecycle
        // lock. Stop may therefore see ingressFailed first; a clip-quality fallback must close the
        // saved-path gate without falsely claiming that safely drained ownership was unsafe.
        assertTrue(priorClipFailureBranch.contains("sensorLinearNdrRecordingFailure.compareAndSet("))
        assertTrue(priorClipFailureBranch.contains("Strict NDR clip-quality ingress failure"))
        assertFalse(priorClipFailureBranch.contains("tail did not drain"))
        assertFalse(priorClipFailureBranch.contains("before encoder shutdown"))
        assertTrue(stop.contains("val savedPath = if (sensorLinearFailure == null)"))
    }
}
