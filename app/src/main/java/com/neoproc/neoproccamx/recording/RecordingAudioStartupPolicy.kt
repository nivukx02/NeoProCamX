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

    /**
     * A requested audio track is part of the recording contract, not an optional startup aid.
     * Stopping the session or reaching the codec-format deadline must never silently turn an
     * audio recording into a video-only file. The caller may fail the transaction with a useful
     * error after its bounded wait, but it may start the container only when every requested track
     * has actually published its format.
     */
    fun requiredTracksReady(
        videoTrackIndex: Int,
        audioEnabled: Boolean,
        audioTrackIndex: Int
    ): Boolean =
        videoTrackIndex >= 0 && (!audioEnabled || audioTrackIndex >= 0)

    fun primingPcmBytes(
        channelCount: Int = DEFAULT_AUDIO_CHANNELS
    ): Int = AAC_LC_FRAME_SAMPLES * channelCount * PCM_16BIT_BYTES_PER_SAMPLE
}
