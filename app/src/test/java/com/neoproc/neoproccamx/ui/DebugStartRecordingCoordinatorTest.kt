package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

class DebugStartRecordingCoordinatorTest {
    @Test
    fun `successful start is consumed before a later user stop`() {
        val harness = Harness()
        val coordinator = harness.coordinator()
        val token = coordinator.beginAttempt()

        coordinator.scheduleVerification(token, verifyDelayMs = 2_000L, pollIntervalMs = 250L)
        harness.runNext()
        harness.state = GpuRecordingState.Recording
        harness.runNext()

        assertEquals(1, harness.startedCount)
        assertEquals(emptyList<String>(), harness.failures)
        assertFalse(harness.hasTasks())

        // The original bug checked only the state at the long timeout. A legitimate stop after a
        // successful start therefore re-armed the command. Once consumed, no later idle state can do so.
        harness.state = GpuRecordingState.Idle
        assertFalse(harness.hasTasks())
        assertEquals(1, harness.startedCount)
        assertEquals(emptyList<String>(), harness.failures)
    }

    @Test
    fun `stale verifier cannot complete or retry a newer attempt`() {
        val harness = Harness()
        val coordinator = harness.coordinator()
        val stale = coordinator.beginAttempt()
        coordinator.scheduleVerification(stale, verifyDelayMs = 2_000L, pollIntervalMs = 250L)
        val current = coordinator.beginAttempt()
        coordinator.scheduleVerification(current, verifyDelayMs = 2_000L, pollIntervalMs = 250L)
        harness.state = GpuRecordingState.Recording

        harness.runNext()
        assertEquals(0, harness.startedCount)
        harness.runNext()

        assertEquals(1, harness.startedCount)
        assertEquals(emptyList<String>(), harness.failures)
    }

    @Test
    fun `never-active start retries once at the deadline`() {
        val harness = Harness()
        val coordinator = harness.coordinator()
        val token = coordinator.beginAttempt()

        coordinator.scheduleVerification(token, verifyDelayMs = 500L, pollIntervalMs = 250L)
        harness.runNext()
        harness.runNext()

        assertEquals(0, harness.startedCount)
        assertEquals(listOf("start_recording_not_active"), harness.failures)
        assertFalse(harness.hasTasks())
    }

    @Test
    fun `main activity delegates start verification instead of delayed state-only restart`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val startBlock = source
            .substringAfter("DEBUG_ACTION_START_RECORDING ->")
            .substringBefore("DEBUG_ACTION_STOP_RECORDING ->")

        assertTrue(startBlock.contains("debugStartRecordingCoordinator.beginAttempt()"))
        assertTrue(startBlock.contains("debugStartRecordingCoordinator.scheduleVerification("))
        assertTrue(startBlock.contains("finishPendingDebugCommandAndContinue()"))
        assertFalse(startBlock.contains("vm.gpuRecordState.value !is GpuRecordingState.Recording"))
    }

    private class Harness {
        data class Task(val runnable: Runnable, val delayMs: Long)

        var nowMs = 0L
        var pending = true
        var videoMode = true
        var state: GpuRecordingState = GpuRecordingState.Starting
        var startedCount = 0
        val failures = mutableListOf<String>()
        private val tasks = ArrayDeque<Task>()

        fun coordinator(): DebugStartRecordingCoordinator =
            DebugStartRecordingCoordinator(
                nowMs = { nowMs },
                postDelayed = { runnable, delayMs -> tasks.addLast(Task(runnable, delayMs)) },
                isCommandPending = { pending },
                isVideoMode = { videoMode },
                recordingState = { state },
                logInfo = {},
                logError = {},
                onStarted = {
                    startedCount += 1
                    pending = false
                },
                onRetryableFailure = failures::add
            )

        fun runNext() {
            val task = tasks.removeFirst()
            nowMs += task.delayMs
            task.runnable.run()
        }

        fun hasTasks(): Boolean = tasks.isNotEmpty()
    }
}
