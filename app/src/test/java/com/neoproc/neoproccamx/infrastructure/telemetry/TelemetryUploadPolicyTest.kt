package com.neoproc.neoproccamx.infrastructure.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryUploadPolicyTest {
    @Test
    fun uploadRequiresConsentHttpsAndAnExactPinnedHost() {
        val pinnedHosts = setOf("telemetry.example.test")
        assertNull(TelemetryUploadPolicy.authorizedHttpsEndpoint(null, true, pinnedHosts))
        assertNull(TelemetryUploadPolicy.authorizedHttpsEndpoint("https://telemetry.example.test/v1", false, pinnedHosts))
        assertNull(TelemetryUploadPolicy.authorizedHttpsEndpoint("http://telemetry.example.test/v1", true, pinnedHosts))
        assertNull(TelemetryUploadPolicy.authorizedHttpsEndpoint("https://user:secret@example.test/v1", true, pinnedHosts))
        assertNull(TelemetryUploadPolicy.authorizedHttpsEndpoint("https://example.test/v1#fragment", true, pinnedHosts))
        assertNull(TelemetryUploadPolicy.authorizedHttpsEndpoint("https://unpinned.example.test/v1", true, pinnedHosts))
        assertEquals(
            "https://telemetry.example.test/v1",
            TelemetryUploadPolicy.authorizedHttpsEndpoint(
                " https://telemetry.example.test/v1 ",
                hasUserConsent = true,
                pinnedHosts = pinnedHosts
            )
        )
    }

    @Test
    fun wildcardPin_matchesOneDnsLabelOnly() {
        val pins = setOf("*.example.test")
        assertEquals(
            "https://telemetry.example.test/v1",
            TelemetryUploadPolicy.authorizedHttpsEndpoint("https://telemetry.example.test/v1", true, pins)
        )
        assertNull(
            TelemetryUploadPolicy.authorizedHttpsEndpoint("https://deep.telemetry.example.test/v1", true, pins)
        )
    }
}
