package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraScreenAwakePolicyTest {
    @Test
    fun `foreground preview remains awake while recorder is idle or failed`() {
        assertTrue(
            CameraScreenAwakePolicy.shouldKeepScreenOn(
                foregroundPreviewActive = true,
                recordingState = GpuRecordingState.Idle
            )
        )
        assertTrue(
            CameraScreenAwakePolicy.shouldKeepScreenOn(
                foregroundPreviewActive = true,
                recordingState = GpuRecordingState.Error("test")
            )
        )
    }

    @Test
    fun `recording lifecycle retains screen ownership independently of preview`() {
        val activeStates = listOf(
            GpuRecordingState.Starting,
            GpuRecordingState.Recording,
            GpuRecordingState.Paused,
            GpuRecordingState.Stopping,
            GpuRecordingState.Finalizing
        )
        activeStates.forEach { state ->
            assertTrue(
                CameraScreenAwakePolicy.shouldKeepScreenOn(
                    foregroundPreviewActive = false,
                    recordingState = state
                )
            )
        }
    }

    @Test
    fun `background idle window releases screen ownership`() {
        assertFalse(
            CameraScreenAwakePolicy.shouldKeepScreenOn(
                foregroundPreviewActive = false,
                recordingState = GpuRecordingState.Idle
            )
        )
    }
}
