package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualControlPresetPersistenceContractTest {
    private val activitySource = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
        .readText()
        .replace("\r\n", "\n")
    private val viewModelSource = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        .readText()
        .replace("\r\n", "\n")
    private val preferenceSource = File("src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt")
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun `preset activation delegates to non destructive runtime policy`() {
        val legacyEntry = activitySource
            .substringAfter("private fun resetAutoImagingStateForPreset()")
            .substringBefore("private fun syncPresetFromState()")

        assertTrue(legacyEntry.contains("vm.applyManualControlsForPreset(activeModePreset)"))
        assertFalse(legacyEntry.contains("vm.unlockFocusAndExposure()"))
        assertFalse(legacyEntry.contains("vm.setAutoISO()"))
        assertFalse(legacyEntry.contains("vm.setAutoExposure()"))
        assertFalse(legacyEntry.contains("vm.setAutoWB()"))
        assertFalse(legacyEntry.contains("vm.setAutoFocus()"))
        assertFalse(legacyEntry.contains("vm.setEV(0)"))
    }

    @Test
    fun `temporary Auto mutates only effective Camera2 state`() {
        val temporaryAuto = viewModelSource
            .substringAfter("private fun applyTemporaryAutomaticControlsToCamera()")
            .substringBefore("private fun supportedVideoSize(")

        assertTrue(temporaryAuto.contains("camera.setAutoISO()"))
        assertTrue(temporaryAuto.contains("camera.setAutoExposure()"))
        assertTrue(temporaryAuto.contains("camera.setAutoWB()"))
        assertTrue(temporaryAuto.contains("applyAutomaticFocusRuntime()"))
        assertTrue(temporaryAuto.contains("camera.setEV(0)"))
        assertFalse(temporaryAuto.contains("prefs."))
        assertFalse(temporaryAuto.contains("CameraManualControlStateSyncPolicy.set"))
    }

    @Test
    fun `process recreation restores persisted focus EV tint and authoritative snapshot`() {
        val resolver = viewModelSource
            .substringAfter("private fun resolvePersistedManualControlPlan()")
            .substringBefore("private fun applyProManualControlPlanToCamera")
        val restore = viewModelSource
            .substringAfter("private fun restorePersistedManualControls()")
            .substringBefore("private fun resolvePersistedManualControlPlan()")

        assertTrue(preferenceSource.contains("KEY_LAST_FOCUS_DISTANCE"))
        assertTrue(preferenceSource.contains("KEY_LAST_EV"))
        assertTrue(resolver.contains("persistedWhiteBalanceTint = prefs.lastWBTint"))
        assertTrue(resolver.contains("persistedFocusDistance = prefs.lastFocusDistance"))
        assertTrue(resolver.contains("persistedExposureCompensation = prefs.lastEV"))
        assertTrue(restore.contains("CameraManualControlStateSyncPolicy.applyRestorePlan(restorePlan)"))
        assertTrue(restore.contains("applyProManualControlPlanToCamera(restorePlan)"))
        assertTrue(restore.contains("applyTemporaryAutomaticControlsToCamera()"))
    }
}
