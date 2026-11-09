package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanCinemaDebayerPipelineTest {

    @Test
    fun statsExposeRawMonitorAsPreviewOnlyAndNonDestructiveToRawMaster() {
        val pipeline = VulkanCinemaDebayerPipeline()

        val stats = pipeline.getStats()

        assertEquals("RAW_MONITOR_CINEMA_DEBAYER_PREVIEW_ONLY", stats.rawMonitorPipeline)
        assertFalse(stats.destructiveToRawMaster)
        assertTrue(stats.sensorMetadataRequired)
        assertEquals(0L, stats.debayeredFrames)
    }

    @Test
    fun deferredFenceCompletionUpdatesRealDiagnostics() {
        val pipeline = VulkanCinemaDebayerPipeline()

        pipeline.recordDeferredCompletion(
            width = 4096,
            height = 3072,
            dispatchStartedElapsedRealtimeNs = 1_000_000L,
            completionElapsedRealtimeNs = 6_000_000L
        )

        val stats = pipeline.getStats()
        assertEquals(1L, stats.debayeredFrames)
        assertEquals(5L, stats.lastDebayerTimeMs)
        assertEquals(5f, stats.averageDebayerTimeMs, 0f)
        assertEquals(4096, stats.lastBufferWidth)
        assertEquals(3072, stats.lastBufferHeight)
    }
}
