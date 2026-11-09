package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.RecordingPublicationIoGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class RecordingPublicationStartupRetryTest {
    @Test
    fun failedStartCannotRejectOrRetireTheRetryThatWinsTheCompletionGap() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = RecordingPublicationIoGate.process
        var starts = 0
        var transition: GpuRecordingState = GpuRecordingState.Idle
        val lifecycle = RecordingStopRestartLifecycle(
            scope = scope,
            claimStartAdmission = { true },
            beginClaimedStart = { starts += 1; transition = GpuRecordingState.Starting },
            hasActiveBackend = { false },
            requestBackendStopAdmission = { false },
            beginBackendStop = { error("No backend was started") },
            setTransitionState = { transition = it },
            replayDeferredLifecycleRefreshIfSafe = {},
            publishWarning = { error(it) },
            teardownStarted = { false }
        )
        try {
            assertEquals(0, gate.snapshot().captureOwners)
            lifecycle.requestStart()
            assertEquals(1, starts)
            assertEquals(1, gate.snapshot().captureOwners)
            lifecycle.completeStart(recordingSessionStarted = false) {
                // Deterministically reproduce a shutter retry after handoff admission reopens but
                // before the failed owner's completion/finally body returns.
                lifecycle.requestStart()
                assertEquals(2, starts)
                assertEquals(1, gate.snapshot().captureOwners)
            }
            assertTrue(lifecycle.isStarting)
            assertTrue(transition is GpuRecordingState.Starting)
            assertEquals(1, gate.snapshot().captureOwners)
            lifecycle.completeStart(recordingSessionStarted = false) {}
            assertFalse(lifecycle.isStarting)
            assertEquals(0, gate.snapshot().captureOwners)
        } finally {
            lifecycle.releasePublicationIsolationAfterRetirement()
            scope.cancel()
        }
    }
}
