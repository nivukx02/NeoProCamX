package com.neoproc.neoproccamx.recording

import android.media.AudioRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioStopPolicyTest {

    @Test
    fun shouldStopAudioCapture_stopsOnlyOnceWhileRecorderIsActive() {
        assertTrue(
            RecordingAudioStopPolicy.shouldStopAudioCapture(
                recordingState = AudioRecord.RECORDSTATE_RECORDING,
                stopAlreadyRequested = false
            )
        )
        assertFalse(
            RecordingAudioStopPolicy.shouldStopAudioCapture(
                recordingState = AudioRecord.RECORDSTATE_RECORDING,
                stopAlreadyRequested = true
            )
        )
    }

    @Test
    fun shouldStopAudioCapture_skipsWhenRecorderIsAlreadyStopped() {
        assertFalse(
            RecordingAudioStopPolicy.shouldStopAudioCapture(
                recordingState = AudioRecord.RECORDSTATE_STOPPED,
                stopAlreadyRequested = false
            )
        )
    }
}
