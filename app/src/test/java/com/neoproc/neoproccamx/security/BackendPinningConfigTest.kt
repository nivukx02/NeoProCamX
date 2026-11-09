package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPinningConfigTest {
    private val zeroPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val onePin = "sha256/AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="

    @Test
    fun parse_extractsHostsAndPinsFromBuildConfigString() {
        val config = BackendPinningConfig.parse(
            "API.Example.com=$zeroPin,$onePin;cdn.example.com=$zeroPin"
        )

        assertEquals(listOf(zeroPin, onePin), config["api.example.com"])
        assertEquals(listOf(zeroPin), config["cdn.example.com"])
    }

    @Test
    fun parse_rejectsMalformedEntriesInsteadOfSilentlyDisablingPinning() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendPinningConfig.parse("invalid-entry")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendPinningConfig.parse("video.example.com=sha256/XYZ")
        }
        assertTrue(BackendPinningConfig.parse(" ").isEmpty())
    }
}
