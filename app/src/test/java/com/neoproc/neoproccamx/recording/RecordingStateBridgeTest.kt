package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateBridgeTest {

    @Test
    fun toRecordingState_mapsGpuLifecycleStates() {
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Idle) is RecordingState.Idle)
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Ready) is RecordingState.Idle)
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Starting) is RecordingState.Idle)
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Recording) is RecordingState.Recording)
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Paused) is RecordingState.Paused)
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Stopping) is RecordingState.Paused)
        assertTrue(RecordingStateBridge.toRecordingState(GpuRecordingState.Finalizing) is RecordingState.Idle)
    }

    @Test
    fun toRecordingState_preservesErrorMessage() {
        val result = RecordingStateBridge.toRecordingState(GpuRecordingState.Error("encoder failed"))

        assertTrue(result is RecordingState.Error)
        assertEquals("encoder failed", (result as RecordingState.Error).msg)
    }
}
