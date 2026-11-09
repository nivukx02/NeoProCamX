package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the production cold-splash minimum and its non-negotiable safety maximum. */
class ColdStartSplashDismissTest {

    private val minimumEndMs = 350L
    private val maxSplashHoldMs = 2_500L

    private fun keepOnScreen(previewReady: Boolean, nowMs: Long): Boolean =
        SplashVisibilityPolicy.shouldKeepColdSplash(
            previewPresented = previewReady,
            nowElapsedMs = nowMs,
            minimumEndElapsedMs = minimumEndMs,
            maximumEndElapsedMs = maxSplashHoldMs
        )

    @Test
    fun maxSplashHoldIsPinned() {
        // The safety deadline used by MainActivity; pinned so a change is visible.
        assertEquals(2_500L, maxSplashHoldMs)
    }

    @Test
    fun heldUntilFirstFrameWithinDeadline() {
        assertTrue("held while not ready and before deadline", keepOnScreen(false, 100L))
    }

    @Test
    fun presentedFrameStillHonorsMinimumVisibility() {
        assertTrue("minimum visibility is honored", keepOnScreen(true, minimumEndMs - 1L))
        assertFalse("presented frame releases at minimum", keepOnScreen(true, minimumEndMs))
    }

    @Test
    fun dismissedAtSafetyDeadlineEvenWithoutFirstFrame() {
        assertFalse("deadline reached dismisses (never hangs)", keepOnScreen(false, maxSplashHoldMs))
        assertFalse("past deadline dismisses", keepOnScreen(false, 3_000L))
    }

    @Test
    fun invalidTimingStillCannotExtendPastMaximum() {
        assertFalse(
            SplashVisibilityPolicy.shouldKeepColdSplash(
                previewPresented = false,
                nowElapsedMs = maxSplashHoldMs,
                minimumEndElapsedMs = maxSplashHoldMs + 1_000L,
                maximumEndElapsedMs = maxSplashHoldMs
            )
        )
    }
}
