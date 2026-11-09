package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTimelineTelemetryPolicyTest {

    @Test
    fun framePacingJitter_reportsDeviationFromExpectedFrameCadence() {
        val jitterMs = RecordingTimelineTelemetryPolicy.framePacingJitterMs(
            previousVideoPtsUs = 33_333L,
            currentVideoPtsUs = 266_666L,
            frameRate = 30f
        )

        assertEquals(200L, jitterMs)
    }

    @Test
    fun avSyncDrift_usesLatestAudioAndVideoTimelines() {
        val driftMs = RecordingTimelineTelemetryPolicy.avSyncDriftMs(
            latestVideoPtsUs = 2_000_000L,
            latestAudioPtsUs = 1_760_000L
        )

        assertEquals(240L, driftMs)
    }

    @Test
    fun avSyncDrift_returnsNullUntilBothTracksHaveTimestamps() {
        val driftMs = RecordingTimelineTelemetryPolicy.avSyncDriftMs(
            latestVideoPtsUs = 2_000_000L,
            latestAudioPtsUs = -1L
        )

        assertNull(driftMs)
    }
}
