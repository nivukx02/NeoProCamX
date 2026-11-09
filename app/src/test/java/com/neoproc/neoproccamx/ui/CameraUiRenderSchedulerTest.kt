package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiRenderSchedulerTest {

    @Test
    fun request_coalescesMultiplePassesIntoOneFrameCallback() {
        val posted = mutableListOf<Runnable>()
        val rendered = mutableListOf<Set<CameraUiRenderPass>>()
        val scheduler = CameraUiRenderScheduler(
            framePoster = { posted.add(it) },
            render = { rendered.add(it) }
        )

        scheduler.request(CameraUiRenderPass.STATUS_CHROME)
        scheduler.request(CameraUiRenderPass.STATUS_CHROME)
        scheduler.request(CameraUiRenderPass.TOP_WARNING)

        assertEquals(1, posted.size)
        posted.single().run()
        assertEquals(
            setOf(CameraUiRenderPass.STATUS_CHROME, CameraUiRenderPass.TOP_WARNING),
            rendered.single()
        )
    }

    @Test
    fun cancel_dropsPendingFrameWork() {
        val posted = mutableListOf<Runnable>()
        val rendered = mutableListOf<Set<CameraUiRenderPass>>()
        val scheduler = CameraUiRenderScheduler(
            framePoster = { posted.add(it) },
            render = { rendered.add(it) }
        )

        scheduler.request(CameraUiRenderPass.STATUS_CHROME)
        scheduler.cancel()
        posted.single().run()

        assertTrue(rendered.isEmpty())
    }
}
