package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contracts for the exact-request P010 startup/stop ownership race. */
class P010ProducerReadinessContractTest {
    private val source =
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()

    @Test
    fun `tracked repeating sequence owns the exact request surface and first frame proof`() {
        val model = source
            .substringAfter("private data class ActiveCameraRepeatingSequence(")
            .substringBefore("private class P010StopBoundaryRequestToken(")
        val submit = source
            .substringAfter("private fun submitTrackedRepeatingRequest(")
            .substringBefore("private fun submitTrackedCaptureRequest(")

        assertTrue(model.contains("val request: CaptureRequest"))
        assertTrue(model.contains("val p010Surface: Surface?"))
        assertTrue(model.contains("val firstFrameProof: CompletableFuture<P010RepeatingFrameProof>"))
        assertTrue(submit.contains("request = request"))
        assertTrue(submit.contains("p010Surface = p010Surface"))
    }

    @Test
    fun `only the active exact request can prove a current generation sensor frame`() {
        val completed = source
            .substringAfter("override fun onCaptureCompleted(")
            .substringBefore("previewHealthMonitor.trackCaptureResult()")

        assertTrue(completed.contains("activeRepeatingSequence.get() === repeating"))
        assertTrue(completed.contains("repeating.identity.session === s"))
        assertTrue(completed.contains("repeating.identity.sessionGeneration == captureSessionGeneration"))
        assertTrue(completed.contains("repeating.identity.sessionGeneration == sessionGeneration.get()"))
        assertTrue(completed.contains("repeating.request === r"))
        assertTrue(completed.contains("result.frameNumber >= 0L"))
        assertTrue(completed.contains("sensorTimestampNs > 0L"))
        assertTrue(completed.contains("repeating.firstFrameProof.complete("))
    }

    @Test
    fun `producer readiness requires recording target fps session binding and proven exact request`() {
        val readiness = source
            .substringAfter("override fun npv10TenBitProducerProof(")
            .substringBefore("override fun resolveNpv10CaptureSourcePlan(")

        assertTrue(readiness.contains("!recordingActive"))
        assertTrue(readiness.contains("targetCameraFps != fps"))
        assertTrue(readiness.contains("!isNpv10TenBitSessionConfigured(size)"))
        assertTrue(readiness.contains("binding?.session !== session"))
        assertTrue(readiness.contains("lastAppliedFpsRequestPlan?.appliedFps != fps"))
        assertTrue(readiness.contains("repeating.identity.session !== session"))
        assertTrue(readiness.contains("repeating.identity.sessionGeneration != generation"))
        assertTrue(readiness.contains("repeating.p010Surface !== p010Surface"))
        assertTrue(readiness.contains("repeating.firstFrameProof.isCompletedExceptionally"))
        assertTrue(readiness.contains("repeating.firstFrameProof.getNow(null)"))
        assertTrue(readiness.contains("activeRepeatingSequence.get() !== repeating"))
        assertTrue(readiness.contains("firstFrameNumber = firstFrame.frameNumber"))
        assertTrue(readiness.contains("firstFrameSensorTimestampNs = firstFrame.sensorTimestampNs"))
    }

    @Test
    fun `startup lease serializes control refreshes with camera2 request publication`() {
        val submit = source
            .substringAfter("private fun submitTrackedRepeatingRequest(")
            .substringBefore("internal fun currentRepeatingReadinessSnapshot()")
        val start = source
            .substringAfter("internal fun startNpv10VideoCaptureWithProducerLease()")
            .substringBefore("override fun stopVideoCapture()")

        val admission = submit.indexOf("npv10ProducerStartupLeaseGate.admitSubmission(")
        val camera2Submission = submit.indexOf("session.setRepeatingRequest(")
        assertTrue(submit.contains("synchronized(cameraSubmissionLock)"))
        assertTrue(admission >= 0)
        assertTrue(camera2Submission > admission)
        assertTrue(start.contains("synchronized(cameraSubmissionLock)"))
        assertTrue(start.contains("npv10ProducerStartupLeaseGate.acquire(generation)"))
        assertTrue(start.contains("restartRepeatingRequestForRecordTargetChange(lease)"))
        assertTrue(start.contains("requestCommit = ::commitNpv10ProducerStartupLease"))
        assertTrue(start.contains("cancel = ::cancelNpv10ProducerStartupLease"))
        assertTrue(submit.indexOf("session.setRepeatingRequest(") <
            submit.indexOf("completeDeferredReplay(authorizedNpv10StartupLease)"))
        assertTrue(submit.indexOf("Npv10ProducerStartupCommitPolicy.canCommitReplay(") <
            submit.indexOf("completeDeferredReplay(authorizedNpv10StartupLease)"))
        assertTrue(submit.contains("p010TargetPresent = p010Surface != null"))
        assertTrue(submit.contains("sessionCurrent = session === captureSession"))
        assertTrue(source.contains("npv10ProducerStartupLeaseGate.invalidate("))
        val generationAdvance = source
            .substringAfter("private fun advanceSessionGeneration(reason: String): Long")
            .substringBefore("private fun recordSoftwareAeControlRevision(")
        assertTrue(generationAdvance.indexOf("synchronized(cameraSubmissionLock)") <
            generationAdvance.indexOf("sessionGeneration.incrementAndGet()"))
        assertTrue(generationAdvance.indexOf("sessionGeneration.incrementAndGet()") <
            generationAdvance.indexOf("npv10ProducerStartupLeaseGate.invalidate("))
    }

    @Test
    fun `initial and replay publication failures revoke instead of escaping the lease`() {
        val restart = source
            .substringAfter("private fun restartRepeatingRequestForRecordTargetChange(")
            .substringBefore("override fun drainRawReaderSynchronously()")
        val transitionGate = source
            .substringAfter("private fun sessionRefreshTransitionActive()")
            .substringBefore("private fun publishRepeatingRequestIfSessionStable")

        assertTrue(restart.contains("if (deferRepeatingRefreshBehindActiveBoundary())"))
        assertTrue(restart.contains("if (sessionRefreshTransitionActive())"))
        assertTrue(transitionGate.contains("sessionRefreshPending = sessionRefreshPending.get()"))
        assertTrue(transitionGate.contains("queuedSessionRefreshDispatches = queuedSessionRefreshDispatches"))
        assertTrue(restart.contains("dispatchNpv10AuthorizedPublication(authorizedNpv10StartupLease)"))
        assertTrue(restart.contains("failNpv10AuthorizedPublication("))
        assertTrue(restart.contains("publicationBefore = repeatingPublicationGeneration.get()"))
        assertTrue(restart.contains("repeating.publicationGeneration > publicationBefore"))
        assertTrue(restart.contains("Authorized NPV10 Camera2 repeating request was not published"))
        assertFalse(restart.contains("abandonNpv10DeferredReplay"))
    }

    @Test
    fun `stop fence waits for proof then identity revalidates before stop repeating`() {
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")
        val proof = stop.indexOf("token.repeatingSequence.firstFrameProof.get(")
        val revalidation = stop.indexOf(
            "activeRepeatingSequence.get() !== token.repeatingSequence",
            proof
        )
        val stopRepeating = stop.indexOf("token.session.stopRepeating()", revalidation)

        assertTrue(proof >= 0)
        assertTrue(revalidation > proof)
        assertTrue(stopRepeating > revalidation)
    }

    @Test
    fun `idle npv10 p010 is a stable record output and equivalent refresh preserves its sequence`() {
        val role = source
            .substringAfter("private fun sessionSurfaceKindFor(surface: Surface)")
            .substringBefore("private fun streamUseCaseRoleFor(surface: Surface)")
        val preserve = source
            .substringAfter("private fun canPreserveActiveNpv10RepeatingSequence(")
            .substringBefore("private fun createSession(")
        val reuse = source
            .substringAfter("CameraSessionConfigurationDeduplicationPolicy.shouldReuseConfiguredSession(")
            .substringBefore("// Final admission and generation advance")

        assertTrue(role.contains("npv10TenBitCaptureEnabled && surface == yuvReader?.surface"))
        assertTrue(role.contains("return CameraSessionSurfaceKind.RECORD"))
        assertTrue(preserve.contains("repeating.identity.session === retainedSession"))
        assertTrue(preserve.contains("repeating.identity.sessionGeneration == generation"))
        assertTrue(preserve.contains("repeating.p010Surface === p010Surface"))
        assertTrue(reuse.contains("canPreserveActiveNpv10RepeatingSequence("))
        assertTrue(reuse.contains("if (!preserveNpv10Repeating)"))
    }
}
