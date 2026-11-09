package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRestartLifecycleIntegrationContractTest {
    private val source =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val lifecycle =
        File("src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt").readText()

    @Test
    fun `start and toggle retain restart behind authoritative stop generation`() {
        val start = lifecycle
            .substringAfter("fun requestStart() {")
            .substringBefore("fun registerStartupCancellationAction")
        val toggle = source
            .substringAfter("fun toggleRecording() {")
            .substringBefore("private fun toggleRecordingInternal()")

        assertTrue(source.contains("fun startRecording() = recordingStopRestartLifecycle.requestStart()"))
        assertTrue(start.contains("startupHandoff.tryBeginStart("))
        assertTrue(start.contains("retainRestartDuringPendingStop"))
        assertTrue(start.contains("restartAfterStop.requestStart()"))
        assertTrue(
            start.indexOf("startupHandoff.tryBeginStart") <
                start.indexOf("restartAfterStop.requestStart()")
        )
        assertTrue(start.contains("claimStartAdmission()"))
        assertTrue(start.contains("beginStartWithPublicationIsolation()"))
        val isolatedStart = lifecycle
            .substringAfter("private fun beginStartWithPublicationIsolation() {")
            .substringBefore("private fun releasePublicationCaptureLease()")
        val storageOwner = isolatedStart.indexOf("RecordingPublicationIoGate.process.acquireCapture()")
        val admittedBackendStart = isolatedStart.indexOf("beginClaimedStart()")
        assertTrue(storageOwner >= 0)
        assertTrue(admittedBackendStart > storageOwner)
        assertTrue(isolatedStart.contains("publicationCaptureLease.compareAndSet(null, lease)"))
        assertTrue(
            toggle.indexOf("if (recordingStopRestartLifecycle.isStopInProgress)") <
                toggle.indexOf("if (recordingStopRestartLifecycle.isStarting)")
        )
        assertTrue(toggle.contains("startRecording()"))
    }

    @Test
    fun `stop lease exists before startup cancellation and exact owner releases it`() {
        val stop = lifecycle
            .substringAfter("fun requestStop() {")
            .substringBefore("fun releaseActiveStopOwnerWithoutBackend()")
        val owner = lifecycle
            .substringAfter("fun launchStopOwner(")
            .substringBefore("fun cancelForTeardown()")
        val releaseOwner = lifecycle
            .substringAfter("private fun releaseStopOwner(stopLease:")
            .substringBefore("private fun scheduleRetainedRestart(")

        val cancellation = stop.indexOf("startupHandoff.requestStopDuringStart")
        val generation = stop.indexOf("restartAfterStop.tryBeginStop", cancellation)
        val externalFlag = stop.indexOf("stopInProgress.compareAndSet(false, true)")
        val transfer = stop.indexOf("startupCancellationStopLease.compareAndSet(null, startupStopLease)")
        assertTrue(cancellation >= 0)
        assertTrue(generation > cancellation)
        assertTrue(externalFlag > generation)
        assertTrue(transfer > externalFlag)

        val capturedLease = owner.indexOf("restartAfterStop.activeStopLeaseForOwner()")
        val undispatched = owner.indexOf("CoroutineStart.UNDISPATCHED")
        val delegatedRelease = owner.indexOf("releaseStopOwner(stopLease)")
        val release = releaseOwner.indexOf("restartAfterStop.releaseStop(stopLease)")
        val flagReleased = releaseOwner.indexOf("stopInProgress.set(false)", release)
        val idle = releaseOwner.indexOf("setTransitionState(GpuRecordingState.Idle)", release)
        val replay = releaseOwner.indexOf("scheduleRetainedRestart(release.permit)", idle)
        assertTrue(capturedLease >= 0)
        assertTrue(undispatched > capturedLease)
        assertTrue(delegatedRelease > undispatched)
        assertTrue(release >= 0)
        assertTrue(flagReleased > release)
        assertTrue(idle > flagReleased)
        assertTrue(replay > idle)
    }

    @Test
    fun `npv10 and aborted startup stay finalizing until exact owner release`() {
        val npv10Stop = source
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")
        val startupAbort = source
            .substringAfter("private fun abortNpv10Startup(")
            .substringBefore("private suspend fun awaitNpv10P010SessionReady(")

        val beginFinalization = npv10Stop.indexOf("npv10Recording.beginFinalization()")
        val transition = npv10Stop.indexOf(
            "recordingTransitionState.value = GpuRecordingState.Finalizing",
            beginFinalization
        )
        val durableStop = npv10Stop.indexOf("npv10Recording.stop()", transition)
        assertTrue(beginFinalization >= 0)
        assertTrue(transition > beginFinalization)
        assertTrue(durableStop > transition)
        assertFalse(npv10Stop.contains("recordingTransitionState.value = GpuRecordingState.Idle"))
        assertTrue(startupAbort.contains("recordingTransitionState.value = GpuRecordingState.Finalizing"))
        assertFalse(startupAbort.contains("recordingTransitionState.value = GpuRecordingState.Idle"))
    }
}
