package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeRailAutoSelectionPolicyTest {

    @Test
    fun shouldScheduleAutoSelection_returnsFalseDuringDirectTapSuppression() {
        assertFalse(
            CameraModeRailAutoSelectionPolicy.shouldScheduleAutoSelection(
                nowMs = 1_000L,
                suppressedUntilMs = 1_600L
            )
        )
    }

    @Test
    fun shouldScheduleAutoSelection_returnsTrueAfterDirectTapSuppressionExpires() {
        assertTrue(
            CameraModeRailAutoSelectionPolicy.shouldScheduleAutoSelection(
                nowMs = 1_601L,
                suppressedUntilMs = 1_600L
            )
        )
    }
}
