package com.neoproc.neoproccamx.security

import java.util.Locale

object AppSigningPolicy {
    fun normalizeFingerprint(rawFingerprint: String): String =
        rawFingerprint
            .uppercase(Locale.US)
            .replace(Regex("[^A-F0-9]"), "")

    fun parseAllowedFingerprints(rawConfig: String): Set<String> =
        rawConfig
            .split(',', ';', '\n')
            .map(::normalizeFingerprint)
            .filter { it.isNotEmpty() }
            .toSet()

    fun isTrustedInstalledSigners(
        observedFingerprints: Set<String>,
        allowedFingerprints: Set<String>,
        hasMultipleSigners: Boolean
    ): Boolean {
        val normalizedObserved = observedFingerprints.map(::normalizeFingerprint).filter { it.isNotEmpty() }.toSet()
        val normalizedAllowed = allowedFingerprints.map(::normalizeFingerprint).filter { it.isNotEmpty() }.toSet()
        if (normalizedAllowed.isEmpty()) return true
        if (normalizedObserved.isEmpty()) return false
        return if (hasMultipleSigners) {
            normalizedObserved == normalizedAllowed
        } else {
            normalizedObserved.any { it in normalizedAllowed }
        }
    }
}
