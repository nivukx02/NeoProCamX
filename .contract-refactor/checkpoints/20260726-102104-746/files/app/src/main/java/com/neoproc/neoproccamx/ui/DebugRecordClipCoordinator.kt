package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState

/** Runs one debug record_clip timeline without allowing delayed callbacks to restart it. */
internal class DebugRecordClipCoordinator(
    private val nowMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val isCommandPending: () -> Boolean,
    private val isVideoMode: () -> Boolean,
    private val recordingState: () -> GpuRecordingState,
    private val isRecordingTimingReady: () -> Boolean,
    private val markTimingStart: () -> Unit,
    private val stopRecording: () -> Unit,
    private val logInfo: (String) -> Unit,
    private val logError: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val executionGate = DebugRecordClipExecutionGate()

    fun beginCommand(): Long = executionGate.beginCommand()

    fun cancelCurrent() = executionGate.cancelCurrent()

    fun tryClaimStart(token: Long): Boolean = executionGate.tryClaimStart(token)

    fun tryClaimObserver(token: Long): Boolean = executionGate.tryClaimObserver(token)

    fun scheduleStopAfterRecordingStarts(
        commandToken: Long,
        durationMs: Long,
        verifyDelayMs: Long,
        backendSettleGraceMs: Long,
        pollIntervalMs: Long
    ) {
        val deadlineMs = nowMs() + verifyDelayMs
        val stopDelayMs = durationMs + backendSettleGraceMs
        fun finishCommand(reason: String, error: Boolean) {
            if (!executionGate.completeCommand(commandToken)) return
            if (error) logError("debugAction recordClip terminal failure: $reason")
            else logInfo("debugAction recordClip terminal: $reason")
            onFinished()
        }
        val poll = object : Runnable {
            override fun run() {
                if (!isCommandPending() || !executionGate.isActive(commandToken)) return
                if (!isVideoMode()) {
                    finishCommand("record_clip_left_video_mode", error = true)
                    return
                }
                when (val state = recordingState()) {
                    is GpuRecordingState.Recording,
                    is GpuRecordingState.Paused -> {
                        if (!isRecordingTimingReady()) {
                            if (nowMs() < deadlineMs) postDelayed(this, pollIntervalMs)
                            else finishCommand(
                                "record_clip_waiting_for_validated_recording_feed",
                                error = true
                            )
                            return
                        }
                        if (!executionGate.tryClaimStop(commandToken)) return
                        logInfo(
                            "debugAction recordClip active validated; scheduling stop " +
                                "durationMs=$durationMs backendSettleGraceMs=$backendSettleGraceMs " +
                                "state=$state"
                        )
                        markTimingStart()
                        postDelayed(Runnable {
                            val stopState = recordingState()
                            if (stopState is GpuRecordingState.Recording ||
                                stopState is GpuRecordingState.Paused
                            ) {
                                logInfo("debugAction recordClip stop")
                                stopRecording()
                            } else {
                                logInfo("debugAction recordClip stop skipped: state=$stopState")
                            }
                            finishCommand("stop_requested", error = false)
                        }, stopDelayMs)
                    }
                    is GpuRecordingState.Error -> DebugRecordClipErrorPolicy.handle(
                        state.message,
                        onStrictAdmissionFailure = {
                            finishCommand("strict admission failure: ${state.message}", error = true)
                        },
                        onRetryable = {
                            finishCommand("recording error: ${state.message}", error = true)
                        }
                    )
                    else -> {
                        if (nowMs() < deadlineMs) postDelayed(this, pollIntervalMs)
                        else finishCommand("record_clip_start_failed", error = true)
                    }
                }
            }
        }
        postDelayed(poll, pollIntervalMs)
    }
}
