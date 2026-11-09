package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraManualControlStateSyncPolicyTest {

    @Test
    fun `resolveRestorePlan treats non positive white balance sentinel as auto`() {
        val resolved = CameraManualControlStateSyncPolicy.resolveWhiteBalanceRestoreValue(
            persistedWhiteBalanceKelvin = -1
        )

        assertEquals(ManualControlValue.Auto, resolved)
    }

    @Test
    fun `resolveRestorePlan preserves positive white balance kelvin as manual`() {
        val resolved = CameraManualControlStateSyncPolicy.resolveWhiteBalanceRestoreValue(
            persistedWhiteBalanceKelvin = 5600
        )

        assertEquals(ManualControlValue.Manual(5600), resolved)
    }
}
