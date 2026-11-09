package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModeSwitchBatchFlushSchedulerTest {

    @Test
    fun scheduledBatchFlushWaitsForTheFrameCallback() {
        val posted = mutableListOf<Runnable>()
        var flushCount = 0
        val scheduler = ModeSwitchBatchFlushScheduler(posted::add)

        scheduler.schedule(Runnable { flushCount += 1 })

        assertEquals(0, flushCount)
        posted.single().run()
        assertEquals(1, flushCount)
    }

    @Test
    fun emergencyFlushRunsExactlyOnceAndInvalidatesThePostedCallback() {
        val posted = mutableListOf<Runnable>()
        var flushCount = 0
        val scheduler = ModeSwitchBatchFlushScheduler(posted::add)

        scheduler.schedule(Runnable { flushCount += 1 })
        scheduler.flushNow()
        posted.single().run()

        assertEquals(1, flushCount)
    }

    @Test
    fun aFollowingSwitchFlushesThePreviousTransactionBeforeItSchedulesItsOwn() {
        val posted = mutableListOf<Runnable>()
        val flushed = mutableListOf<String>()
        val scheduler = ModeSwitchBatchFlushScheduler(posted::add)

        scheduler.schedule(Runnable { flushed += "first" })
        scheduler.schedule(Runnable { flushed += "second" })

        assertEquals(listOf("first"), flushed)
        posted.forEach(Runnable::run)
        assertEquals(listOf("first", "second"), flushed)
    }

    @Test
    fun activityPostsTheLosslessFlushAfterAUiFrameAndFlushesBeforeBackgrounding() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
            .readText()
            .replace("\r\n", "\n")
        val activation = source
            .substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun resetAutoImagingStateForPreset()")
        val pause = source
            .substringAfter("override fun onPause()")
            .substringBefore("override fun onStop()")

        assertTrue(source.contains("binding.root.postOnAnimation"))
        assertTrue(source.contains("binding.root.post(work)"))
        assertTrue(
            activation.indexOf("if (!modePresetSwitchDecision.accepted)") <
                activation.indexOf("binding.glSurface.resetPreviewFpsTracking()")
        )
        assertTrue(
            activation.indexOf("binding.glSurface.resetPreviewFpsTracking()") <
                activation.indexOf("vm.beginModeSwitchPreviewWorkBatch()")
        )
        assertTrue(activation.contains("modeSwitchBatchFlushScheduler.schedule(finishBatch)"))
        assertTrue(activation.contains("rawStreamFamilyBoundary"))
        assertTrue(activation.contains("finishBatch.run()"))
        assertTrue(
            activation.indexOf("if (rawStreamFamilyBoundary)") <
                activation.indexOf("modeSwitchBatchFlushScheduler.schedule(finishBatch)")
        )
        assertTrue(activation.contains("ModeSwitchGestureTiming"))
        assertTrue(
            pause.indexOf("modeSwitchBatchFlushScheduler.flushNow()") <
                pause.indexOf("vm.handleAppBackgrounded()")
        )
    }
}
