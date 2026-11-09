package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState

/**
 * Verifies one debug `start_recording` attempt and consumes it as soon as recording is observed.
 *
 * The verifier must remember that a start succeeded. Looking only at the state after a long timeout
 * is unsafe: the user can legitimately stop the recording before that timeout, which must not turn
 * the already-successful one-shot command back into a second start.
 */
internal class DebugStartRecordingCoordinator(
    private val nowMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val isCommandPending: () -> Boolean,
    private val isVideoMode: () -> Boolean,
    private val recordingState: () -> GpuRecordingState,
    private val logInfo: (String) -> Unit,
    private val logError: (String) -> Unit,
    private val onStarted: () -> Unit,
    private val onRetryableFailure: (String) -> Unit
) {
    private var generation = 0L
    private var activeToken: Long? = null

    fun beginAttempt(): Long {
        generation += 1L
        activeToken = generation
        return generation
    }

    fun cancelCurrent() {
        activeToken = null
    }

    fun scheduleVerification(
        attemptToken: Long,
        verifyDelayMs: Long,
        pollIntervalMs: Long
    ) {
        val deadlineMs = nowMs() + verifyDelayMs
        val poll = object : Runnable {
            override fun run() {
                if (!isActive(attemptToken)) return
                if (!isCommandPending()) {
                    cancel(attemptToken)
                    return
                }
                if (!isVideoMode()) {
                    fail(attemptToken, "start_recording_left_video_mode")
                    return
                }
                when (val state = recordingState()) {
                    is GpuRecordingState.Recording,
                    is GpuRecordingState.Paused -> succeed(attemptToken, state)
                    is GpuRecordingState.Error ->
                        fail(attemptToken, "start_recording_error: ${state.message}")
                    else -> {
                        if (nowMs() < deadlineMs) {
                            postDelayed(this, pollIntervalMs)
                        } else {
                            fail(attemptToken, "start_recording_not_active")
                        }
                    }
                }
            }
        }
        postDelayed(poll, pollIntervalMs)
    }

    private fun isActive(token: Long): Boolean = activeToken == token

    private fun cancel(token: Long): Boolean {
        if (!isActive(token)) return false
        activeToken = null
        return true
    }

    private fun succeed(token: Long, state: GpuRecordingState) {
        if (!cancel(token)) return
        logInfo("debugAction startRecording observed active; command consumed state=$state")
        onStarted()
    }

    private fun fail(token: Long, reason: String) {
        if (!cancel(token)) return
        logError("debugAction startRecording verification failed: $reason")
        onRetryableFailure(reason)
    }
}
