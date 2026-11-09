package com.neoproc.neoproccamx.recording

internal object RecordingAudioCodecErrorPolicy {
    private const val PENDING_DEQUEUE_MESSAGE = "another dequeue output request is pending"
    private const val CODEC_RELEASED_MESSAGE = "codec is released already"
    private const val STOP_STATE_MESSAGE = "during stop()"

    fun shouldEndDrainLoop(message: String?): Boolean =
        message?.let {
            it.contains(PENDING_DEQUEUE_MESSAGE, ignoreCase = true) ||
                it.contains(CODEC_RELEASED_MESSAGE, ignoreCase = true) ||
                it.contains(STOP_STATE_MESSAGE, ignoreCase = true)
        } == true
}
