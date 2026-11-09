package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalCooldownEstimatePolicyTest {

    @Test
    fun append_nullEstimateReturnsOriginalWarning() {
        val warning = "Thermal Throttling: Bitrate Reduced"

        val result = ThermalCooldownEstimatePolicy.append(
            warningMessage = warning,
            cooldownEstimateSec = null
        )

        assertEquals(warning, result)
    }

    @Test
    fun append_negativeEstimateCoercesToZeroSeconds() {
        val result = ThermalCooldownEstimatePolicy.append(
            warningMessage = "Thermal Throttling: Bitrate Reduced",
            cooldownEstimateSec = -8
        )

        assertEquals("Thermal Throttling: Bitrate Reduced \u2022 Cooldown ~0s", result)
    }

    @Test
    fun append_secondsUnderMinuteFormatsAsSeconds() {
        val result = ThermalCooldownEstimatePolicy.append(
            warningMessage = "Thermal Throttling: Bitrate Reduced",
            cooldownEstimateSec = 59
        )

        assertEquals("Thermal Throttling: Bitrate Reduced \u2022 Cooldown ~59s", result)
    }

    @Test
    fun append_exactMinuteFormatsAsMinutesOnly() {
        val result = ThermalCooldownEstimatePolicy.append(
            warningMessage = "Thermal Throttling: Bitrate Reduced",
            cooldownEstimateSec = 120
        )

        assertEquals("Thermal Throttling: Bitrate Reduced \u2022 Cooldown ~2m", result)
    }

    @Test
    fun append_mixedMinuteSecondsFormatsAsMinutesAndSeconds() {
        val result = ThermalCooldownEstimatePolicy.append(
            warningMessage = "Thermal Throttling: Bitrate Reduced",
            cooldownEstimateSec = 125
        )

        assertEquals("Thermal Throttling: Bitrate Reduced \u2022 Cooldown ~2m 5s", result)
    }
}
