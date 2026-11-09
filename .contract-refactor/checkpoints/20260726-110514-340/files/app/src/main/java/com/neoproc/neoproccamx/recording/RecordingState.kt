package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

sealed class RecordingState {
    object Idle : RecordingState()
    object Ready : RecordingState()
    object Recording : RecordingState()
    object Paused : RecordingState()
    data class Error(val msg: String) : RecordingState()

    fun isActive(): Boolean = this is Recording || this is Paused

    fun strictHighEndLocked(): Boolean =
        HighEndQualityPolicy.enabled &&
            HighEndQualityPolicy.strictHighEndOnly &&
            this is Recording
}
