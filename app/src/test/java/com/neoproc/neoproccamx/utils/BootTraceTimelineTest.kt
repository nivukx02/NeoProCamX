package com.neoproc.neoproccamx.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootTraceTimelineTest {
    @Test
    fun `activity recreation resets launch elapsed time but preserves process correlation`() {
        val timeline = BootTraceTimeline(processStartElapsedMs = 1_000L)
        assertEquals(1L, timeline.beginActivityLaunch(nowElapsedMs = 2_000L))
        val firstLaunch = timeline.timingFor("activity.setContentView.done", nowElapsedMs = 2_180L)

        assertEquals(BootTraceTimeline.Scope.ACTIVITY, firstLaunch.scope)
        assertEquals(180L, firstLaunch.elapsedMs)
        assertEquals(1_180L, firstLaunch.processElapsedMs)
        assertEquals(1L, firstLaunch.activityLaunchGeneration)

        assertEquals(2L, timeline.beginActivityLaunch(nowElapsedMs = 2_177_000L))
        val recreated = timeline.timingFor("activity.setContentView.done", nowElapsedMs = 2_177_220L)

        assertEquals(BootTraceTimeline.Scope.ACTIVITY, recreated.scope)
        assertEquals(220L, recreated.elapsedMs)
        assertEquals(2_176_220L, recreated.processElapsedMs)
        assertEquals(2L, recreated.activityLaunchGeneration)
    }

    @Test
    fun `application milestones always remain process relative`() {
        val timeline = BootTraceTimeline(processStartElapsedMs = 100L)
        timeline.beginActivityLaunch(nowElapsedMs = 1_000L)

        val timing = timeline.timingFor("app.onCreate.end", nowElapsedMs = 1_250L)

        assertEquals(BootTraceTimeline.Scope.PROCESS, timing.scope)
        assertEquals(1_150L, timing.elapsedMs)
        assertEquals(1_150L, timing.processElapsedMs)
        assertNull(timing.activityLaunchGeneration)
    }

    @Test
    fun `first camera frame is claimed once per activity launch`() {
        val timeline = BootTraceTimeline(processStartElapsedMs = 0L)
        timeline.beginActivityLaunch(nowElapsedMs = 10L)
        assertTrue(timeline.claimFirstFrame())
        assertFalse(timeline.claimFirstFrame())

        timeline.beginActivityLaunch(nowElapsedMs = 20L)
        assertTrue(timeline.claimFirstFrame())
        assertFalse(timeline.claimFirstFrame())
    }
}
