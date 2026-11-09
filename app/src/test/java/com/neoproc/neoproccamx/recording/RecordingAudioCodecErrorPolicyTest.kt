package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioCodecErrorPolicyTest {

    @Test
    fun pendingDequeueConflictEndsDrainLoop() {
        assertTrue(
            RecordingAudioCodecErrorPolicy.shouldEndDrainLoop(
                "Invalid to call while another dequeue output request is pending"
            )
        )
    }

    @Test
    fun releasedCodecEndsDrainLoopDuringStopRace() {
        assertTrue(
            RecordingAudioCodecErrorPolicy.shouldEndDrainLoop("codec is released already")
        )
    }

    @Test
    fun stopStateConflictEndsDrainLoopDuringBoundedShutdown() {
        assertTrue(
            RecordingAudioCodecErrorPolicy.shouldEndDrainLoop(
                "Invalid to call during stop(); only valid in executing state"
            )
        )
    }

    @Test
    fun unrelatedCodecErrorUsesNormalRetryPath() {
        assertFalse(
            RecordingAudioCodecErrorPolicy.shouldEndDrainLoop("codec temporarily unavailable")
        )
    }
}
