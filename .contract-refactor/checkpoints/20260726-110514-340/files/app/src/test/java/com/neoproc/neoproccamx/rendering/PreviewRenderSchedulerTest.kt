package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRenderSchedulerTest {

    @Test
    fun requestRender_schedulesOnlyOneFrameUntilDispatch() {
        var scheduledFrames = 0
        val scheduler = PreviewRenderScheduler(
            isMainThread = { true },
            postToMain = { it() },
            scheduleFrame = { scheduledFrames++ }
        )

        scheduler.requestRender()
        scheduler.requestRender()

        assertEquals(1, scheduledFrames)

        scheduler.onFrameDispatched()
        scheduler.requestRender()

        assertEquals(2, scheduledFrames)
    }

    @Test
    fun requestRender_postsToMainWhenOffMainThread() {
        var posted = false
        var scheduledFrames = 0
        val scheduler = PreviewRenderScheduler(
            isMainThread = { posted },
            postToMain = {
                posted = true
                it()
            },
            scheduleFrame = { scheduledFrames++ }
        )

        scheduler.requestRender()

        assertTrue(posted)
        assertEquals(1, scheduledFrames)
    }

    @Test
    fun cancelScheduledFrame_reportsWhenFrameWasPending() {
        val scheduler = PreviewRenderScheduler(
            isMainThread = { true },
            postToMain = { it() },
            scheduleFrame = {}
        )

        scheduler.requestRender()

        assertTrue(scheduler.cancelScheduledFrame())
        assertFalse(scheduler.cancelScheduledFrame())
    }

    @Test
    fun requestRender_queuesFollowUpFrameWhenRequestedDuringScheduledFrame() {
        var scheduledFrames = 0
        val scheduler = PreviewRenderScheduler(
            isMainThread = { true },
            postToMain = { it() },
            scheduleFrame = { scheduledFrames++ }
        )

        scheduler.requestRender()
        scheduler.requestRender()
        scheduler.onFrameDispatched()

        assertEquals(2, scheduledFrames)
    }
}
