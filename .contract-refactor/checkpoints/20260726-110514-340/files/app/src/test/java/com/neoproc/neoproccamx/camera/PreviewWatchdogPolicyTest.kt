package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewWatchdogPolicyTest {

    @Test
    fun shouldRecover_returnsFalse_whenCaptureResultsAreStillFresh() {
        val shouldRecover = PreviewWatchdogPolicy.shouldRecover(
            hasReceivedYuvFrame = true,
            hasReceivedCaptureResult = true,
            lastYuvFrameArrivalMs = 1_000L,
            lastCaptureResultArrivalMs = 5_800L,
            nowMs = 6_000L,
            thresholdMs = 5_000L
        )

        assertFalse(shouldRecover)
    }

    @Test
    fun shouldRecover_returnsTrue_whenAllPreviewActivityIsStale() {
        val shouldRecover = PreviewWatchdogPolicy.shouldRecover(
            hasReceivedYuvFrame = true,
            hasReceivedCaptureResult = true,
            lastYuvFrameArrivalMs = 500L,
            lastCaptureResultArrivalMs = 700L,
            nowMs = 6_500L,
            thresholdMs = 5_000L
        )

        assertTrue(shouldRecover)
    }

    @Test
    fun shouldRecover_returnsFalse_beforeAnyPreviewActivityArrives() {
        val shouldRecover = PreviewWatchdogPolicy.shouldRecover(
            hasReceivedYuvFrame = false,
            hasReceivedCaptureResult = false,
            lastYuvFrameArrivalMs = 0L,
            lastCaptureResultArrivalMs = 0L,
            nowMs = 10_000L,
            thresholdMs = 5_000L
        )

        assertFalse(shouldRecover)
    }
}
