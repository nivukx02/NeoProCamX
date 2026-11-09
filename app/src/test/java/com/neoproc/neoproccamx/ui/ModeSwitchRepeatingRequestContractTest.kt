package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModeSwitchRepeatingRequestContractTest {
    private val controllerSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }
    private val viewModelSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val watchdogSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/PreviewHealthMonitor.kt").readText()
    }

    @Test
    fun `stable raw boundary retargets the repeating request without recreating the session`() {
        assertTrue(
            viewModelSource.contains(
                "if (decision.shouldRetargetRepeatingRequest) camera.refreshRepeatingRequest()"
            )
        )
        assertTrue(controllerSource.contains("pendingRepeatingRequestRefresh.compareAndSet(false, true)"))
        assertTrue(controllerSource.contains("startPreview(sessionGeneration.get(), session)"))
    }

    @Test
    fun `queued parameter update cannot reinstall a stale target builder`() {
        val updateBlock = controllerSource
            .substringAfter("private fun updatePreview(immediate: Boolean = false)")
            .substringBefore("private val captureCallback")

        assertTrue(updateBlock.contains("previewBuilder !== builder"))
    }

    @Test
    fun `mode batch and session dispatch own repeating target publication`() {
        val refreshBlock = controllerSource
            .substringAfter("fun refreshOutputsAndSession(")
            .substringBefore("private fun hdrSurfaceRoleFor")
        val batchBlock = controllerSource
            .substringAfter("fun endBatchUpdate()")
            .substringBefore("fun hasCameraFacing")
        val repeatingBlock = controllerSource
            .substringAfter("private fun restartRepeatingRequestForRecordTargetChange(")
            .substringBefore("override fun drainRawReaderSynchronously")

        assertTrue(refreshBlock.contains("queueBatchedSessionRefreshIfActive("))
        assertTrue(
            refreshBlock.indexOf("stateFlow.value is CameraState.Closing") <
                refreshBlock.indexOf("deferSessionRefreshBehindP010StopBoundary(")
        )
        assertTrue(
            refreshBlock.indexOf("val dispatchTicket = beginSessionRefreshDispatch(rejectedCompletion)") <
                refreshBlock.indexOf("dispatchSessionRefreshOnCameraThread(")
        )
        assertTrue(refreshBlock.contains("ticket = dispatchTicket"))
        assertTrue(batchBlock.contains("batchSessionRefreshHandoffPending = sessionRefreshQueued"))
        assertTrue(batchBlock.contains("shouldReplayAfterBatch("))
        assertTrue(repeatingBlock.contains("deferRepeatingRefreshToSessionTransaction()"))
        assertTrue(repeatingBlock.contains("publishRepeatingRequestIfSessionStable"))
    }

    @Test
    fun `refresh dispatch and lifecycle reset use exact epoch tickets`() {
        val dispatcherBlock = controllerSource
            .substringAfter("private fun dispatchSessionRefreshOnCameraThread(")
            .substringBefore("private fun deferRepeatingRefreshToSessionTransaction()")
        val clearBlock = controllerSource
            .substringAfter("override fun clearPendingInFlightRefresh()")
            .substringBefore("private fun queueTrailingSessionRefresh(")

        assertTrue(dispatcherBlock.contains("backgroundHandler?.post(cameraAction) == true"))
        assertTrue(dispatcherBlock.contains("completeSessionRefreshDispatch(ticket)"))
        assertTrue(dispatcherBlock.contains("sessionRefreshDispatchTracker.executeIfActive(ticket)"))
        assertTrue(dispatcherBlock.contains("Discarded stale Camera2 session-refresh dispatch"))
        assertTrue(clearBlock.contains("val invalidatedDispatchCallbacks = sessionRefreshDispatchTracker.invalidateLifecycle()"))
        assertTrue(clearBlock.contains("invalidatedDispatchCallbacks.forEach"))
        assertTrue(clearBlock.contains("refreshBatchDepth.set(0)"))
        assertTrue(clearBlock.contains("batchedSessionRefreshRequest = null"))
    }

    @Test
    fun `session creation never runs under the repeating publication lock inversion`() {
        val refreshBlock = controllerSource
            .substringAfter("fun refreshOutputsAndSession(")
            .substringBefore("private fun hdrSurfaceRoleFor")
        val preparationStart = refreshBlock.indexOf(
            "val sessionRefreshAdmitted = synchronized(cameraSubmissionLock)"
        )
        val unlockedCreateMarker = refreshBlock.indexOf(
            "// createSession can synchronously drain a trailing request"
        )

        assertTrue(preparationStart >= 0)
        assertTrue(unlockedCreateMarker > preparationStart)
        assertTrue(
            !refreshBlock.substring(preparationStart, unlockedCreateMarker).contains("createSession(")
        )
        assertTrue(refreshBlock.substring(unlockedCreateMarker).contains("createSession("))
    }

    @Test
    fun `fps and external surface mutations cannot bypass session transaction ownership`() {
        val fpsBlock = controllerSource
            .substringAfter("override fun setFPS(fps: Int): Int")
            .substringBefore("override fun triggerTapAF")
        val previewSetter = controllerSource
            .substringAfter("override fun setPreviewSurface(surface: Surface?)")
            .substringBefore("override fun getPreviewSurface")
        val recordSetter = controllerSource
            .substringAfter("fun setRecordSurface(surface: Surface?, refreshSession: Boolean)")
            .substringBefore("fun setPersistentPreviewOutputs")
        val persistentSetter = controllerSource
            .substringAfter("fun setPersistentPreviewOutputs(sdrSurface: Surface?, hdrSurface: Surface?): Boolean")
            .substringBefore("fun persistentPreviewOutputsAvailable")

        assertTrue(fpsBlock.contains("restartRepeatingRequestForRecordTargetChange()"))
        assertTrue(!fpsBlock.contains("runOnCameraThread { startPreview"))
        assertTrue(previewSetter.contains("replacePreviewSessionSurface(surface)"))
        assertTrue(recordSetter.contains("replaceRecordSessionSurface(surface)"))
        assertTrue(persistentSetter.contains("replacePersistentPreviewSessionSurfaces"))
    }

    @Test
    fun `session creation validates surface ownership immediately before submit`() {
        val createBlock = controllerSource
            .substringAfter("private fun createSession(")
            .substringBefore("private fun handleCreateSessionIllegalState(")

        assertTrue(createBlock.contains("captureSessionSurfaceSnapshot(includeHardwareImport)"))
        assertTrue(createBlock.contains("submitIfSnapshotIsCurrent"))
        assertTrue(createBlock.contains("evaluateSessionSurfaceSubmission("))
        assertTrue(createBlock.contains("dev.createCaptureSession(sessionConfiguration)"))
        assertTrue(createBlock.contains("retrySessionCreateAfterSurfaceRace("))
        assertTrue(createBlock.contains("settleFailedSessionCreateAttempt(generation)"))
    }

    @Test
    fun `processed source topology is primed before asynchronous prearm and camera open`() {
        val prearmBlock = viewModelSource
            .substringAfter("private fun requestRecordingSurfacePrearm(")
            .substringBefore("private fun requestNpv10CapturePrearm(")
        val openBlock = viewModelSource
            .substringAfter("fun openCamera()")
            .substringBefore("fun resumeCameraForForeground()")

        assertTrue(prearmBlock.contains("primeOffscreenHardwareImportSessionGraph(reason = \"mode-switch\")"))
        assertTrue(
            prearmBlock.indexOf("primeOffscreenHardwareImportSessionGraph(reason = \"mode-switch\")") <
                prearmBlock.indexOf("viewModelScope.launch(Dispatchers.IO)")
        )
        assertTrue(openBlock.contains("primeOffscreenHardwareImportSessionGraph(reason = \"camera-open\")"))
        assertTrue(
            openBlock.indexOf("primeOffscreenHardwareImportSessionGraph(reason = \"camera-open\")") <
                openBlock.indexOf("camera.openCamera(")
        )
    }

    @Test
    fun `offscreen prearm proves exact session membership before requesting another rebuild`() {
        val prearmBlock = viewModelSource
            .substringAfter("private fun prearmOffscreenHardwareImportSource(")
            .substringBefore("private fun prearmSensorLinearNdrSource(")
        val membershipIndex = prearmBlock.indexOf("camera.isHardwareImportP010SessionConfigured")
        val inFlightIndex = prearmBlock.indexOf("camera.sessionRefreshPending.get()")
        val refreshIndex = prearmBlock.indexOf("camera.refreshOutputsAndSession(")

        assertTrue(membershipIndex >= 0)
        assertTrue(inFlightIndex > membershipIndex)
        assertTrue(refreshIndex > inFlightIndex)
        assertTrue(prearmBlock.contains("no trailing refresh enqueued"))
    }

    @Test
    fun `preview recovery is single flight and deferred checks do not consume attempts`() {
        assertTrue(controllerSource.contains("isRecoveryInFlight = ::previewRecoveryInFlight"))
        assertTrue(controllerSource.contains("duplicate rebuild suppressed"))
        assertTrue(watchdogSource.contains("if (isRecoveryInFlight())"))
        assertTrue(
            watchdogSource.indexOf("if (isRecoveryInFlight())") <
                watchdogSource.indexOf("consecutiveRecoveries++")
        )
    }
}
