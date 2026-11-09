package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.camera.CameraBackgroundCloseDecision
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.recording.GpuRecordingState

/** Orders display/camera lifecycle effects without changing the recording contract. */
internal object CameraPreviewLifecycleDispatcher {
    /** Evaluate each owner from one snapshot, not repeated reads across a state transition. */
    fun recordingOwnsWindow(recording: GpuRecordingState, camera: CameraState): Boolean =
        recording is GpuRecordingState.Starting || recording is GpuRecordingState.Recording ||
            recording is GpuRecordingState.Paused || recording is GpuRecordingState.Stopping ||
            camera == CameraState.Recording

    /** Focus loss can precede onPause, or occur without it when SystemUI covers the window. */
    fun windowFocus(
        hasFocus: Boolean,
        recordingWindow: () -> Unit,
        refreshOrientation: () -> Unit
    ) {
        // Publish recording display ownership before queuing any orientation work.
        recordingWindow()
        if (hasFocus) refreshOrientation()
    }

    /** Only an active recording may park its display; focus return always clears occlusion. */
    fun recordingWindowFocus(
        hasFocus: Boolean,
        recordingActive: () -> Boolean,
        setOccluded: (Boolean) -> Unit
    ) {
        // No camera close/rebuild, profile mutation or recording stop belongs in a focus callback.
        setOccluded(!hasFocus && recordingActive())
    }

    fun background(
        decision: CameraBackgroundCloseDecision,
        deferClose: (Boolean) -> Unit,
        retainDisplayInputs: () -> Unit,
        stopRecording: () -> Unit,
        closeCamera: () -> Unit
    ) {
        deferClose(decision.deferCameraCloseUntilRecordingStops)
        if (decision.deferCameraCloseUntilRecordingStops && !decision.requestRecordingStop) {
            retainDisplayInputs()
        }
        if (decision.requestRecordingStop) stopRecording()
        if (decision.closeCameraImmediately) closeCamera()
    }

    fun <S : Any> bindReleasedReplacement(
        coordinator: PreviewSurfaceRetirementCoordinator<S>,
        ticket: PreviewSurfaceRetirementTicket,
        isValid: (S) -> Boolean,
        bind: (S) -> Unit,
        evidence: (Boolean) -> Unit = { valid ->
            if (valid) Log.i("CameraViewModel", "Camera2 released retired preview ownership; binding replacement surface")
            else Log.w("CameraViewModel", "Discarding invalid preview replacement after Camera2 retirement")
        }
    ) {
        val completion = coordinator.complete(ticket)
        if (!completion.accepted) return
        val replacement = completion.replacement ?: return
        val valid = runCatching { isValid(replacement) }.getOrDefault(false)
        evidence(valid)
        if (valid) bind(replacement)
    }
}
