package com.neoproc.neoproccamx.recording

internal object RecordingDurationPolicy {
    fun resolveDurationMs(
        recordingStartNs: Long,
        nowNs: Long,
        latestVideoPtsUs: Long,
        latestAudioPtsUs: Long
    ): Long {
        val wallClockMs = ((nowNs - recordingStartNs).coerceAtLeast(0L)) / 1_000_000L
        val mediaDurationMs = listOf(latestVideoPtsUs, latestAudioPtsUs)
            .filter { it >= 0L }
            .maxOrNull()
            ?.div(1_000L)
        return mediaDurationMs ?: wallClockMs
    }
}
