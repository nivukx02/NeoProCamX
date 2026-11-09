package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.Npv10RecordingManager
import com.neoproc.neoproccamx.recording.RecordingManager
import com.neoproc.neoproccamx.recording.RecordingStopOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps recording failure recovery on the process-retained ViewModel scope, not an Activity.
 * Workers only publish a cause. These observers ask the existing serialized stop owner to drain
 * producers, detach camera/GPU surfaces, finalize or reject the output, and release protection.
 * No codec, native resource or Surface is destructively reclaimed on a callback/drain thread.
 */
internal class RecordingTerminalFailureCleanupObserver(
    private val scope: CoroutineScope,
    private val gpuRecording: RecordingManager,
    private val npv10Recording: Npv10RecordingManager,
    private val teardownStarted: () -> Boolean,
    private val setHardwareWarning: (String) -> Unit,
    private val requestStop: (RecordingStopOrigin, String) -> Unit
) {
    fun start() {
        startNpv10TerminalErrorCleanupObserver()
        startEncodedTerminalErrorCleanupObserver()
    }

    /** Preserves the existing Cinema writer-error route and its exact-boundary stop transaction. */
    private fun startNpv10TerminalErrorCleanupObserver() {
        scope.launch(Dispatchers.Main.immediate) {
            npv10Recording.state.collect { state ->
                if (teardownStarted() || state !is GpuRecordingState.Error ||
                    !npv10Recording.hasActiveSession()
                ) {
                    return@collect
                }
                Log.e(
                    "CameraViewModel",
                    "NPV10 terminal writer error; routing through serialized recording cleanup: ${state.message}"
                )
                setHardwareWarning("NPV10 recording stopped safely: ${state.message}")
                requestRecordingStop(
                    origin = RecordingStopOrigin.NPV10_WRITER_FAILURE,
                    detail = state.message
                )
            }
        }
    }

    private fun startEncodedTerminalErrorCleanupObserver() {
        scope.launch(Dispatchers.Main.immediate) {
            combine(gpuRecording.fatalPipelineFailures, gpuRecording.state) { failure, state ->
                failure to state
            }.collect { (failure, state) ->
                if (failure == null || teardownStarted() ||
                    (state !is GpuRecordingState.Recording && state !is GpuRecordingState.Paused)
                ) {
                    return@collect
                }
                if (!gpuRecording.claimFatalPipelineStop(failure)) return@collect
                Log.e(
                    "CameraViewModel",
                    "ENCODED_TERMINAL_FAILURE_STOP generation=${failure.generation} " +
                        "reason=${failure.message}"
                )
                setHardwareWarning("Recording stopped safely: ${failure.message}")
                requestRecordingStop(
                    origin = RecordingStopOrigin.PIPELINE_FAILURE,
                    detail = failure.message
                )
            }
        }
    }

    private fun requestRecordingStop(origin: RecordingStopOrigin, detail: String) =
        requestStop(origin, detail)
}
