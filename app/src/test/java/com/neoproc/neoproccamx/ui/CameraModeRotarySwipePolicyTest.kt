package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeRotarySwipePolicyTest {

    @Test
    fun `left swipe advances hidden cyclic selector`() {
        assertEquals(1, CameraModeRotarySwipePolicy.resolveStep(180f, 80f, 48f))
    }

    @Test
    fun `right swipe reverses hidden cyclic selector`() {
        assertEquals(-1, CameraModeRotarySwipePolicy.resolveStep(80f, 180f, 48f))
    }

    @Test
    fun `short movement does not rotate mode`() {
        assertEquals(0, CameraModeRotarySwipePolicy.resolveStep(100f, 120f, 48f))
    }
}
