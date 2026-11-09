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
}
