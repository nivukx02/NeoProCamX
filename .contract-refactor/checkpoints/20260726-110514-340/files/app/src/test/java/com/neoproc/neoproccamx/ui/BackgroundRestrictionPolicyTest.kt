package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRestrictionPolicyTest {

    @Test
    fun noWarningWhenRecordingInactive() {
        val warning = BackgroundRestrictionPolicy.resolveWarning(
            recordingActive = false,
            backgroundRestricted = true,
            batteryOptimizationActive = true
        )
        assertNull(warning)
    }

    @Test
    fun warningIncludesBothSignalsWhenRestrictedAndBatteryOptimized() {
        val warning = BackgroundRestrictionPolicy.resolveWarning(
            recordingActive = true,
            backgroundRestricted = true,
            batteryOptimizationActive = true
        )
        assertEquals(
            "Background restricted • Battery optimization may interrupt long recording",
            warning
        )
    }

    @Test
    fun warningMatchesBackgroundOnlyCase() {
        val warning = BackgroundRestrictionPolicy.resolveWarning(
            recordingActive = true,
            backgroundRestricted = true,
            batteryOptimizationActive = false
        )
        assertEquals("Background restricted • Long recording reliability reduced", warning)
    }

    @Test
    fun warningMatchesBatteryOnlyCase() {
        val warning = BackgroundRestrictionPolicy.resolveWarning(
            recordingActive = true,
            backgroundRestricted = false,
            batteryOptimizationActive = true
        )
        assertEquals("Battery optimization active • Long recording may pause in background", warning)
    }

    @Test
    fun warningClassifierOnlyMatchesBackgroundRestrictionMessages() {
        assertTrue(
            BackgroundRestrictionPolicy.isBackgroundRestrictionWarning(
                "Background restricted • Long recording reliability reduced"
            )
        )
        assertTrue(
            BackgroundRestrictionPolicy.isBackgroundRestrictionWarning(
                "Battery optimization active • Long recording may pause in background"
            )
        )
        assertFalse(BackgroundRestrictionPolicy.isBackgroundRestrictionWarning("Thermal Throttling: Bitrate Reduced"))
    }
}
