package com.neoproc.neoproccamx.camera

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensSelectionTaskFlagTest {

    @After
    fun resetFlag() {
        // Process-global flag: restore the no-downgrade default for any other suite.
        CameraLensSelectionTaskFlag.registerOnChanged(null)
        CameraLensSelectionTaskFlag.set(false)
    }

    @Test
    fun `task-aware lens selection gate defaults off`() {
        // No-downgrade: the gate must be OFF until the owner enables it device-side.
        assertFalse(CameraLensSelectionTaskFlag.enabled)
    }

    @Test
    fun `set notifies listener only on change`() {
        var notifications = 0
        CameraLensSelectionTaskFlag.registerOnChanged { notifications++ }

        CameraLensSelectionTaskFlag.set(true)
        assertTrue(CameraLensSelectionTaskFlag.enabled)

        // Same value again -> no extra notification.
        CameraLensSelectionTaskFlag.set(true)
        CameraLensSelectionTaskFlag.set(false)
        assertFalse(CameraLensSelectionTaskFlag.enabled)

        // true (1) + false (1) = 2 change notifications; the redundant true fires nothing.
        org.junit.Assert.assertEquals(2, notifications)
    }
}
