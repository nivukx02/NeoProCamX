package com.neoproc.neoproccamx.security

import java.net.IDN
import java.util.Base64
import java.util.Locale

object BackendPinningConfig {
    fun parse(rawConfig: String): Map<String, List<String>> {
        if (rawConfig.isBlank()) return emptyMap()
        val parsed = LinkedHashMap<String, LinkedHashSet<String>>()
        rawConfig.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0 && separator < entry.lastIndex) {
                    "Malformed certificate-pinning entry"
                }
                val host = normalizeHost(entry.substring(0, separator).trim())
                val pins = entry.substring(separator + 1)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map(::validateSha256Pin)
                require(pins.isNotEmpty()) { "Pinned host must provide at least one SHA-256 pin" }
                parsed.getOrPut(host, ::LinkedHashSet).addAll(pins)
            }
        return parsed.mapValues { (_, pins) -> pins.toList() }
    }

    private fun normalizeHost(rawHost: String): String {
        require(rawHost.isNotBlank()) { "Pinned host must not be blank" }
        val wildcard = rawHost.startsWith("*.")
        val hostWithoutWildcard = if (wildcard) rawHost.removePrefix("*.") else rawHost
        val ascii = runCatching {
            IDN.toASCII(hostWithoutWildcard, IDN.USE_STD3_ASCII_RULES)
        }.getOrElse { throw IllegalArgumentException("Invalid pinned host", it) }
            .lowercase(Locale.US)
            .removeSuffix(".")
        require(ascii.isNotEmpty() && ascii.length <= 253 && !ascii.contains("..")) {
            "Invalid pinned host"
        }
        ascii.split('.').forEach { label ->
            require(label.isNotEmpty() && label.length <= 63) { "Invalid pinned host label" }
        }
        return if (wildcard) "*.$ascii" else ascii
    }

    private fun validateSha256Pin(pin: String): String {
        require(pin.startsWith("sha256/")) { "Only SHA-256 certificate pins are accepted" }
        val digest = runCatching { Base64.getDecoder().decode(pin.removePrefix("sha256/")) }
            .getOrElse { throw IllegalArgumentException("Malformed SHA-256 certificate pin", it) }
        require(digest.size == 32) { "SHA-256 certificate pin must encode exactly 32 bytes" }
        return pin
    }
}
