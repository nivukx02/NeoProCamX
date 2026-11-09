package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoCallbackOwnershipPolicyTest {
    @Test
    fun `raw mode always owns a reader consumer even with camera monitor preview`() {
        assertTrue(
            RawVideoCallbackOwnershipPolicy.callbacksNeeded(
                videoUiActive = true,
                rawModeActive = true
            )
        )
    }

    @Test
    fun `non raw modes do not retain raw callback ownership`() {
        assertFalse(RawVideoCallbackOwnershipPolicy.callbacksNeeded(videoUiActive = true, rawModeActive = false))
        assertFalse(RawVideoCallbackOwnershipPolicy.callbacksNeeded(videoUiActive = false, rawModeActive = true))
    }
}
