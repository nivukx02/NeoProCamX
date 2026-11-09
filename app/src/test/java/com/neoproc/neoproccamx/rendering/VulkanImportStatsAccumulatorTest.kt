package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanImportStatsAccumulatorTest {
    @Test
    fun everySuccessfulImportEmitsCurrentHealthForThePreviewWatchdog() {
        val accumulator = VulkanImportStatsAccumulator(VulkanImportStats())

        repeat(14) { index ->
            val result = accumulator.recordSuccessfulImport(
                width = 1920,
                height = 1080,
                format = 35,
                elapsedMs = 1_000L + index
            )
            assertTrue(result.shouldEmitStats)
        }

        val fifteenth = accumulator.recordSuccessfulImport(
            width = 1920,
            height = 1080,
            format = 35,
            elapsedMs = 1_014L
        )

        assertTrue(fifteenth.shouldEmitStats)
        assertEquals(15L, fifteenth.stats.importedFrames)
        assertEquals(15L, fifteenth.stats.sessionImportedFrames)
        assertEquals(1_000L, fifteenth.stats.firstSuccessfulImportElapsedMs)
        assertEquals(1_014L, fifteenth.stats.lastSuccessfulImportElapsedMs)
        assertEquals(fifteenth.stats, accumulator.current)
    }

    @Test
    fun resetAndDisableKeepInternalAndEmittedStatsCoherent() {
        val accumulator = VulkanImportStatsAccumulator(VulkanImportStats(frameImportEnabled = true))

        repeat(15) { index ->
            accumulator.recordSuccessfulImport(
                width = 1280,
                height = 720,
                format = 35,
                elapsedMs = 2_000L + index
            )
        }
        val reset = accumulator.resetSession(elapsedMs = 3_000L)
        val disabled = accumulator.setFrameImportEnabled(enabled = false)

        assertEquals(reset.stats, accumulator.current.copy(frameImportEnabled = true))
        assertEquals(0L, disabled.stats.sessionImportedFrames)
        assertEquals(0L, disabled.stats.sessionFailedImports)
        assertEquals(0L, disabled.stats.consecutiveFailedImports)
        assertEquals(0L, disabled.stats.firstSuccessfulImportElapsedMs)
        assertEquals(0L, disabled.stats.lastSuccessfulImportElapsedMs)
        assertFalse(disabled.stats.frameImportEnabled)
        assertEquals(disabled.stats, accumulator.current)
    }
}
