package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartupFailureLifecycleContractTest {
    private val source =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val coordinator =
        File("src/main/java/com/neoproc/neoproccamx/ui/FailedRecordingStartRecoveryCoordinator.kt")
            .readText()
    private val stopRestartLifecycle =
        File("src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt")
            .readText()

    private val start = source
        .substringAfter("private fun startRecordingInternal()")
        .substringBefore("private fun clearStaleAutofocusRegionForRecordingStart()")

    private val cleanup = source
        .substringAfter("private suspend fun cleanupFailedRecordingStart(")
        .substringBefore("private suspend fun awaitNextVulkanImportStatsOrTimeout(")

    @Test
    fun `failure becomes terminal before any suspending or Camera2 recovery work`() {
        val terminalTransition = cleanup.indexOf("failedRecordingStartRecovery.terminalize(")
        val firstDrain = cleanup.indexOf(
            "sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)"
        )
        val previewDetach = cleanup.indexOf("updatePreviewViewAwait")
        val recoverySchedule = cleanup.indexOf("schedulePreviewRecovery(refreshSession)")

        assertTrue(terminalTransition >= 0)
        assertTrue(firstDrain > terminalTransition)
        assertTrue(previewDetach > terminalTransition)
        assertTrue(recoverySchedule > terminalTransition)
        assertFalse(cleanup.contains("gpuRecordState.value !is GpuRecordingState.Error"))
        assertFalse(cleanup.contains("runOnMain {"))
        assertTrue(coordinator.indexOf("actions.backendFailureMessage()") <
            coordinator.indexOf("actions.publishTransitionFailure(it)"))
    }

    @Test
    fun `failed start releases every encoder owner and preserves exact backend error`() {
        val surfaceDetach = cleanup.indexOf(
            "detachRecordingSurfaceBeforeEncoderStop("
        )
        val coordinatorRelease = cleanup.indexOf(
            "failedRecordingStartRecovery.releaseRecorder(terminalFailureMessage)"
        )
        val recoverySchedule = cleanup.indexOf("schedulePreviewRecovery(refreshSession)")

        assertTrue(surfaceDetach >= 0)
        assertTrue(source.contains("camera.setRecordSurface(null, refreshSession = false)"))
        assertTrue(coordinatorRelease > surfaceDetach)
        assertTrue(recoverySchedule > coordinatorRelease)
        assertTrue(coordinator.contains("actions.stopForegroundProtection()"))
        assertTrue(coordinator.contains("actions.reportRecorderFailure(terminalFailureMessage)"))
        assertTrue(coordinator.contains("actions.discardPreparedSession()"))
        assertTrue(coordinator.contains("actions.releasePersistentInputSurface"))
    }

    @Test
    fun `preview restoration is owned asynchronous work outside startup handoff`() {
        assertTrue(coordinator.contains("previewRecoveryJob?.cancel("))
        assertTrue(coordinator.contains("previewRecoveryGeneration.incrementAndGet()"))
        assertTrue(coordinator.contains("scope.launch(mainDispatcher, start = CoroutineStart.LAZY)"))
        assertTrue(coordinator.contains("fun ensureLatestRecovery()"))
        assertTrue(Regex("ensureLatestRecovery\\(\\)").findAll(coordinator).count() >= 9)
        assertTrue(coordinator.contains("if (refreshSession) {"))
        assertTrue(coordinator.contains("actions.refreshCameraSession()"))
        assertTrue(coordinator.contains("actions.restorePreviewBinding()"))
        assertTrue(coordinator.contains("actions.reconcileRawPreview()"))
        assertTrue(coordinator.contains("catch (cancelled: CancellationException)"))
        assertTrue(coordinator.contains("throw cancelled"))
        assertFalse(coordinator.contains("recordingStartupHandoff.completeStart"))
        assertTrue(source.contains("refreshCameraSession = { camera.refreshOutputsAndSession("))
        assertTrue(source.contains("refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)"))
    }

    @Test
    fun `suspending cleanup propagates cancellation instead of reporting it`() {
        val suspendingStep = coordinator
            .substringAfter("suspend fun runSuspendingStep(")
            .substringBefore("fun schedulePreviewRecovery(")
        val cancellationCatch = suspendingStep.indexOf("catch (cancelled: CancellationException)")
        val rethrow = suspendingStep.indexOf("throw cancelled", cancellationCatch)
        val failureCatch = suspendingStep.indexOf("catch (failure: Throwable)", rethrow)

        assertTrue(cancellationCatch >= 0)
        assertTrue(rethrow > cancellationCatch)
        assertTrue(failureCatch > rethrow)
    }

    @Test
    fun `backend state rather than masked aggregate owns start admission`() {
        val awaitState = source
            .substringAfter("private suspend fun awaitGpuRecordingState(")
            .substringBefore("private suspend fun awaitCameraRecordingState(")
        val directStarts = Regex("gpuRecording\\.start\\(\\)").findAll(start).count()
        val readinessCommits =
            Regex("gpuRecording\\.commitSurfaceEncoderReadiness\\(\\)").findAll(start).count()
        val backendRecordingTransitions = directStarts + readinessCommits

        assertTrue(awaitState.contains("gpuRecording.state.value"))
        assertFalse(awaitState.contains("gpuRecordState.value"))
        assertEquals(
            backendRecordingTransitions,
            Regex("if \\(!awaitGpuRecordingState\\(\\)\\)").findAll(start).count()
        )
        assertTrue(directStarts >= 4)
        assertEquals(1, readinessCommits)
        assertTrue(backendRecordingTransitions >= 5)
    }

    @Test
    fun `null prepare exceptions and cancellation all converge on deterministic cleanup`() {
        val prepareNull = start
            .substringAfter("if (encoderSurface == null && !byteBufferP010Input)")
            .substringBefore("syncReportedRecordingBitrate()")
        val cancellation = start
            .substringAfter("} catch (e: CancellationException) {")
            .substringBefore("} catch (e: Exception) {")
        val exception = start
            .substringAfter("} catch (e: Exception) {")
            .substringBefore("} finally {")
        val finalizer = start
            .substringAfter("} finally {")
            .substringBefore("recordingStopRestartLifecycle.registerStartupCancellationAction")

        assertTrue(prepareNull.contains("cleanupFailedRecordingStart("))
        assertTrue(cancellation.contains("withContext(NonCancellable)"))
        assertTrue(cancellation.contains("cleanupFailedRecordingStart("))
        assertTrue(cancellation.contains("reportError = false"))
        assertTrue(exception.contains("cleanupFailedRecordingStart("))
        assertTrue(finalizer.contains("recordingStopRestartLifecycle.completeStart("))
        assertTrue(stopRestartLifecycle.contains("setTransitionState(GpuRecordingState.Stopping)"))
        assertTrue(stopRestartLifecycle.contains("setTransitionState(GpuRecordingState.Idle)"))
    }
}
