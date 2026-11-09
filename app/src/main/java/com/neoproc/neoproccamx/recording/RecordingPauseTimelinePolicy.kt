package com.neoproc.neoproccamx.recording

internal data class RecordingPauseTimeline(
    val paused: Boolean = false,
    val pauseStartedNs: Long = 0L,
    val accumulatedPausedUs: Long = 0L
)

internal object RecordingPauseTimelinePolicy {
    fun pause(
        timeline: RecordingPauseTimeline,
        nowNs: Long
    ): RecordingPauseTimeline =
        if (timeline.paused) {
            timeline
        } else {
            timeline.copy(paused = true, pauseStartedNs = nowNs.coerceAtLeast(0L))
        }

    fun resume(
        timeline: RecordingPauseTimeline,
        nowNs: Long
    ): RecordingPauseTimeline {
        if (!timeline.paused) return timeline
        val pausedDurationUs =
            ((nowNs - timeline.pauseStartedNs).coerceAtLeast(0L) / 1_000L)
        return timeline.copy(
            paused = false,
            pauseStartedNs = 0L,
            accumulatedPausedUs = timeline.accumulatedPausedUs + pausedDurationUs
        )
    }

    fun outputPresentationTimeUs(
        normalizedPresentationTimeUs: Long,
        timeline: RecordingPauseTimeline
    ): Long =
        (normalizedPresentationTimeUs - timeline.accumulatedPausedUs).coerceAtLeast(0L)

    fun shouldWriteSample(timeline: RecordingPauseTimeline): Boolean = !timeline.paused
}
