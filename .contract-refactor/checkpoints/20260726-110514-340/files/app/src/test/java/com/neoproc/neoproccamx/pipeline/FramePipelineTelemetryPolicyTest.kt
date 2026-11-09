package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePipelineTelemetryPolicyTest {

    @Test
    fun formatPoolUtilization_reportsIdleWhenNoBuffersExist() {
        assertEquals(
            "YUV idle",
            FramePipelineTelemetryPolicy.formatPoolUtilization(
                label = "YUV",
                snapshot = FramePoolSnapshot(activeBuffers = 0, pooledBuffers = 0)
            )
        )
    }

    @Test
    fun formatPoolUtilization_reportsActiveUtilizationPercentage() {
        assertEquals(
            "Luma 75% (3/4 active)",
            FramePipelineTelemetryPolicy.formatPoolUtilization(
                label = "Luma",
                snapshot = FramePoolSnapshot(activeBuffers = 3, pooledBuffers = 1)
            )
        )
    }

    @Test
    fun formatRollingDropSummary_reportsDropRateFpsAndBusyDrops() {
        assertEquals(
            "Drop 25.0% | 24fps | busy=7",
            FramePipelineTelemetryPolicy.formatRollingDropSummary(
                summary = FrameDropSummary(currentFps = 24, processedFrames = 18, skippedFrames = 6),
                busyDrops = 7L
            )
        )
    }
}
