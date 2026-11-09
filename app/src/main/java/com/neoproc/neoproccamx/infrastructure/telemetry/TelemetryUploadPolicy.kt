package com.neoproc.neoproccamx.infrastructure.telemetry

import java.net.URI
import java.util.Locale

/** Fail-closed authorization for telemetry leaving the device. */
internal object TelemetryUploadPolicy {
    fun authorizedHttpsEndpoint(
        endpoint: String?,
        hasUserConsent: Boolean,
        pinnedHosts: Set<String>
    ): String? {
        if (!hasUserConsent) return null
        val candidate = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.fragment != null) return null
        val normalizedHost = uri.host.lowercase(Locale.US).removeSuffix(".")
        if (pinnedHosts.none { pinHostMatches(it, normalizedHost) }) return null
        return uri.toASCIIString()
    }

    private fun pinHostMatches(pinHost: String, endpointHost: String): Boolean {
        val normalizedPin = pinHost.lowercase(Locale.US).removeSuffix(".")
        if (!normalizedPin.startsWith("*.")) return normalizedPin == endpointHost
        val suffix = normalizedPin.removePrefix("*.")
        if (!endpointHost.endsWith(".$suffix")) return false
        val wildcardLabel = endpointHost.removeSuffix(".$suffix")
        return wildcardLabel.isNotEmpty() && !wildcardLabel.contains('.')
    }
}
