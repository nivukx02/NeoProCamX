package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import com.neoproc.neoproccamx.recording.ResolvedRecordingSelection

/**
 * Immutable identity of every value that can affect processed-video resolution selection.
 *
 * RAW uses a different stream family and is deliberately excluded from this cache. Keeping its
 * entry separate lets a RAW -> Video/Pro transition reuse the last *exact* processed selection,
 * while camera, encoder, aspect, cadence, bit-depth or quality-policy changes produce a miss.
 */
internal data class ProcessedRecordingSelectionFingerprint(
    val cameraId: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val preferredCodec: GpuVideoCodec,
    val availableSizeSignature: List<Long>,
    val pixelArraySizeSignature: Long?,
    val maximumRawSizeSignature: Long?,
    val fallbackSensorSizeSignature: Long?,
    val uiMode: AppMode,
    val cinemaMode: CinemaVideoMode,
    val rawEnabled: Boolean,
    val selectedOutputAspectRatioBits: Int?,
    val explicitResolutionSelection: Boolean,
    val rawSupported: Boolean,
    val av1Supported: Boolean,
    val av1CapabilityResolved: Boolean,
    val cachedTenBitSupport: Boolean?,
    val hdrRequested: Boolean,
    val tenBitRequested: Boolean,
    val fps: Int,
    val highEndEnabled: Boolean,
    val strictHighEndOnly: Boolean,
    val fullSensorForced: Boolean,
    val deviceBuildFingerprint: String
) {
    companion object {
        fun sizeSignature(width: Int, height: Int): Long {
            val longEdge = maxOf(width, height).coerceAtLeast(1)
            val shortEdge = minOf(width, height).coerceAtLeast(1)
            return (longEdge.toLong() shl 32) or (shortEdge.toLong() and 0xffffffffL)
        }
    }
}

/**
 * Single-entry, generation-safe cache for the hot mode-switch path.
 *
 * Callers may populate the cache during normal configuration, but may only request reuse while a
 * mode-switch transaction is active. A RAW lookup never evicts the processed entry. A result is
 * cacheable only when both camera and encoder accepted it exactly and no upscale was introduced.
 */
internal class ProcessedRecordingSelectionCache {
    internal data class Lookup(
        val selection: ResolvedRecordingSelection,
        val cacheHit: Boolean
    )

    private data class Entry(
        val fingerprint: ProcessedRecordingSelectionFingerprint,
        val selection: ResolvedRecordingSelection
    )

    private val lock = Any()
    private var requestGeneration = 0L
    private var entry: Entry? = null

    fun resolve(
        fingerprint: ProcessedRecordingSelectionFingerprint,
        processedMode: Boolean,
        reuseAllowed: Boolean,
        resolver: () -> ResolvedRecordingSelection
    ): Lookup {
        if (!processedMode) {
            synchronized(lock) { advanceGeneration() }
            return Lookup(selection = resolver(), cacheHit = false)
        }

        val generation: Long
        synchronized(lock) {
            generation = advanceGeneration()
            if (reuseAllowed) {
                entry
                    ?.takeIf { it.fingerprint == fingerprint }
                    ?.selection
                    ?.takeIf(::isExact)
                    ?.let { return Lookup(selection = it, cacheHit = true) }
            }
        }

        val resolved = resolver()
        if (isExact(resolved)) {
            synchronized(lock) {
                // Never let an older resolver completion replace a newer capability/request result.
                if (generation == requestGeneration) {
                    entry = Entry(fingerprint = fingerprint, selection = resolved)
                }
            }
        }
        return Lookup(selection = resolved, cacheHit = false)
    }

    internal fun clear() = synchronized(lock) {
        advanceGeneration()
        entry = null
    }

    private fun advanceGeneration(): Long {
        requestGeneration = if (requestGeneration == Long.MAX_VALUE) 1L else requestGeneration + 1L
        return requestGeneration
    }

    private fun isExact(selection: ResolvedRecordingSelection): Boolean =
        selection.cameraExactMatch &&
            selection.encoderExactMatch &&
            !selection.upscaledToRequested
}
