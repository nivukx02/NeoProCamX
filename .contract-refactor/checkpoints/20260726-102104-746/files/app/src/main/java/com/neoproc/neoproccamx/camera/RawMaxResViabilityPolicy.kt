package com.neoproc.neoproccamx.camera

/** Normalised device thermal level for RAW burst viability (mapped from the OS thermal label). */
internal enum class RawThermalLevel { OK, WARM, HOT, CRITICAL }

/**
 * Whether a maximum-resolution RAW burst can run right now.
 *
 * @property viable      safe to attempt a sustained max-res burst.
 * @property hardBlocked physically impossible (cannot hold even a single frame, or thermally critical);
 *                       even an explicit user request must fall back.
 * @property reason      human-readable explanation for logs/UI.
 */
internal data class RawMaxResViability(
    val viable: Boolean,
    val hardBlocked: Boolean,
    val reason: String
)

/**
 * Smart-viability gate for the maximum-sensor RAW burst.
 *
 * A max-res frame is huge (a 50MP 16-bit RAW ≈ 100 MB; 200MP ≈ 400 MB). Rather than attempt a burst
 * the device cannot complete — and hand back a broken/stuttering clip — this gate refuses when the
 * device is thermally stressed, low on storage, or low on memory.
 *
 * Two severities:
 *  - [RawMaxResViability.hardBlocked] — truly impossible (can't fit one frame, or thermal CRITICAL).
 *    Both AUTO and an explicit MAXIMUM_SENSOR request fall back.
 *  - soft (viable=false, hardBlocked=false) — risky but not impossible (HOT, tight headroom). AUTO
 *    falls back; an explicit MAXIMUM_SENSOR request is allowed through (power-user intent).
 *
 * Pure and Context-free.
 */
internal object RawMaxResViabilityPolicy {

    /** Frames of storage headroom we want before calling a sustained burst viable. */
    const val DEFAULT_MIN_BURST_FRAMES: Int = 8

    fun resolve(
        estPerFrameBytes: Long,
        freeStorageBytes: Long,
        availableMemBytes: Long,
        thermal: RawThermalLevel,
        readerDepth: Int = 4,
        minBurstFrames: Int = DEFAULT_MIN_BURST_FRAMES
    ): RawMaxResViability {
        val frameBytes = estPerFrameBytes.coerceAtLeast(1L)
        val depth = readerDepth.coerceAtLeast(1)
        val readerBytes = frameBytes * depth
        val burstBytes = frameBytes * minBurstFrames.coerceAtLeast(1)

        // A non-positive probe means the signal is unavailable (StatFs / MemoryInfo failure or a
        // pre-Q thermal read). Treat unknown as "do not block on this axis" so a failed probe never
        // wrongly forces the fallback.
        val storageKnown = freeStorageBytes > 0L
        val memKnown = availableMemBytes > 0L

        // ── Hard blocks — cannot proceed at all ──────────────────────────────────
        if (thermal == RawThermalLevel.CRITICAL) {
            return RawMaxResViability(false, hardBlocked = true, reason = "Device thermally critical")
        }
        if (memKnown && availableMemBytes < frameBytes) {
            return RawMaxResViability(
                false, hardBlocked = true,
                reason = "Insufficient memory for a single max-res RAW frame"
            )
        }
        if (storageKnown && freeStorageBytes < frameBytes) {
            return RawMaxResViability(
                false, hardBlocked = true,
                reason = "Insufficient storage for a single max-res RAW frame"
            )
        }

        // ── Soft blocks — risky but overridable by an explicit request ───────────
        if (memKnown && availableMemBytes < readerBytes) {
            return RawMaxResViability(
                false, hardBlocked = false,
                reason = "Low memory for a stable max-res burst queue"
            )
        }
        if (storageKnown && freeStorageBytes < burstBytes) {
            return RawMaxResViability(
                false, hardBlocked = false,
                reason = "Low storage for a sustained max-res burst"
            )
        }
        if (thermal == RawThermalLevel.HOT) {
            return RawMaxResViability(false, hardBlocked = false, reason = "Device running hot")
        }

        return RawMaxResViability(true, hardBlocked = false, reason = "Max-res burst viable")
    }

    /**
     * Maps an arbitrary OS thermal label to a normalised level (robust to OEM naming).
     *
     * Aligned with the codebase's existing RAW-throttle threshold (severity ≥ Severe): SEVERE is a
     * soft block ([RawThermalLevel.HOT]) that AUTO honours and an explicit request may override;
     * CRITICAL/EMERGENCY/SHUTDOWN are hard blocks. An unknown/pre-Q label ("Unknown") maps to
     * [RawThermalLevel.OK] so those devices are not wrongly gated.
     */
    fun thermalLevelFromLabel(label: String?): RawThermalLevel {
        val l = label?.uppercase().orEmpty()
        return when {
            l.contains("CRIT") || l.contains("EMERGENC") || l.contains("SHUTDOWN") -> RawThermalLevel.CRITICAL
            l.contains("SEVERE") || l.contains("HOT") || l.contains("THROTTL") -> RawThermalLevel.HOT
            l.contains("MODERATE") || l.contains("WARM") || l.contains("FAIR") ||
                l.contains("ELEVATED") || l.contains("LIGHT") -> RawThermalLevel.WARM
            else -> RawThermalLevel.OK
        }
    }
}
