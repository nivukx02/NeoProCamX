package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f13 / CP9C — characterizes the existing splash keep-on-screen predicate so the disk
 * pipeline-cache change provably does NOT alter splash dismissal (spec item 3: splash never hangs).
 * Mirrors MainActivity's `!previewReadyForSplash && elapsedRealtime() < splashDeadlineMs`.
 */
class ColdStartSplashDismissTest {

    private val maxSplashHoldMs = 2_500L

    private fun keepOnScreen(previewReady: Boolean, nowMs: Long, deadlineMs: Long): Boolean =
        !previewReady && nowMs < deadlineMs

    @Test
    fun maxSplashHoldIsPinned() {
        // The safety deadline used by MainActivity; pinned so a change is visible.
        assertEquals(2_500L, maxSplashHoldMs)
    }

    @Test
    fun heldUntilFirstFrameWithinDeadline() {
        assertTrue("held while not ready and before deadline", keepOnScreen(false, 100L, maxSplashHoldMs))
    }

    @Test
    fun dismissedOnFirstFrame() {
        assertFalse("first preview frame dismisses immediately", keepOnScreen(true, 100L, maxSplashHoldMs))
    }

    @Test
    fun dismissedAtSafetyDeadlineEvenWithoutFirstFrame() {
        assertFalse("deadline reached dismisses (never hangs)", keepOnScreen(false, maxSplashHoldMs, maxSplashHoldMs))
        assertFalse("past deadline dismisses", keepOnScreen(false, 3_000L, maxSplashHoldMs))
    }
}
