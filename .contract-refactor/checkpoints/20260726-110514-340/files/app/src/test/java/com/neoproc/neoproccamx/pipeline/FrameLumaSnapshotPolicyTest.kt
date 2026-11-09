package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameLumaSnapshotPolicyTest {

    @Test
    fun fullSensorLumaSnapshot_scalesBeforeCpuAnalysis() {
        val dimensions = FrameLumaSnapshotPolicy.resolveDimensions(
            sourceWidth = 4096,
            sourceHeight = 2304,
            targetLongEdge = 384
        )

        assertEquals(384, dimensions.width)
        assertEquals(216, dimensions.height)
    }

    @Test
    fun smallFrames_areNotUpscaledForAnalysis() {
        val dimensions = FrameLumaSnapshotPolicy.resolveDimensions(
            sourceWidth = 320,
            sourceHeight = 180,
            targetLongEdge = 384
        )

        assertEquals(320, dimensions.width)
        assertEquals(180, dimensions.height)
    }
}
