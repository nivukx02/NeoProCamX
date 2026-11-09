package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysSaveAtAchievedFpsPolicyTest {

    @Test
    fun `accepts a genuine clip with imported frames and a bound encoder`() {
        assertTrue(
            AlwaysSaveAtAchievedFpsPolicy.canAcceptAtAchievedFps(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 40L,
                firstSuccessfulImportElapsedMs = 1_000L,
                lastSuccessfulImportElapsedMs = 2_500L
            )
        )
    }

    @Test
    fun `rejects a stub clip below the real-footage floor`() {
        assertFalse(
            AlwaysSaveAtAchievedFpsPolicy.canAcceptAtAchievedFps(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 3L,
                firstSuccessfulImportElapsedMs = 1_000L,
                lastSuccessfulImportElapsedMs = 1_100L
            )
        )
    }

    @Test
    fun `rejects when the encoder surface is not bound or import disabled`() {
        assertFalse(
            AlwaysSaveAtAchievedFpsPolicy.canAcceptAtAchievedFps(
                frameImportEnabled = true,
                encoderSurfaceBound = false,
                importedFrames = 40L,
                firstSuccessfulImportElapsedMs = 1_000L,
                lastSuccessfulImportElapsedMs = 2_500L
            )
        )
        assertFalse(
            AlwaysSaveAtAchievedFpsPolicy.canAcceptAtAchievedFps(
                frameImportEnabled = false,
                encoderSurfaceBound = true,
                importedFrames = 40L,
                firstSuccessfulImportElapsedMs = 1_000L,
                lastSuccessfulImportElapsedMs = 2_500L
            )
        )
    }

    @Test
    fun `rejects a non-monotonic or empty import window`() {
        assertFalse(
            AlwaysSaveAtAchievedFpsPolicy.canAcceptAtAchievedFps(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 40L,
                firstSuccessfulImportElapsedMs = 0L,
                lastSuccessfulImportElapsedMs = 0L
            )
        )
    }
}
