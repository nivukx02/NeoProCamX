package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTogglePolicyTest {

    @Test
    fun nextAction_returnsStart_forIdleState() {
        assertEquals(
            RecordingTogglePolicy.Action.START,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Idle)
        )
    }

    @Test
    fun nextAction_returnsStop_forRecordingState() {
        assertEquals(
            RecordingTogglePolicy.Action.STOP,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Recording)
        )
    }

    @Test
    fun nextAction_returnsStop_forStartingStateSoSecondPressCancels() {
        assertEquals(
            RecordingTogglePolicy.Action.STOP,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Starting)
        )
    }

    @Test
    fun nextAction_returnsStop_forPausedState() {
        assertEquals(
            RecordingTogglePolicy.Action.STOP,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Paused)
        )
    }

    @Test
    fun nextAction_returnsIgnore_forStoppingState() {
        assertEquals(
            RecordingTogglePolicy.Action.IGNORE,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Stopping)
        )
    }

    @Test
    fun nextAction_returnsIgnore_whileLosslessMasterFinalizes() {
        assertEquals(
            RecordingTogglePolicy.Action.IGNORE,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Finalizing)
        )
    }

    @Test
    fun nextAction_returnsStart_forPreparedState() {
        assertEquals(
            RecordingTogglePolicy.Action.START,
            RecordingTogglePolicy.nextAction(GpuRecordingState.Ready)
        )
    }
}
