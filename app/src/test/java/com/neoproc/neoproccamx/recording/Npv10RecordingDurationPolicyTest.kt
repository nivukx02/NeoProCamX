package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10RecordingDurationPolicyTest {
    @Test
    fun `active duration freezes throughout pause and excludes pause after resume`() {
        val started = Npv10RecordingDurationPolicy.start(nowElapsedMs = 1_000L)
        val paused = Npv10RecordingDurationPolicy.pause(started, nowElapsedMs = 2_250L)

        assertTrue(paused.isPaused)
        assertEquals(1_250L, Npv10RecordingDurationPolicy.activeDurationMs(paused, 9_000L))

        val resumed = Npv10RecordingDurationPolicy.resume(paused, nowElapsedMs = 9_000L)
        assertFalse(resumed.isPaused)
        assertEquals(6_750L, resumed.accumulatedPausedMs)
        assertEquals(1_250L, Npv10RecordingDurationPolicy.activeDurationMs(resumed, 9_000L))
        assertEquals(2_000L, Npv10RecordingDurationPolicy.activeDurationMs(resumed, 9_750L))
    }

    @Test
    fun `duplicate pause and resume calls are idempotent`() {
        val started = Npv10RecordingDurationPolicy.start(nowElapsedMs = 100L)
        val paused = Npv10RecordingDurationPolicy.pause(started, nowElapsedMs = 200L)

        assertEquals(paused, Npv10RecordingDurationPolicy.pause(paused, nowElapsedMs = 300L))

        val resumed = Npv10RecordingDurationPolicy.resume(paused, nowElapsedMs = 500L)
        assertEquals(resumed, Npv10RecordingDurationPolicy.resume(resumed, nowElapsedMs = 900L))
        assertEquals(300L, resumed.accumulatedPausedMs)
    }

    @Test
    fun `invalid or pre-start samples remain non-negative`() {
        val idle = Npv10RecordingDurationTimeline()
        assertEquals(0L, Npv10RecordingDurationPolicy.activeDurationMs(idle, Long.MIN_VALUE))

        val started = Npv10RecordingDurationPolicy.start(nowElapsedMs = 1_000L)
        assertEquals(0L, Npv10RecordingDurationPolicy.activeDurationMs(started, 500L))
    }
}
