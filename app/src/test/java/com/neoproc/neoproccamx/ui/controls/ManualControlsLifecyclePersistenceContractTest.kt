package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualControlsLifecyclePersistenceContractTest {
    private val source: String
        get() = File("src/main/java/com/neoproc/neoproccamx/ui/controls/ManualControlsFragment.kt")
            .readText()
            .replace("\r\n", "\n")

    @Test
    fun `fragment creation and Pro entry render without mutating camera controls`() {
        val onViewCreated = source
            .substringAfter("override fun onViewCreated")
            .substringBefore("override fun onViewStateRestored")
        val proEntry = source
            .substringAfter("fun resetForProEntry()")
            .substringBefore("private fun bindViews")

        assertTrue(onViewCreated.contains("renderCurrentControlState()"))
        assertTrue(proEntry.contains("renderCurrentControlState()"))
        listOf(
            "unlockFocusAndExposure(",
            "setAutoISO(",
            "setAutoExposure(",
            "setAutoWB(",
            "setAutoFocus(",
            "setEV("
        ).forEach { mutation ->
            assertFalse("Lifecycle must not call $mutation", onViewCreated.contains(mutation))
            assertFalse("Pro entry must not call $mutation", proEntry.contains(mutation))
        }
        assertFalse(source.contains("private fun resetToAutoDefaults()"))
    }

    @Test
    fun `render restores every control from authoritative state and current parameters`() {
        val renderer = source
            .substringAfter("private fun renderCurrentControlState()")
            .substringBefore("private fun refreshIsoSliderRange()")

        assertTrue(renderer.contains("CameraManualControlStateSyncPolicy.snapshot()"))
        assertTrue(renderer.contains("vm.cameraParams.value"))
        assertTrue(renderer.contains("manualState.iso as? ManualControlValue.Manual"))
        assertTrue(renderer.contains("manualState.exposureNs as? ManualControlValue.Manual"))
        assertTrue(renderer.contains("manualState.whiteBalanceKelvin as? ManualControlValue.Manual"))
        assertTrue(renderer.contains("manualState.focusDistance as? ManualControlValue.Manual"))
        assertTrue(renderer.contains("manualState.exposureCompensation as? ManualControlValue.Manual"))
        assertTrue(renderer.contains("ManualControlValueMapper.progressForFocusDistance("))
        assertTrue(renderer.contains("renderAuxControlStates()"))
    }

    @Test
    fun `Auto remains an explicit per-control user action`() {
        val listeners = source
            .substringAfter("private fun bindListeners()")
            .substringBefore("private fun bindReactiveManualUpdates()")

        assertTrue(listeners.contains("vm.setAutoISO()"))
        assertTrue(listeners.contains("vm.setAutoExposure()"))
        assertTrue(listeners.contains("vm.setAutoWB()"))
        assertTrue(listeners.contains("vm.setAutoFocus()"))
        assertTrue(listeners.contains("vm.setEV(0)"))
    }
}
