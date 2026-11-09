package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsSuspensionStateTest {
    @Test
    fun oneSettingsOwnershipCycleArmsExactlyOneFastResume() {
        val state = CameraSettingsSuspensionState()

        assertTrue(state.suspend())
        assertFalse(state.suspend())
        assertTrue(state.isSuspended())
        assertTrue(state.resume())
        assertFalse(state.isSuspended())
        assertTrue(state.consumeFastResume())
        assertFalse(state.consumeFastResume())
        assertFalse(state.resume())
    }
}
