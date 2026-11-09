package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class Npv10FrameBackpressurePolicyTest {
    @Test
    fun strictNpv10AcceptsFramesInsideQueueBudget() {
        assertEquals(
            Npv10FrameBackpressureAction.ACCEPT,
            Npv10FrameBackpressurePolicy.resolve(pendingFrames = 8, maxPendingFrames = 8)
        )
    }

    @Test
    fun strictNpv10FailsMasterInsteadOfDroppingOverflowFrame() {
        assertEquals(
            Npv10FrameBackpressureAction.FAIL_STRICT_MASTER,
            Npv10FrameBackpressurePolicy.resolve(pendingFrames = 9, maxPendingFrames = 8)
        )
    }
}
