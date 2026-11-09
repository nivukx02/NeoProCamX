package com.neoproc.neoproccamx.security

import java.util.Locale

object AppSigningPolicy {
    fun normalizeFingerprint(rawFingerprint: String): String =
        rawFingerprint
            .uppercase(Locale.US)
            .replace(Regex("[^A-F0-9]"), "")

    fun parseAllowedFingerprints(rawConfig: String): Set<String> {
        val tokens = rawConfig
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val normalized = tokens
            .map(::normalizeFingerprint)
        require(normalized.size == tokens.size && normalized.all(::isValidSha256Fingerprint)) {
            "Signing certificate fingerprints must be complete SHA-256 values"
        }
        return normalized.toSet()
    }

    fun isValidSha256Fingerprint(fingerprint: String): Boolean {
        val normalized = normalizeFingerprint(fingerprint)
        return normalized.length == 64 && normalized.all { it in 'A'..'F' || it in '0'..'9' }
    }

    fun isTrustedInstalledSigners(
        observedFingerprints: Set<String>,
        allowedFingerprints: Set<String>,
        hasMultipleSigners: Boolean
    ): Boolean {
        val normalizedObserved = observedFingerprints
            .map(::normalizeFingerprint)
            .filter(::isValidSha256Fingerprint)
            .toSet()
        val normalizedAllowed = allowedFingerprints
            .map(::normalizeFingerprint)
            .filter(::isValidSha256Fingerprint)
            .toSet()
        if (normalizedAllowed.isEmpty()) return true
        if (normalizedObserved.isEmpty()) return false
        return if (hasMultipleSigners) {
            normalizedObserved == normalizedAllowed
        } else {
            normalizedObserved.any { it in normalizedAllowed }
        }
    }
}
