package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.ai.FocusTarget
import com.neoproc.neoproccamx.ai.FocusTargetType
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusAssistVisualPolicyTest {

    @Test
    fun `manual tap focus uses tap visual`() {
        val visual = FocusAssistVisualPolicy.resolve(
            manualFocus = focusTarget(FocusTargetType.OBJECT),
            primaryFocus = focusTarget(FocusTargetType.EYE)
        )

        assertEquals(FocusAssistVisual.TAP, visual)
    }

    @Test
    fun `eye autofocus uses eye visual`() {
        val visual = FocusAssistVisualPolicy.resolve(
            manualFocus = null,
            primaryFocus = focusTarget(FocusTargetType.EYE)
        )

        assertEquals(FocusAssistVisual.EYE, visual)
    }

    @Test
    fun `face autofocus uses face visual`() {
        val visual = FocusAssistVisualPolicy.resolve(
            manualFocus = null,
            primaryFocus = focusTarget(FocusTargetType.FACE)
        )

        assertEquals(FocusAssistVisual.FACE, visual)
    }

    @Test
    fun `default state falls back to tap visual`() {
        val visual = FocusAssistVisualPolicy.resolve(
            manualFocus = null,
            primaryFocus = null
        )

        assertEquals(FocusAssistVisual.TAP, visual)
    }

    private fun focusTarget(type: FocusTargetType): FocusTarget =
        FocusTarget(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            type = type,
            trackId = 7
        )
}
