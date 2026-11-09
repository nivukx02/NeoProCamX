package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPauseTimelinePolicyTest {

    @Test
    fun pause_marksTimelinePausedWithoutChangingAccumulatedDuration() {
        val timeline = RecordingPauseTimelinePolicy.pause(
            timeline = RecordingPauseTimeline(),
            nowNs = 1_000_000_000L
        )

        assertTrue(timeline.paused)
        assertEquals(1_000_000_000L, timeline.pauseStartedNs)
        assertEquals(0L, timeline.accumulatedPausedUs)
    }

    @Test
    fun resume_addsPausedDurationOnce() {
        val paused = RecordingPauseTimelinePolicy.pause(
            timeline = RecordingPauseTimeline(),
            nowNs = 1_000_000_000L
        )

        val resumed = RecordingPauseTimelinePolicy.resume(
            timeline = paused,
            nowNs = 1_500_000_000L
        )

        assertFalse(resumed.paused)
        assertEquals(0L, resumed.pauseStartedNs)
        assertEquals(500_000L, resumed.accumulatedPausedUs)
    }

    @Test
    fun outputPtsSubtractsAccumulatedPauseAndStaysNonNegative() {
        val timeline = RecordingPauseTimeline(
            accumulatedPausedUs = 250_000L
        )

        assertEquals(
            750_000L,
            RecordingPauseTimelinePolicy.outputPresentationTimeUs(
                normalizedPresentationTimeUs = 1_000_000L,
                timeline = timeline
            )
        )
        assertEquals(
            0L,
            RecordingPauseTimelinePolicy.outputPresentationTimeUs(
                normalizedPresentationTimeUs = 120_000L,
                timeline = timeline
            )
        )
    }

    @Test
    fun shouldWriteSamplesOnlyWhileActivelyRecording() {
        assertTrue(RecordingPauseTimelinePolicy.shouldWriteSample(RecordingPauseTimeline(paused = false)))
        assertFalse(RecordingPauseTimelinePolicy.shouldWriteSample(RecordingPauseTimeline(paused = true)))
    }
}
