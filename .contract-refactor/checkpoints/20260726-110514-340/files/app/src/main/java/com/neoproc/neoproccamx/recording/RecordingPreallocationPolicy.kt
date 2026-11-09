package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import java.io.File

/**
 * Storage policy shared by the processed NPV10 and sensor NPRAW private masters.
 *
 * Preallocation is deliberately bounded: processed video asks for 8 GiB, RAW asks for 10 GiB,
 * and both retain a safety reserve for Android and the user's other data. The reservation is an
 * optimisation, not a recording-duration limit; a healthy writer may grow beyond it.
 */
object RecordingPreallocationPolicy {
    const val BLOCK_BYTES = 4L * 1024L
    const val MIN_RESERVATION_BYTES = 256L * 1024L * 1024L
    const val MIN_SAFETY_RESERVE_BYTES = 2L * 1024L * 1024L * 1024L
    const val PROCESSED_TARGET_BYTES = 8L * 1024L * 1024L * 1024L
    const val RAW_TARGET_BYTES = 10L * 1024L * 1024L * 1024L
    const val HARD_MAX_BYTES = RAW_TARGET_BYTES

    data class Plan(
        val requestedBytes: Long,
        val availableBytes: Long,
        val safetyReserveBytes: Long,
        val reservationBytes: Long,
        val enabled: Boolean,
        val reason: String
    )

    fun processed(availableBytes: Long): Plan =
        resolve(availableBytes = availableBytes, requestedBytes = PROCESSED_TARGET_BYTES)

    fun raw(availableBytes: Long): Plan =
        resolve(availableBytes = availableBytes, requestedBytes = RAW_TARGET_BYTES)

    /** Resolve against the actual destination filesystem used by offline SDR/NDR/RAW→LOG develops. */
    @SuppressLint("UsableSpace")
    fun processedOutputFile(output: File): Plan {
        val directory = output.parentFile ?: output.absoluteFile.parentFile
        val availableBytes = runCatching { directory?.usableSpace ?: output.usableSpace }
            .getOrDefault(0L)
        return processed(availableBytes)
    }

    fun resolve(availableBytes: Long, requestedBytes: Long): Plan {
        val safeAvailable = availableBytes.coerceAtLeast(0L)
        val safeRequested = requestedBytes.coerceIn(0L, HARD_MAX_BYTES)
        val percentageReserve = safeAvailable / 10L
        val safetyReserve = maxOf(MIN_SAFETY_RESERVE_BYTES, percentageReserve)
            .coerceAtMost(safeAvailable)
        val reservable = (safeAvailable - safetyReserve).coerceAtLeast(0L)
        val reservation = alignDown(minOf(safeRequested, reservable), BLOCK_BYTES)
        val enabled = safeRequested > 0L && reservation >= MIN_RESERVATION_BYTES
        val reason = when {
            safeRequested <= 0L -> "disabled-request"
            !enabled -> "insufficient-space-after-safety-reserve"
            reservation < safeRequested -> "space-bounded"
            else -> "target-reserved"
        }
        return Plan(
            requestedBytes = safeRequested,
            availableBytes = safeAvailable,
            safetyReserveBytes = safetyReserve,
            reservationBytes = if (enabled) reservation else 0L,
            enabled = enabled,
            reason = reason
        )
    }

    private fun alignDown(value: Long, alignment: Long): Long =
        if (alignment <= 0L) value else value - value % alignment
}

enum class RecordingPreallocationMethod {
    NONE,
    POSIX_FALLOCATE,
    SPARSE_LENGTH_FALLBACK
}

data class RecordingPreallocationResult(
    val plan: RecordingPreallocationPolicy.Plan,
    val method: RecordingPreallocationMethod,
    val reservedBytes: Long,
    val failure: String? = null,
    /** Exact useful container bytes after finalisation (dummy reservation excluded). */
    val committedBytes: Long = 0L,
    /** True when close/abort removed unused preallocated tail bytes. */
    val reservationTrimmed: Boolean = false,
    /** True only after the committed length and container metadata reached fsync. */
    val synced: Boolean = false
)
