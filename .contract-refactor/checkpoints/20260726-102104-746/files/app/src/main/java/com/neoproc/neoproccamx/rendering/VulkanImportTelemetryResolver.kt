package com.neoproc.neoproccamx.rendering

/**
 * s7-f12 / CP9B — truthful import / zero-copy telemetry resolver.
 *
 * Single source of truth for the gate-facing zero-copy + fallback verdict. It reads ONLY real
 * evidence:
 *   - the native capability-summary token `zeroCopyLiveImport=available|unavailable` (the one and
 *     only producer is vulkan_bridge.cpp; the manager already folds it into
 *     [VulkanRuntimeManager]'s capability summary via combinedCapabilitySummary()), and
 *   - the live [VulkanImportStats] counters.
 *
 * It NEVER synthesizes a pass: an absent or `unavailable` token yields [Verdict.zeroCopyAvailable]
 * == false, and a fallback is advised purely from observed consecutive failures. This keeps the
 * OmegaCapabilityGate / PixelProcessingRealityPolicy honest — the verdict mirrors what the native
 * path actually did.
 *
 * No-downgrade: with the token absent (today's default) and zero failures the verdict is
 * { zeroCopyAvailable=false, zeroCopyProven=false, fallbackAdvised=false } — it adds no new claim.
 */
internal object VulkanImportTelemetryResolver {

    /** Token the native bridge emits in its capability summary (vulkan_bridge.cpp). */
    const val ZERO_COPY_TOKEN_KEY: String = "zeroCopyLiveImport"
    const val ZERO_COPY_AVAILABLE_VALUE: String = "available"
    const val ZERO_COPY_UNAVAILABLE_VALUE: String = "unavailable"

    /**
     * Consecutive-failure count at or above which the caller should degrade to a raw-passthrough
     * feed. Matches the existing pipeline-health intent (VulkanRuntimeManager.isPipelineHealthy
     * uses 10) so the verdict never disagrees with the shipping health signal.
     */
    const val FALLBACK_CONSECUTIVE_FAILURE_THRESHOLD: Long = 10L

    data class Verdict(
        /** True only when the native token explicitly says `available`. */
        val zeroCopyAvailable: Boolean,
        /**
         * True only when zero-copy is available AND the live import path has actually imported at
         * least one frame this session with frame-import enabled. Impossible-to-fake from counters
         * alone; this is the value a gate may treat as "proven".
         */
        val zeroCopyProven: Boolean,
        /** Caller should fall back to raw passthrough (no black preview) when true. */
        val fallbackAdvised: Boolean
    )

    /** Parse the real native token out of the combined capability summary. PURE. */
    fun parseZeroCopyLiveImportAvailable(capabilitySummary: String): Boolean {
        var index = 0
        val key = ZERO_COPY_TOKEN_KEY + '='
        while (true) {
            val found = capabilitySummary.indexOf(key, index)
            if (found < 0) return false
            // Require a token boundary before the key so a substring match cannot spoof it.
            val boundaryOk = found == 0 || capabilitySummary[found - 1] == ' '
            if (boundaryOk) {
                val valueStart = found + key.length
                val valueEnd = capabilitySummary.indexOf(' ', valueStart)
                    .let { if (it < 0) capabilitySummary.length else it }
                return capabilitySummary.substring(valueStart, valueEnd) == ZERO_COPY_AVAILABLE_VALUE
            }
            index = found + key.length
        }
    }

    fun resolve(
        capabilitySummary: String,
        stats: VulkanImportStats,
        fallbackThreshold: Long = FALLBACK_CONSECUTIVE_FAILURE_THRESHOLD
    ): Verdict {
        val zeroCopyAvailable = parseZeroCopyLiveImportAvailable(capabilitySummary)
        val zeroCopyProven = zeroCopyAvailable &&
            stats.frameImportEnabled &&
            stats.sessionImportedFrames > 0L
        val fallbackAdvised = stats.frameImportEnabled &&
            stats.consecutiveFailedImports >= fallbackThreshold
        return Verdict(
            zeroCopyAvailable = zeroCopyAvailable,
            zeroCopyProven = zeroCopyProven,
            fallbackAdvised = fallbackAdvised
        )
    }
}
