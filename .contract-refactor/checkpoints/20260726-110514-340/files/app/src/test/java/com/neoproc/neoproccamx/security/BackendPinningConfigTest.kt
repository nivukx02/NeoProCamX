package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPinningConfigTest {
    @Test
    fun parse_extractsHostsAndPinsFromBuildConfigString() {
        val config = BackendPinningConfig.parse(
            "api.example.com=sha256/AAA,sha256/BBB;cdn.example.com=sha256/CCC"
        )

        assertEquals(listOf("sha256/AAA", "sha256/BBB"), config["api.example.com"])
        assertEquals(listOf("sha256/CCC"), config["cdn.example.com"])
    }

    @Test
    fun parse_ignoresBlankAndMalformedEntries() {
        val config = BackendPinningConfig.parse(
            " ;invalid-entry;video.example.com=sha256/XYZ "
        )

        assertEquals(1, config.size)
        assertTrue(config.containsKey("video.example.com"))
    }
}
