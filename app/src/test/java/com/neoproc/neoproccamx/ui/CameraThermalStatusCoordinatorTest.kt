package com.neoproc.neoproccamx.ui

import android.os.PowerManager
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraThermalStatusCoordinatorTest {
    @Test
    fun `nominal through moderate statuses preserve the established high end tiers`() {
        val nominal = CameraThermalStatusPolicy.resolve(PowerManager.THERMAL_STATUS_NONE)
        val light = CameraThermalStatusPolicy.resolve(PowerManager.THERMAL_STATUS_LIGHT)
        val moderate = CameraThermalStatusPolicy.resolve(PowerManager.THERMAL_STATUS_MODERATE)

        assertEquals(HighEndRuntimeTier.ULTRA, nominal.tier)
        assertEquals(HighEndRuntimeTier.CINEMA, light.tier)
        assertEquals(HighEndRuntimeTier.PROFESSIONAL, moderate.tier)
        assertEquals("NONE", nominal.runtimePolicyLabel)
        assertEquals("LIGHT", light.runtimePolicyLabel)
        assertEquals("MODERATE", moderate.runtimePolicyLabel)
        assertFalse(nominal.throttled)
        assertFalse(moderate.throttled)
    }

    @Test
    fun `severe and critical statuses keep the original mitigation boundary`() {
        val severe = CameraThermalStatusPolicy.resolve(PowerManager.THERMAL_STATUS_SEVERE)
        val critical = CameraThermalStatusPolicy.resolve(PowerManager.THERMAL_STATUS_CRITICAL)

        assertEquals(HighEndRuntimeTier.SUSTAINED, severe.tier)
        assertEquals(HighEndRuntimeTier.RECOVERY, critical.tier)
        assertEquals("SEVERE", severe.runtimePolicyLabel)
        assertEquals("CRITICAL", critical.runtimePolicyLabel)
        assertTrue(severe.throttled)
        assertTrue(critical.throttled)
    }

    @Test
    fun `higher platform statuses remain recovery instead of inventing a quality fallback`() {
        val emergency = CameraThermalStatusPolicy.resolve(PowerManager.THERMAL_STATUS_EMERGENCY)

        assertEquals(HighEndRuntimeTier.RECOVERY, emergency.tier)
        assertEquals("EMERGENCY", emergency.runtimePolicyLabel)
        assertTrue(emergency.throttled)
    }
}
