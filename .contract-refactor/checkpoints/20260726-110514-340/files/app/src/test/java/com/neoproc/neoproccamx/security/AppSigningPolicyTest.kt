package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSigningPolicyTest {
    @Test
    fun normalizeFingerprint_stripsSeparatorsAndUppercases() {
        assertEquals(
            "AABBCCDD",
            AppSigningPolicy.normalizeFingerprint("aa:bb cc-dd")
        )
    }

    @Test
    fun isTrustedInstalledSigners_acceptsCertificateHistoryForSingleSigner() {
        val trusted = AppSigningPolicy.isTrustedInstalledSigners(
            observedFingerprints = setOf("OLDCERT", "NEWCERT"),
            allowedFingerprints = setOf("NEWCERT"),
            hasMultipleSigners = false
        )

        assertTrue(trusted)
    }

    @Test
    fun isTrustedInstalledSigners_requiresExactSignerSetForMultiSignerApk() {
        val trusted = AppSigningPolicy.isTrustedInstalledSigners(
            observedFingerprints = setOf("CERT_A"),
            allowedFingerprints = setOf("CERT_A", "CERT_B"),
            hasMultipleSigners = true
        )

        assertFalse(trusted)
    }
}
