package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSigningPolicyTest {
    private val oldCert = "01".repeat(32)
    private val newCert = "AB".repeat(32)
    private val secondCert = "CD".repeat(32)

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
            observedFingerprints = setOf(oldCert, newCert),
            allowedFingerprints = setOf(newCert),
            hasMultipleSigners = false
        )

        assertTrue(trusted)
    }

    @Test
    fun isTrustedInstalledSigners_requiresExactSignerSetForMultiSignerApk() {
        val trusted = AppSigningPolicy.isTrustedInstalledSigners(
            observedFingerprints = setOf(newCert),
            allowedFingerprints = setOf(newCert, secondCert),
            hasMultipleSigners = true
        )

        assertFalse(trusted)
    }

    @Test
    fun parseAllowedFingerprints_rejectsTruncatedOrMalformedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSigningPolicy.parseAllowedFingerprints("AA:BB:CC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSigningPolicy.parseAllowedFingerprints("not-a-fingerprint")
        }
    }

    @Test
    fun parseAllowedFingerprints_acceptsCompleteColonSeparatedSha256() {
        val colonSeparated = newCert.chunked(2).joinToString(":")
        assertEquals(setOf(newCert), AppSigningPolicy.parseAllowedFingerprints(colonSeparated))
    }
}
