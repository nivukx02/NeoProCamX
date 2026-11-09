package com.neoproc.neoproccamx.security

object BackendPinningConfig {
    fun parse(rawConfig: String): Map<String, List<String>> {
        if (rawConfig.isBlank()) return emptyMap()
        val parsed = LinkedHashMap<String, List<String>>()
        rawConfig.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .forEach { entry ->
                val host = entry.substringBefore('=').trim()
                val pins = entry.substringAfter('=')
                    .split(',')
                    .map { it.trim() }
                    .filter { it.startsWith("sha256/") }
                if (host.isNotEmpty() && pins.isNotEmpty()) {
                    parsed[host] = pins
                }
            }
        return parsed
    }
}
