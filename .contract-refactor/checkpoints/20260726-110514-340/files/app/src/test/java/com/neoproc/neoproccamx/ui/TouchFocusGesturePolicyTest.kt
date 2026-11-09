package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchFocusGesturePolicyTest {

    @Test
    fun movementEffect_cancelsBothWhenHorizontalDriftExceedsTolerance() {
        val effect = TouchFocusGesturePolicy.movementEffect(
            deltaX = 30f,
            deltaY = 0f,
            holdTolerancePx = 24f,
            isScaling = false
        )

        assertEquals(FocusTouchMoveEffect.CANCEL_ADJUST_AND_LOCK, effect)
    }

    @Test
    fun movementEffect_cancelsLockOnlyForLargeVerticalDrift() {
        val effect = TouchFocusGesturePolicy.movementEffect(
            deltaX = 8f,
            deltaY = 60f,
            holdTolerancePx = 24f,
            isScaling = false
        )

        assertEquals(FocusTouchMoveEffect.CANCEL_LOCK_ONLY, effect)
    }

    @Test
    fun shouldIgnoreTapAfterNavigation_onlyWithinDebounceWindow() {
        assertTrue(
            TouchFocusGesturePolicy.shouldIgnoreTapAfterNavigation(
                nowMs = 1250L,
                lastGestureNavigateMs = 1000L
            )
        )
        assertFalse(
            TouchFocusGesturePolicy.shouldIgnoreTapAfterNavigation(
                nowMs = 1400L,
                lastGestureNavigateMs = 1000L
            )
        )
    }

    @Test
    fun movementEffect_appliesMinimumHoldToleranceWhenInputToleranceIsInvalid() {
        val effect = TouchFocusGesturePolicy.movementEffect(
            deltaX = 4f,
            deltaY = 7f,
            holdTolerancePx = 0f,
            isScaling = false
        )

        assertEquals(FocusTouchMoveEffect.NONE, effect)
    }

    @Test
    fun focusLockHoldDelay_matchesThreeSecondGestureRequirement() {
        assertEquals(3000L, TouchFocusGesturePolicy.focusLockHoldDelayMs())
    }
}
