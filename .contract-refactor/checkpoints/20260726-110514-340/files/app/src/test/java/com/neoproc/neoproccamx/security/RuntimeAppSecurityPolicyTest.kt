package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAppSecurityPolicyTest {
    @Test
    fun evaluate_blocksTamperedReleaseBuildsWhenBlockingEnabled() {
        val decision = RuntimeAppSecurityPolicy.evaluate(
            isDebugBuild = false,
            tampered = true,
            rooted = false,
            emulator = false,
            blockOnTamper = true
        )

        assertEquals(RuntimeSecurityStartupAction.BLOCK, decision.startupAction)
        assertTrue(decision.reasons.contains("tampered_apk"))
    }

    @Test
    fun evaluate_warnsButDoesNotBlockCompromisedDevices() {
        val decision = RuntimeAppSecurityPolicy.evaluate(
            isDebugBuild = false,
            tampered = false,
            rooted = true,
            emulator = true,
            blockOnTamper = true
        )

        assertEquals(RuntimeSecurityStartupAction.WARN, decision.startupAction)
    }

    @Test
    fun evaluate_allowsDebugBuildsForDeveloperWorkflows() {
        val decision = RuntimeAppSecurityPolicy.evaluate(
            isDebugBuild = true,
            tampered = true,
            rooted = true,
            emulator = true,
            blockOnTamper = true
        )

        assertEquals(RuntimeSecurityStartupAction.ALLOW, decision.startupAction)
    }
}
