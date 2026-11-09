package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPendingFrameBackpressurePolicyTest {

    @Test
    fun skipsBitmapPreparationWhenAnalysisQueueAlreadyHasPendingFrame() {
        assertTrue(
            AIPendingFrameBackpressurePolicy.shouldSkipBitmapPreparation(
                hasPendingFrame = true,
                analysisLoopRunning = true
            )
        )
    }

    @Test
    fun keepsPreparingWhenQueueIsIdleOrDrainingStopped() {
        assertFalse(
            AIPendingFrameBackpressurePolicy.shouldSkipBitmapPreparation(
                hasPendingFrame = false,
                analysisLoopRunning = true
            )
        )
        assertFalse(
            AIPendingFrameBackpressurePolicy.shouldSkipBitmapPreparation(
                hasPendingFrame = true,
                analysisLoopRunning = false
            )
        )
    }
}
