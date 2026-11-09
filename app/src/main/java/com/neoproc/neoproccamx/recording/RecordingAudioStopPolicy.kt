package com.neoproc.neoproccamx.recording

import android.media.AudioRecord

internal object RecordingAudioStopPolicy {
    fun shouldStopAudioCapture(
        recordingState: Int,
        stopAlreadyRequested: Boolean
    ): Boolean {
        return !stopAlreadyRequested && recordingState == AudioRecord.RECORDSTATE_RECORDING
    }
}
