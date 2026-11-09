package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingDurationPolicyTest {

    @Test
    fun resolveDurationMs_usesWallClockUntilMediaTimelineStarts() {
        val durationMs = RecordingDurationPolicy.resolveDurationMs(
            recordingStartNs = 1_000_000_000L,
            nowNs = 1_350_000_000L,
            latestVideoPtsUs = -1L,
            latestAudioPtsUs = -1L
        )

        assertEquals(350L, durationMs)
    }

    @Test
    fun resolveDurationMs_prefersLatestMediaTimelineOnceSamplesArrive() {
        val durationMs = RecordingDurationPolicy.resolveDurationMs(
            recordingStartNs = 1_000_000_000L,
            nowNs = 2_500_000_000L,
            latestVideoPtsUs = 920_000L,
            latestAudioPtsUs = 880_000L
        )

        assertEquals(920L, durationMs)
    }

    @Test
    fun resolveDurationMs_usesLatestAudioWhenItLeadsVideo() {
        val durationMs = RecordingDurationPolicy.resolveDurationMs(
            recordingStartNs = 1_000_000_000L,
            nowNs = 2_500_000_000L,
            latestVideoPtsUs = 780_000L,
            latestAudioPtsUs = 840_000L
        )

        assertEquals(840L, durationMs)
    }
}
