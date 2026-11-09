package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawPreviewPresentationCoordinatorConcurrencyContractTest {
    private val source by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt").readText()
    }
    private val cameraViewModel by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val readinessLifecycle by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/NdrRenderProfileLifecycleCoordinator.kt")
            .readText()
    }

    @Test
    fun `epoch publication and Camera2 session callbacks share one serialized owner`() {
        assertTrue(source.contains("private val transitionLock = Any()"))
        assertTrue(function("fun begin(reason:").contains("synchronized(transitionLock)"))
        assertTrue(function("fun beginIfActive(reason:").contains("synchronized(transitionLock)"))
        assertTrue(function("fun onSessionGenerationAdvanced(").contains("synchronized(transitionLock)"))
        assertTrue(function("fun onSessionGenerationConfigured(").contains("synchronized(transitionLock)"))
    }

    @Test
    fun `stale configured session cannot replace the valid commit request`() {
        val schedule = function("private fun scheduleCommitLocked(")
        val generationGate = schedule.indexOf("sessionGeneration != latestSessionGeneration")
        val cameraGate = schedule.indexOf("sessionGeneration != camera.sessionGeneration.get()")
        val cancellation = schedule.indexOf("invalidatePendingCommitLocked()")

        assertTrue(generationGate >= 0)
        assertTrue(cameraGate > generationGate)
        assertTrue(cancellation > cameraGate)
    }

    @Test
    fun `render preparation never holds the epoch transition monitor`() {
        val schedule = function("private fun scheduleCommitLocked(")
        val firstValidation = schedule.indexOf("val prepareRequired = synchronized(transitionLock)")
        val prepare = schedule.indexOf("ndrPresenter.prepare(ndrPeakPlan())")
        val finalCommit = schedule.indexOf("synchronized(transitionLock)", prepare + 1)

        assertTrue(firstValidation >= 0)
        assertTrue(prepare > firstValidation)
        assertTrue(finalCommit > prepare)
    }

    @Test
    fun `temporarily unready ndr compositor is retained quietly and replayed by readiness edge`() {
        val schedule = function("private fun scheduleCommitLocked(")
        val compositorReadiness = function("fun onNdrCompositorReady()")
        val profileReadiness = function("fun onNdrRenderProfileReady(")
        val replay = function("private fun replayDeferredNdrRouteCommitLocked(")

        assertTrue(schedule.contains("SensorLinearNdrPreviewPreparation.DEFERRED_UNREADY"))
        assertTrue(schedule.contains("ndrRouteReadinessReplay.retainLatest(DeferredNdrRouteCommit("))
        assertFalse(schedule.contains("Strict RAW-derived visible NDR route was not prepared"))
        assertTrue(compositorReadiness.contains("replayDeferredNdrRouteCommitLocked"))
        assertTrue(profileReadiness.contains("replayDeferredNdrRouteCommitLocked"))
        assertTrue(replay.contains("ndrRouteReadinessReplay.takeForReadinessEdge() ?: return"))
        assertTrue(replay.contains("scheduleCommitLocked("))
        assertTrue(cameraViewModel.contains("rawPreviewPresentationCoordinator.onNdrCompositorReady()"))
        assertTrue(cameraViewModel.contains("NdrRenderProfileLifecycleCoordinator.observeReadyGenerations("))
        assertTrue(readinessLifecycle.contains("readyGenerations.collect"))
        assertTrue(readinessLifecycle.contains("onReady(readyGeneration)"))
    }

    @Test
    fun `readiness published immediately before retention cannot strand the route`() {
        val schedule = function("private fun scheduleCommitLocked(")
        val retain = schedule.indexOf(
            "ndrRouteReadinessReplay.retainLatest(DeferredNdrRouteCommit("
        )
        val stickyRecheck = schedule.indexOf(
            "currentNdrPreparationDependenciesReady()",
            retain
        )
        val retentionMonitorEnd = schedule.indexOf(
            "if (retainedForReadinessReplay &&",
            retain
        )
        val replay = schedule.indexOf(
            "post-retention-readiness-recheck",
            stickyRecheck
        )

        assertTrue(retain >= 0)
        assertTrue(retentionMonitorEnd > retain)
        assertTrue(stickyRecheck > retentionMonitorEnd)
        assertTrue(replay > stickyRecheck)
        assertTrue(function("private fun currentNdrPreparationDependenciesReady(")
            .contains("vulkanRuntime.isCurrentNdrRenderProfilePacketReady()"))
    }

    @Test
    fun `ready native preparation rejection remains a terminal fail-closed error`() {
        val schedule = function("private fun scheduleCommitLocked(")

        assertTrue(schedule.contains("SensorLinearNdrPreviewPreparation.FAILED"))
        assertTrue(schedule.contains("rejected ready native preparation"))
        assertTrue(schedule.contains("outgoing frame retained"))
    }

    @Test
    fun `generation replacement and teardown cancel a deferred readiness replay`() {
        val invalidation = function("private fun invalidatePendingCommitLocked()")
        val teardown = function("fun retireCallbacksForTeardown()")

        assertTrue(invalidation.contains("commitRequestId ="))
        assertTrue(invalidation.contains("ndrRouteReadinessReplay.invalidate()"))
        assertTrue(teardown.contains("closed = true"))
        assertTrue(teardown.contains("invalidatePendingCommitLocked()"))
        assertTrue(teardown.contains("ndrRouteReadinessReplay.close()"))
    }

    @Test
    fun `callback install restore and close share lifecycle owner`() {
        val install = function("fun installCallbacks()")
        val restore = function("fun restoreCallbacks()")
        val close = function("fun retireCallbacksForTeardown()")
        val installLocked = function("private fun installCallbacksLocked()")

        assertTrue(install.contains("synchronized(transitionLock)"))
        assertTrue(install.contains("closed"))
        assertTrue(restore.contains("synchronized(transitionLock)"))
        assertTrue(close.contains("synchronized(transitionLock)"))
        assertTrue(close.contains("restoreCallbacksLocked()"))
        assertTrue(close.contains("beginPreviewPresentationEpoch"))
        assertTrue(close.contains("runtimeToRetire.releaseSensorLinearNdrPreviewSurface()"))
        assertTrue(installLocked.indexOf("configureProcessors()") < installLocked.indexOf("camera.onRawReady"))
    }

    @Test
    fun `recording callbacks do not retain preview coordinator during native retirement`() {
        val configure = function("private fun configureProcessors()")
        val recording = configure.substringAfter("val recordingRuntime = vulkanRuntime")

        assertTrue(recording.contains("recordingRuntime.submitSensorLinearNdrRecordingImage("))
        assertTrue(recording.contains("recordingRuntime.pollSensorLinearNdrRecordingCompletion()"))
        assertFalse(recording.contains("vulkanRuntime.submitSensorLinearNdrRecordingImage("))
        assertFalse(recording.contains("vulkanRuntime.pollSensorLinearNdrRecordingCompletion()"))
    }

    @Test
    fun `idle RAW native completion uses detached immutable presentation sink`() {
        val configure = function("private fun configureProcessors()")
        val sink = source.substringAfter("private class CompletedRawFramePresentationSink(")

        assertTrue(configure.contains("val completedFrameSink = CompletedRawFramePresentationSink("))
        assertTrue(configure.contains("completedFrameSink.present(completed, submissionIdentity)"))
        assertFalse(configure.contains("presentCompletedRawFrame(completed)"))
        assertTrue(sink.contains("private val sensorLinearPath: Boolean"))
        assertTrue(sink.contains("presentIdleRawPreviewCompletionIfCurrent(submissionIdentity)"))
        assertFalse(sink.contains("RawPreviewPresentationCoordinator"))
    }

    @Test
    fun `Camera2 result forwarding never waits on preview transition monitor`() {
        val callback = function("fun onCaptureResult(")
        val closedField = source.substringBefore("var callbacksActive:")

        assertTrue(closedField.contains("@Volatile\n    private var closed"))
        assertFalse(callback.contains("synchronized(transitionLock)"))
        val admissionGuard = callback.indexOf("if (closed || !callbacksActive ||")
        assertTrue(admissionGuard >= 0)
        assertTrue(callback.contains("!isCameraSessionGenerationCurrent(sessionGeneration)"))
        assertTrue(
            callback.indexOf("rawVideoManager.onCaptureResult(result, sessionGeneration)") >
                admissionGuard
        )
    }

    @Test
    fun `visible consumer commits before producer admission and rejection disarms route`() {
        val schedule = function("private fun scheduleCommitLocked(")
        val compositor = schedule.indexOf("commitProjectOwnedNdrPresentationEpoch")
        val manager = schedule.indexOf("rawVideoManager.commitPreviewPresentationEpoch")
        val rejection = schedule.indexOf("if (compositorReady && !managerReady)")
        val disarm = schedule.indexOf("beginLocked(\"manager-commit-rejected:", rejection)

        assertTrue(compositor >= 0)
        assertTrue(manager > compositor)
        assertTrue(rejection > manager)
        assertTrue(disarm > rejection)
    }

    @Test
    fun `epoch observer runs after epoch and compositor publication`() {
        val begin = function("private fun beginLocked(")
        val epochPublication = begin.indexOf("epoch = nextEpoch")
        val hdrPublication = begin.indexOf("ndrCompositor.beginProjectOwnedNdrPresentationEpoch(nextEpoch)")
        val sdrPublication = begin.indexOf("compositor.beginDebayerPresentationEpoch(nextEpoch)")
        val observer = begin.indexOf("onEpochBegan(nextEpoch, reason)")

        assertTrue(epochPublication >= 0)
        assertTrue(hdrPublication > epochPublication)
        assertTrue(sdrPublication > epochPublication)
        assertTrue(observer > hdrPublication)
        assertTrue(observer > sdrPublication)
    }

    private fun function(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function signature: $signature" }
        val next = source.indexOf("\n    fun ", start + signature.length)
        val nextPrivate = source.indexOf("\n    private fun ", start + signature.length)
        val end = listOf(next, nextPrivate).filter { it >= 0 }.minOrNull() ?: source.length
        return source.substring(start, end)
    }
}
