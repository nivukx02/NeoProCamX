package com.neoproc.neoproccamx.rendering

/**
 * s7-f11 / CP73 — pure resolver for the dedicated-transfer-queue zero-copy capability.
 *
 * Reads ONLY real evidence: the native `dedicatedTransferQueue=available|unavailable` and
 * `zeroCopyLiveImport=available|unavailable` capability tokens. Never synthesizes a pass — absent
 * or `unavailable` tokens mean zero-copy transfer is not ready regardless of the default request.
 *
 * [zeroCopyTransferReady] is true ONLY when the device has a dedicated transfer family, live AHB
 * import is available, and [Cp73ZeroCopyTransferFlag] is on — the gate value a future CP73 export
 * path would consult. The memcpy-removal itself is device-gated; this policy is the honest
 * capability report + gate.
 */
internal object Cp73TransferQueuePolicy {

    const val TOKEN_KEY: String = "dedicatedTransferQueue"
    const val AVAILABLE_VALUE: String = "available"
    const val UNAVAILABLE_VALUE: String = "unavailable"

    /** Boundary-safe parse of the native token (a leading substring cannot spoof it). PURE. */
    fun parseDedicatedTransferQueueAvailable(capabilitySummary: String): Boolean {
        var index = 0
        val key = "$TOKEN_KEY="
        while (true) {
            val found = capabilitySummary.indexOf(key, index)
            if (found < 0) return false
            val boundaryOk = found == 0 || capabilitySummary[found - 1] == ' '
            if (boundaryOk) {
                val valueStart = found + key.length
                val valueEnd = capabilitySummary.indexOf(' ', valueStart)
                    .let { if (it < 0) capabilitySummary.length else it }
                return capabilitySummary.substring(valueStart, valueEnd) == AVAILABLE_VALUE
            }
            index = found + key.length
        }
    }

    /**
     * True only when the default request is enabled, the device exposes a dedicated transfer family,
     * and the native live import path reports available. PURE.
     */
    fun zeroCopyTransferReady(capabilitySummary: String, flagEnabled: Boolean): Boolean =
        flagEnabled &&
            parseDedicatedTransferQueueAvailable(capabilitySummary) &&
            VulkanImportTelemetryResolver.parseZeroCopyLiveImportAvailable(capabilitySummary)
}
