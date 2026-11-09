package com.neoproc.neoproccamx.ui.health

import com.neoproc.neoproccamx.monitor.HealthDiagnostics
import com.neoproc.neoproccamx.monitor.HealthState
import com.neoproc.neoproccamx.monitor.ModuleHealthStatus
import com.neoproc.neoproccamx.monitor.MonitoredModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthChipVisualPolicyTest {

    private fun status(state: HealthState, recovering: Boolean = false) = ModuleHealthStatus(
        module = MonitoredModule.PROV_CORE,
        state = state,
        reason = "r",
        diagnostics = HealthDiagnostics(pipelineStage = "test"),
        recovering = recovering
    )

    @Test
    fun healthy_greenNoAnimationNoIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.HEALTHY))
        assertEquals(HealthChipVisualPolicy.COLOR_HEALTHY, v.textArgb)
        assertEquals(HealthChipAnimation.NONE, v.animation)
        assertFalse(v.showWarningIcon)
        assertEquals(HealthChipVisualPolicy.ALPHA_ACTIVE, v.alpha)
    }

    @Test
    fun warning_amberPulseIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.WARNING))
        assertEquals(HealthChipVisualPolicy.COLOR_WARNING, v.textArgb)
        assertEquals(HealthChipAnimation.SLOW_PULSE, v.animation)
        assertTrue(v.showWarningIcon)
    }

    @Test
    fun error_redGlowShakeIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.ERROR))
        assertEquals(HealthChipVisualPolicy.COLOR_ERROR, v.textArgb)
        assertEquals(HealthChipAnimation.ERROR_GLOW_SHAKE, v.animation)
        assertTrue(v.showWarningIcon)
    }

    @Test
    fun errorRecovering_bluePulse() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.ERROR, recovering = true))
        assertEquals(HealthChipVisualPolicy.COLOR_RECOVERING, v.textArgb)
        assertEquals(HealthChipAnimation.SLOW_PULSE, v.animation)
    }

    @Test
    fun inactive_grayLowOpacityNoAnimation() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.INACTIVE))
        assertEquals(HealthChipVisualPolicy.COLOR_INACTIVE, v.textArgb)
        assertEquals(HealthChipVisualPolicy.ALPHA_INACTIVE, v.alpha)
        assertEquals(HealthChipAnimation.NONE, v.animation)
    }

    @Test
    fun active_greenFullOpacityNoIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.ACTIVE))
        assertEquals(HealthChipVisualPolicy.COLOR_ACTIVE, v.textArgb)
        assertEquals(HealthChipVisualPolicy.ALPHA_ACTIVE, v.alpha)
        assertFalse(v.showWarningIcon)
        assertEquals(HealthChipAnimation.NONE, v.animation)
    }

    @Test
    fun ready_tealFullOpacityNoIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.READY))
        assertEquals(HealthChipVisualPolicy.COLOR_READY, v.textArgb)
        assertEquals(HealthChipVisualPolicy.ALPHA_ACTIVE, v.alpha)
        assertFalse(v.showWarningIcon)
    }

    @Test
    fun unknown_neutralNoIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.UNKNOWN))
        assertEquals(HealthChipVisualPolicy.COLOR_UNKNOWN, v.textArgb)
        assertFalse(v.showWarningIcon)
        assertEquals(HealthChipAnimation.NONE, v.animation)
    }

    @Test
    fun unsupported_dimGrayLowOpacityNoIcon() {
        val v = HealthChipVisualPolicy.visual(status(HealthState.UNSUPPORTED))
        assertEquals(HealthChipVisualPolicy.COLOR_UNSUPPORTED, v.textArgb)
        assertEquals(HealthChipVisualPolicy.ALPHA_INACTIVE, v.alpha)
        assertFalse(v.showWarningIcon)
    }

    @Test
    fun displayStateLabel_errorRecovering_readsRecovering() {
        val s = status(HealthState.ERROR, recovering = true)
        assertEquals("RECOVERING", s.displayStateLabel)
        // FAILED is the display name for a non-recovering ERROR.
        assertEquals("FAILED", status(HealthState.ERROR).displayStateLabel)
        assertEquals("ACTIVE", status(HealthState.ACTIVE).displayStateLabel)
    }

    @Test
    fun recoveringFlagIgnoredWhenNotError() {
        // recovering only applies to ERROR; a HEALTHY status is never shown as recovering.
        val v = HealthChipVisualPolicy.visual(status(HealthState.HEALTHY, recovering = true))
        assertEquals(HealthChipVisualPolicy.COLOR_HEALTHY, v.textArgb)
    }
}
