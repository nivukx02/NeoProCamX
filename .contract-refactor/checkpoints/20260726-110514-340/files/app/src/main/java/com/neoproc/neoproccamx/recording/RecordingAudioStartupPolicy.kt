package com.neoproc.neoproccamx.recording

internal object RecordingAudioStartupPolicy {
    private const val AUDIO_TRACK_GRACE_PERIOD_NS = 250_000_000L
    private const val AAC_LC_FRAME_SAMPLES = 1024
    private const val PCM_16BIT_BYTES_PER_SAMPLE = 2
    private const val DEFAULT_AUDIO_CHANNELS = 2

    fun audioTrackDeadlineNs(
        recordingStartNs: Long,
        audioEnabled: Boolean
    ): Long = if (audioEnabled) {
        recordingStartNs + AUDIO_TRACK_GRACE_PERIOD_NS
    } else {
        recordingStartNs
    }

    fun allowVideoOnlyMuxerStart(
        audioEnabled: Boolean,
        audioTrackIndex: Int,
        isRecording: Boolean,
        nowNs: Long,
        audioTrackDeadlineNs: Long
    ): Boolean =
        !audioEnabled ||
            audioTrackIndex >= 0 ||
            !isRecording ||
            nowNs >= audioTrackDeadlineNs

    fun primingPcmBytes(
        channelCount: Int = DEFAULT_AUDIO_CHANNELS
    ): Int = AAC_LC_FRAME_SAMPLES * channelCount * PCM_16BIT_BYTES_PER_SAMPLE
}
