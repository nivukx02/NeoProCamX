package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P010StopFenceControllerContractTest {
    private val source =
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()

    @Test
    fun `one central submission gate tracks every repeating sequence`() {
        assertEquals(1, Regex("\\.setRepeatingRequest\\(").findAll(source).count())
        assertEquals(1, Regex("\\.capture\\(").findAll(source).count())
        assertTrue(source.contains("private val activeRepeatingSequence ="))
        assertTrue(source.contains("sequenceId = sequenceId"))
        assertTrue(source.contains("sessionGeneration = generation"))
        assertTrue(source.contains("p010Surface = p010Surface"))
    }

    @Test
    fun `old sequence completion drives exclusive tagged marker submission`() {
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")
        val marker = source
            .substringAfter("private fun submitP010StopMarkerAfterRepeatingCompletion(")
            .substringBefore("internal fun stopNpv10P010CaptureAtExactBoundary(")
        val sequenceCallback = source
            .substringAfter("override fun onCaptureSequenceCompleted(")
            .substringBefore("private fun maybeLogPhysicalTelemetryForShaderProfiles(")

        assertTrue(stop.contains("token.session.stopRepeating()"))
        assertFalse(stop.contains("submitTrackedCaptureRequest("))
        assertFalse(stop.contains(".abortCaptures("))
        assertTrue(sequenceCallback.contains("val submitMarker = token.state.onRepeatingSequenceCompleted("))
        assertTrue(sequenceCallback.indexOf("if (submitMarker)") <
            sequenceCallback.indexOf("submitP010StopMarkerAfterRepeatingCompletion(token)"))
        assertTrue(sequenceCallback.contains("token.state.onMarkerSequenceCompleted("))
        assertTrue(marker.contains("device.createCaptureRequest(requestPlan.template)"))
        assertTrue(marker.contains("addTarget(token.p010Surface)"))
        assertTrue(marker.contains("setTag(token)"))
        assertTrue(marker.contains("targetsP010 = true"))
    }

    @Test
    fun `marker result failure loss abort stale and interrupt all fail closed`() {
        val callback = source
            .substringAfter("private val captureCallback = object")
            .substringBefore("private fun maybeLogPhysicalTelemetryForShaderProfiles(")
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")

        assertTrue(callback.contains("sequenceId = markerSequenceId"))
        assertTrue(callback.contains("Stale Camera2 session delivered the P010 stop marker result"))
        assertTrue(callback.contains("Camera2 failed the P010 stop marker"))
        assertTrue(callback.contains("Camera2 lost the exact P010 stop marker buffer"))
        assertTrue(callback.contains("token.state.onSequenceAborted("))
        assertTrue(stop.contains("token.state.awaitCameraMarker(remainingNanos())"))
        assertTrue(stop.contains("is P010StopBoundaryCameraResult.Failed -> poison(cameraResult.reason)"))
    }

    @Test
    fun `fence releases after sink detach callback and replays at most one refresh`() {
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")
        val cleanup = stop.indexOf("sealDetachAndBeginFinalization(remainingMillis())")
        val release = stop.indexOf("activeP010StopBoundary.compareAndSet(token, null)")
        val replay = stop.indexOf("p010StopDeferredRequestRefresh.drain()", release)

        assertTrue(cleanup >= 0)
        assertTrue(release > cleanup)
        assertTrue(replay > release)
        assertTrue(stop.contains("if (cleanupSinkDetached)"))
        assertTrue(stop.contains("p010StopDeferredRequestRefresh.discard()"))
        assertEquals(1, Regex("p010StopDeferredRequestRefresh\\.drain\\(\\)").findAll(stop).count())
        assertFalse(stop.contains("sleep("))
        assertFalse(stop.contains("delay("))
    }

    @Test
    fun `full session refresh coalesces behind the exact live stop owner`() {
        val helper = source
            .substringAfter("private fun deferSessionRefreshBehindP010StopBoundary(")
            .substringBefore("fun refreshOutputsAndSession(")

        assertTrue(helper.contains("rebuildReaders: Boolean"))
        assertTrue(helper.contains("forceRebuild: Boolean"))
        assertTrue(helper.contains("allowIdenticalRecoveryRebuild: Boolean"))
        assertTrue(helper.contains("reason: String"))
        assertTrue(helper.contains("onConfigured: () -> Unit"))
        assertTrue(helper.contains("onPreviousSessionClosed: (() -> Unit)?"))
        assertTrue(helper.contains("synchronized(cameraSubmissionLock)"))
        assertTrue(
            "The stop owner must be sampled and then identity-rechecked under the submission lock",
            Regex("activeP010StopBoundary\\.get\\(\\)").findAll(helper).count() >= 2
        )
        assertTrue(helper.contains("!=="))
        assertTrue(helper.contains("queueTrailingSessionRefresh("))
        assertTrue(helper.contains("rebuildReaders = rebuildReaders"))
        assertTrue(helper.contains("forceRebuild = forceRebuild"))
        assertTrue(helper.contains("allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild"))
        assertTrue(helper.contains("reason = reason"))
        assertTrue(helper.contains("onConfigured = onConfigured"))
        assertTrue(helper.contains("onPreviousSessionClosed = onPreviousSessionClosed"))
        assertFalse(helper.contains("setupReaders("))
        assertFalse(helper.contains("createSession("))
        assertFalse(helper.contains("advanceSessionGeneration("))
    }

    @Test
    fun `full refresh admission and camera execution both recheck the stop fence`() {
        val refresh = source
            .substringAfter("fun refreshOutputsAndSession(")
            .substringBefore("private fun hdrSurfaceRoleFor(")
        val batchAdmissionHelper = source
            .substringAfter("private fun queueBatchedSessionRefreshIfActive(")
            .substringBefore("private fun deferRepeatingRefreshToSessionTransaction(")
        val initialFence = refresh.indexOf("deferSessionRefreshBehindP010StopBoundary(")
        val batchAdmission = refresh.indexOf("if (queueBatchedSessionRefreshIfActive(")
        val cameraDispatch = refresh.indexOf("dispatchSessionRefreshOnCameraThread(")
        val cameraFence = refresh.indexOf(
            "deferSessionRefreshBehindP010StopBoundary(",
            initialFence + 1
        )
        val finalAdmissionFence = refresh.indexOf(
            "deferSessionRefreshBehindP010StopBoundary(",
            cameraFence + 1
        )
        val refreshClaim = refresh.indexOf("sessionRefreshPending.compareAndSet(false, true)")
        val readerMutation = refresh.indexOf("setupReaders()")
        val mutationLock = refresh.lastIndexOf("synchronized(cameraSubmissionLock)", readerMutation)
        val sessionMutation = refresh.indexOf("createSession(")
        val cadenceWindow = refresh.indexOf("recordFrameDeliveryLedger.markReconfigureStart()")

        assertTrue(initialFence >= 0)
        assertTrue(batchAdmissionHelper.contains("refreshBatchDepth.get() <= 0"))
        assertTrue("P010 ownership must win before mode-switch batching", initialFence < batchAdmission)
        assertTrue(cameraDispatch > batchAdmission)
        assertTrue("Camera-thread execution must recheck ownership", cameraFence > cameraDispatch)
        assertTrue(cameraFence < refreshClaim)
        assertTrue("Final refresh admission must share the stop-fence lock", mutationLock > refreshClaim)
        assertTrue(finalAdmissionFence > mutationLock)
        assertTrue(finalAdmissionFence < readerMutation)
        assertTrue(finalAdmissionFence < cadenceWindow)
        assertTrue(cadenceWindow < sessionMutation)
    }

    @Test
    fun `pre generation refresh recheck is atomic and preserves strict lineage`() {
        val createSession = source
            .substringAfter("private fun createSession(")
            .substringBefore("private fun handleCreateSessionIllegalState(")
        val generationAdvance = createSession.indexOf(
            "advanceSessionGeneration(\"create-session:\$refreshReason\")"
        )
        val generationLock = createSession.lastIndexOf(
            "synchronized(cameraSubmissionLock)",
            generationAdvance
        )
        val finalFence = createSession.lastIndexOf(
            "deferSessionRefreshBehindP010StopBoundary(",
            generationAdvance
        )
        val prepare = createSession.indexOf("prepareForCameraSessionRebuild(dev)")
        val criticalSection = if (generationLock >= 0 && generationAdvance > generationLock) {
            createSession.substring(generationLock, generationAdvance)
        } else {
            ""
        }
        val clearOwnership = source
            .substringAfter("private fun clearSessionRefreshOwnership()")
            .substringBefore("private fun retireStaleSessionRefreshOwnershipIfNeeded")

        assertTrue(generationAdvance >= 0)
        assertTrue("Final ownership check and generation advance must share the submission lock", generationLock >= 0)
        assertTrue(finalFence > generationLock)
        assertTrue(finalFence < prepare)
        assertTrue(prepare < generationAdvance)
        assertTrue(criticalSection.contains("clearSessionRefreshOwnership()"))
        assertTrue(clearOwnership.contains("sessionRefreshPending.set(false)"))
        assertTrue(criticalSection.contains("recordFrameDeliveryLedger.markReconfigureSettled()"))
        assertEquals(
            "Session lineage must advance through one audited authority only",
            1,
            Regex("sessionGeneration\\.incrementAndGet\\(\\)").findAll(source).count()
        )
    }

    @Test
    fun `retired stop owner drains full and repeating refresh only after safe release`() {
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")
        val release = stop.indexOf("activeP010StopBoundary.compareAndSet(token, null)")
        val successfulRelease = stop
            .substringAfter("if (cleanupSinkDetached) {")
            .substringBefore("} else {")
        val repeatingDrain = stop.indexOf("p010StopDeferredRequestRefresh.drain()", release)
        val sessionDrain = stop.indexOf("runTrailingSessionRefreshIfNeeded()", release)
        val failedDetachDiscard = stop.indexOf("p010StopDeferredRequestRefresh.discard()", release)

        assertTrue(release >= 0)
        assertTrue(repeatingDrain > release)
        assertTrue(sessionDrain > release)
        assertTrue(successfulRelease.contains("p010StopDeferredRequestRefresh.drain()"))
        assertTrue(successfulRelease.contains("runTrailingSessionRefreshIfNeeded()"))
        assertTrue(failedDetachDiscard > sessionDrain)
        assertEquals(1, Regex("runTrailingSessionRefreshIfNeeded\\(\\)").findAll(stop).count())
    }

    @Test
    fun `recorder publication boundary is established inside exact proven transition`() {
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")

        val fifoProof = stop.indexOf("token.state.onReaderFifoDrained(establishProducerBoundary)")
        val boundaryResult = stop.indexOf("boundaryProven = token.state.await(", fifoProof)
        val logicalStop = stop.indexOf("stopP010RecordingStateWithoutRetarget(", boundaryResult)
        val cleanup = stop.indexOf("sealDetachAndBeginFinalization(remainingMillis())", logicalStop)

        assertTrue(stop.contains("establishProducerBoundary: (Long) -> Boolean"))
        assertTrue(fifoProof >= 0)
        assertTrue(boundaryResult > fifoProof)
        assertTrue(logicalStop > boundaryResult)
        assertTrue(cleanup > logicalStop)
    }

    @Test
    fun `p010 surface resolution is guarded on api 29 and 30`() {
        val resolver = source
            .substringAfter("private fun p010ReaderSurfaceOrNull(reader: ImageReader?)")
            .substringBefore("private fun updatePreview(")
        val sdkGuard = resolver.indexOf("Build.VERSION.SDK_INT < Build.VERSION_CODES.S")
        val formatRead = resolver.indexOf("ImageFormat.YCBCR_P010")
        val stop = source
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")

        assertTrue(sdkGuard >= 0)
        assertTrue(formatRead > sdkGuard)
        assertTrue(stop.contains("val p010Surface = p010ReaderSurfaceOrNull(reader)"))
        assertFalse(stop.contains("ImageFormat.YCBCR_P010"))
    }

    @Test
    fun `cadence safe session never reads p010 format before api 31`() {
        val resolver = source
            .substringAfter("private fun p010CadenceSafeSessionActive(")
            .substringBefore("private fun persistentNeoHdrCaptureMode(")
        val sdkGuard = resolver.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S")
        val formatRead = resolver.indexOf("ImageFormat.YCBCR_P010")

        assertTrue(sdkGuard >= 0)
        assertTrue(formatRead > sdkGuard)
    }
}
