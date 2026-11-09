package com.neoproc.neoproccamx.recording

/**
 * Monotonic active-recording clock for an NPV10 session.
 *
 * Camera timestamps remain untouched in the lossless master. This clock is used only for the
 * user-visible duration and runtime utilization evidence, where an intentional pause must not be
 * counted as recorded programme time.
 */
internal data class Npv10RecordingDurationTimeline(
    val startedElapsedMs: Long = 0L,
    val pauseStartedElapsedMs: Long = 0L,
    val accumulatedPausedMs: Long = 0L
) {
    val isPaused: Boolean
        get() = pauseStartedElapsedMs > 0L
}

internal object Npv10RecordingDurationPolicy {
    fun start(nowElapsedMs: Long): Npv10RecordingDurationTimeline =
        Npv10RecordingDurationTimeline(startedElapsedMs = nowElapsedMs.coerceAtLeast(1L))

    fun pause(
        timeline: Npv10RecordingDurationTimeline,
        nowElapsedMs: Long
    ): Npv10RecordingDurationTimeline {
        if (timeline.startedElapsedMs <= 0L || timeline.isPaused) return timeline
        return timeline.copy(
            pauseStartedElapsedMs = nowElapsedMs.coerceAtLeast(timeline.startedElapsedMs)
        )
    }

    fun resume(
        timeline: Npv10RecordingDurationTimeline,
        nowElapsedMs: Long
    ): Npv10RecordingDurationTimeline {
        if (!timeline.isPaused) return timeline
        val pauseDurationMs =
            (nowElapsedMs - timeline.pauseStartedElapsedMs).coerceAtLeast(0L)
        return timeline.copy(
            pauseStartedElapsedMs = 0L,
            accumulatedPausedMs = saturatedAdd(
                timeline.accumulatedPausedMs,
                pauseDurationMs
            )
        )
    }

    fun activeDurationMs(
        timeline: Npv10RecordingDurationTimeline,
        nowElapsedMs: Long
    ): Long {
        if (timeline.startedElapsedMs <= 0L) return 0L
        val normalizedNowMs = nowElapsedMs.coerceAtLeast(timeline.startedElapsedMs)
        val openPauseMs = if (timeline.isPaused) {
            (normalizedNowMs - timeline.pauseStartedElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return (normalizedNowMs - timeline.startedElapsedMs -
            timeline.accumulatedPausedMs - openPauseMs).coerceAtLeast(0L)
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
