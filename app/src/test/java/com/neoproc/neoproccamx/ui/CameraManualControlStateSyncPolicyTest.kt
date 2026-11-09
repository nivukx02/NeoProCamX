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

    @Test
    fun `process recreation plan preserves tint focus and exposure compensation`() {
        val plan = ManualControlRestorePlan(
            iso = ManualControlValue.Manual(800),
            exposureNs = ManualControlValue.Manual(20_000_000L),
            whiteBalanceKelvin = ManualControlValue.Manual(4_300),
            whiteBalanceTint = CameraManualControlStateSyncPolicy
                .resolveWhiteBalanceTintRestoreValue(-12, whiteBalanceIsManual = true),
            focusDistance = CameraManualControlStateSyncPolicy
                .resolveFocusRestoreValue(2.5f, maximumFocusDistance = 10f),
            exposureCompensation = CameraManualControlStateSyncPolicy
                .resolveExposureCompensationRestoreValue(2)
        )

        assertEquals(ManualControlValue.Manual(800), plan.iso)
        assertEquals(ManualControlValue.Manual(20_000_000L), plan.exposureNs)
        assertEquals(ManualControlValue.Manual(4_300), plan.whiteBalanceKelvin)
        assertEquals(ManualControlValue.Manual(-12), plan.whiteBalanceTint)
        assertEquals(ManualControlValue.Manual(2.5f), plan.focusDistance)
        assertEquals(ManualControlValue.Manual(2f), plan.exposureCompensation)

        CameraManualControlStateSyncPolicy.applyRestorePlan(plan)
        assertEquals(plan.iso, CameraManualControlStateSyncPolicy.snapshot().iso)
        assertEquals(plan.focusDistance, CameraManualControlStateSyncPolicy.snapshot().focusDistance)
        assertEquals(
            plan.exposureCompensation,
            CameraManualControlStateSyncPolicy.snapshot().exposureCompensation
        )
    }

    @Test
    fun `invalid persisted focus is restored as Auto`() {
        val focus = CameraManualControlStateSyncPolicy.resolveFocusRestoreValue(
            persistedFocusDistance = 12f,
            maximumFocusDistance = 5f
        )

        assertEquals(ManualControlValue.Auto, focus)
    }
}
