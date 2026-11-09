package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents foreground/onResume preference restoration from replacing a live recording request. */
class LifecyclePreferenceRefreshRecordingGateContractTest {
    private val source =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val lifecycle =
        File("src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt").readText()

    @Test
    fun `all recording transition states block lifecycle preference mutation`() {
        val blocker = source
            .substringAfter("private fun GpuRecordingState.blocksLifecyclePreferenceMutation()")
            .substringBefore("private fun deferLifecyclePreferenceRefresh(")

        listOf("Starting", "Recording", "Paused", "Stopping", "Finalizing")
            .forEach { state -> assertTrue(blocker.contains("GpuRecordingState.$state")) }
        assertTrue(blocker.contains("recordingStopRestartLifecycle.isStarting"))
        assertTrue(blocker.contains("recordingStopRestartLifecycle.isStopInProgress"))
        assertTrue(blocker.contains("cameraState.value == CameraState.Recording"))
        assertTrue(blocker.contains("npv10Recording.hasActiveSession()"))
    }

    @Test
    fun `preference refresh defers before launch and rechecks before commit`() {
        val refresh = source
            .substringAfter("fun refreshPreferences()")
            .substringBefore("private fun invalidatePendingPreferenceRestore()")
        val apply = source
            .substringAfter("private fun applyPreferences(")
            .substringBefore("private fun scheduleDeferredPreferenceRestore(")

        assertTrue(refresh.contains("lifecyclePreferenceMutationBlockedByRecording()"))
        assertTrue(refresh.contains("deferLifecyclePreferenceRefresh("))
        assertTrue(apply.contains("lifecyclePreferenceMutationBlockedByRecording()"))
        assertTrue(apply.contains("recording became active before restore commit"))
        assertTrue(apply.contains("recording won restore ownership before mutation"))
    }

    @Test
    fun `deferred refresh replays after failed startup and every stop owner`() {
        val startupFinally = lifecycle
            .substringAfter("fun completeStart(")
            .substringBefore("fun requestStop()")
        val stopOwner = lifecycle
            .substringAfter("private fun releaseStopOwner(stopLease:")
            .substringBefore("private fun scheduleRetainedRestart(")

        assertTrue(startupFinally.contains("!recordingSessionStarted && !hasActiveBackend()"))
        assertTrue(startupFinally.contains("replayDeferredLifecycleRefreshIfSafe()"))
        assertTrue(stopOwner.contains("stopInProgress.set(false)"))
        assertTrue(stopOwner.contains("setTransitionState(GpuRecordingState.Idle)"))
        assertTrue(stopOwner.contains("replayDeferredLifecycleRefreshIfSafe()"))
    }
}
