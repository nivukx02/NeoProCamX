package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import java.io.File

/**
 * Storage policy shared by the processed NPV10 and sensor NPRAW private masters.
 *
 * Preallocation is deliberately bounded per allocation operation: MOV output and RAW ask for
 * 10 GiB, and both retain a safety reserve for Android and the user's other data. MOV writers may
 * extend that reservation in additional 10 GiB ranges before the current extent is consumed. The
 * reservation is an optimisation, not a recording-duration limit.
 */
object RecordingPreallocationPolicy {
    const val BLOCK_BYTES = 4L * 1024L
    const val MIN_RESERVATION_BYTES = 256L * 1024L * 1024L
    const val MIN_SAFETY_RESERVE_BYTES = 2L * 1024L * 1024L * 1024L
    const val PROCESSED_TARGET_BYTES = 10L * 1024L * 1024L * 1024L
    const val RAW_TARGET_BYTES = 10L * 1024L * 1024L * 1024L
    const val HARD_MAX_BYTES = RAW_TARGET_BYTES
    const val MOV_ROLLING_EXTENSION_BYTES = 10L * 1024L * 1024L * 1024L
    const val MOV_ROLLING_LEAD_BYTES = 1L * 1024L * 1024L * 1024L
    const val RAW_ROLLING_EXTENSION_BYTES = 10L * 1024L * 1024L * 1024L
    /**
     * Allocate each logical 10 GiB RAW extension in short physical slices. A single large
     * posix_fallocate call can monopolize the storage device long enough to exhaust Camera2's
     * lossless RAW ownership window under thermal pressure. Slicing changes only allocation
     * scheduling; the same bytes are reserved and the recorded payload remains bit-exact.
     */
    // The logical extension remains exactly 10 GiB; only its background transaction granularity
    // changes, reducing each storage-bus monopolisation window while Camera2 and pwrite remain active.
    const val RAW_ROLLING_ALLOCATION_SLICE_BYTES = 128L * 1024L * 1024L
    // Full-active-array RAW16 at 4K25 consumes about 600 MiB/s. Starting the next physical
    // reservation with four GiB left gives the allocator more than six seconds of lead without
    // changing the recorded raster, cadence, precision, or payload bytes.
    const val RAW_ROLLING_LEAD_BYTES = 4L * 1024L * 1024L * 1024L
    /** Metadata/sample-table headroom added to an exact offline-conversion payload estimate. */
    const val OFFLINE_CONTAINER_HEADROOM_BYTES = 16L * 1024L * 1024L

    data class Plan(
        val requestedBytes: Long,
        val availableBytes: Long,
        val safetyReserveBytes: Long,
        val reservationBytes: Long,
        val enabled: Boolean,
        val reason: String,
        /** Zero disables rolling growth. Non-zero values are per-operation, not a total file cap. */
        val rollingExtensionBytes: Long = 0L,
        val rollingLeadBytes: Long = 0L
    )

    data class RollingPlan(
        val currentReservedBytes: Long,
        val requestedGrowthBytes: Long,
        val availableBytesIncludingCurrentReservation: Long,
        val safetyReserveBytes: Long,
        val targetReservedBytes: Long,
        val growthBytes: Long,
        val fullyCoversRequest: Boolean,
        val reason: String
    )

    fun processed(availableBytes: Long): Plan =
        resolve(availableBytes = availableBytes, requestedBytes = PROCESSED_TARGET_BYTES)
            .withMovRollingReservation()

    fun raw(availableBytes: Long): Plan =
        resolve(availableBytes = availableBytes, requestedBytes = RAW_TARGET_BYTES)
            .withRawRollingReservation()

    /** Resolve against the actual destination filesystem used by offline SDR/NDR/RAW→LOG develops. */
    @SuppressLint("UsableSpace")
    fun processedOutputFile(output: File): Plan {
        return processed(destinationAvailableBytes(output))
    }

    /**
     * Size-aware reservation for an offline conversion whose uncompressed payload size is known.
     *
     * A fixed 10 GiB `posix_fallocate` is appropriate for an open-ended live recording, but it can block
     * an otherwise tiny offline conversion for minutes on FUSE/scoped-storage volumes. Offline MOV writers
     * know their frame payload size and (normally) the source sample count, so reserve exactly that payload
     * plus bounded container/table headroom. Unknown-size/compressed conversions retain the established
     * 10 GiB policy. This changes allocation latency only: writers may still grow normally and no image,
     * bit-depth, bitrate, colour, timing, or metadata policy is changed.
     */
    fun offlineConversionOutput(output: File, estimatedPayloadBytes: Long?): Plan =
        offlineConversion(
            availableBytes = destinationAvailableBytes(output),
            estimatedPayloadBytes = estimatedPayloadBytes
        )

    internal fun offlineConversion(availableBytes: Long, estimatedPayloadBytes: Long?): Plan {
        val requestedBytes = estimatedPayloadBytes
            ?.takeIf { it > 0L }
            ?.let { payload ->
                try {
                    Math.addExact(payload, OFFLINE_CONTAINER_HEADROOM_BYTES)
                } catch (_: ArithmeticException) {
                    HARD_MAX_BYTES
                }
            }
            ?: PROCESSED_TARGET_BYTES
        return resolve(availableBytes = availableBytes, requestedBytes = requestedBytes)
            .withMovRollingReservation()
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

    /**
     * Resolves the next physical MOV extent without consuming Android's/user's safety reserve.
     * [availableBytes] is the free space reported after the existing file reservation, so the
     * current reservation is added back exactly once before calculating the total safe ceiling.
     */
    fun rollingMovExtension(
        availableBytes: Long,
        currentReservedBytes: Long,
        requestedGrowthBytes: Long = MOV_ROLLING_EXTENSION_BYTES
    ): RollingPlan {
        val current = currentReservedBytes.coerceAtLeast(0L)
        val requestedGrowth = requestedGrowthBytes.coerceIn(0L, HARD_MAX_BYTES)
        val availableIncludingCurrent = saturatingAdd(availableBytes.coerceAtLeast(0L), current)
        val safetyReserve = maxOf(
            MIN_SAFETY_RESERVE_BYTES,
            availableIncludingCurrent / 10L
        ).coerceAtMost(availableIncludingCurrent)
        val maximumSafeTarget = alignDown(
            (availableIncludingCurrent - safetyReserve).coerceAtLeast(0L),
            BLOCK_BYTES
        ).coerceAtLeast(current)
        val requestedTarget = saturatingAdd(current, requestedGrowth)
        val target = alignDown(minOf(requestedTarget, maximumSafeTarget), BLOCK_BYTES)
            .coerceAtLeast(current)
        val growth = (target - current).coerceAtLeast(0L)
        val fullyCovered = growth == requestedGrowth && requestedGrowth > 0L
        val reason = when {
            requestedGrowth <= 0L -> "rolling-disabled-request"
            growth < MIN_RESERVATION_BYTES -> "rolling-insufficient-space-after-safety-reserve"
            fullyCovered -> "rolling-target-reserved"
            else -> "rolling-space-bounded"
        }
        return RollingPlan(
            currentReservedBytes = current,
            requestedGrowthBytes = requestedGrowth,
            availableBytesIncludingCurrentReservation = availableIncludingCurrent,
            safetyReserveBytes = safetyReserve,
            targetReservedBytes = if (growth >= MIN_RESERVATION_BYTES) target else current,
            growthBytes = if (growth >= MIN_RESERVATION_BYTES) growth else 0L,
            fullyCoversRequest = fullyCovered,
            reason = reason
        )
    }

    /** Resolve the next bit-exact NPRAW extent while preserving the filesystem safety reserve. */
    fun rollingRawExtension(
        availableBytes: Long,
        currentReservedBytes: Long,
        requestedGrowthBytes: Long = RAW_ROLLING_EXTENSION_BYTES
    ): RollingPlan = rollingMovExtension(
        availableBytes = availableBytes,
        currentReservedBytes = currentReservedBytes,
        requestedGrowthBytes = requestedGrowthBytes
    )

    fun shouldScheduleMovGrowth(
        requiredEndBytes: Long,
        reservedBytes: Long,
        leadBytes: Long
    ): Boolean =
        reservedBytes > 0L &&
            (reservedBytes - requiredEndBytes.coerceAtLeast(0L)).coerceAtLeast(0L) <=
            leadBytes.coerceAtLeast(0L)

    fun shouldScheduleRawGrowth(
        requiredEndBytes: Long,
        reservedBytes: Long,
        leadBytes: Long = RAW_ROLLING_LEAD_BYTES
    ): Boolean = shouldScheduleMovGrowth(requiredEndBytes, reservedBytes, leadBytes)

    fun nextRawAllocationSliceBytes(remainingGrowthBytes: Long): Long =
        remainingGrowthBytes.coerceAtLeast(0L).coerceAtMost(RAW_ROLLING_ALLOCATION_SLICE_BYTES)

    private fun Plan.withMovRollingReservation(): Plan = copy(
        rollingExtensionBytes = MOV_ROLLING_EXTENSION_BYTES,
        rollingLeadBytes = MOV_ROLLING_LEAD_BYTES
    )

    private fun Plan.withRawRollingReservation(): Plan = copy(
        rollingExtensionBytes = RAW_ROLLING_EXTENSION_BYTES,
        rollingLeadBytes = RAW_ROLLING_LEAD_BYTES
    )

    private fun alignDown(value: Long, alignment: Long): Long =
        if (alignment <= 0L) value else value - value % alignment

    @SuppressLint("UsableSpace")
    internal fun destinationAvailableBytes(output: File): Long {
        val directory = output.parentFile ?: output.absoluteFile.parentFile
        // A direct MediaStore target is represented as /proc/self/fd/<fd>. Its parent is procfs (zero
        // usable bytes), but statvfs on the fd symlink resolves the real destination filesystem. Prefer
        // the file's value when it exists; ordinary not-yet-created cache files retain the directory path.
        val outputBytes = runCatching { if (output.exists()) output.usableSpace else 0L }.getOrDefault(0L)
        val directoryBytes = runCatching { directory?.usableSpace ?: 0L }.getOrDefault(0L)
        return maxOf(outputBytes, directoryBytes)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
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
    /** Physical bytes reserved by the initial allocation operation before any rolling growth. */
    val initialReservedBytes: Long = reservedBytes,
    /** Number of successfully completed rolling allocation operations. */
    val rollingExtensionCount: Long = 0L,
    /** Sum of physical bytes added by successful rolling allocation operations. */
    val rollingExtendedBytes: Long = 0L,
    /** Exact useful container bytes after finalisation (dummy reservation excluded). */
    val committedBytes: Long = 0L,
    /** True when close/abort removed unused preallocated tail bytes. */
    val reservationTrimmed: Boolean = false,
    /** True only after the committed length and container metadata reached fsync. */
    val synced: Boolean = false
)

/**
 * Durable MOV reservation facts written into each recording quality certificate.
 *
 * Logcat is intentionally not the source of truth: vendor log quotas can evict the allocation line
 * during a long 4K recording. The certificate is fsynced after the MOV writer has closed, so these
 * fields describe the exact finalized recording transaction.
 */
internal fun RecordingPreallocationResult.toMovCertificateMetadata(): Map<String, String> = mapOf(
    "movPhysicalReservationEnabled" to plan.enabled.toString(),
    "movPhysicalReservationMethod" to method.name,
    "movPhysicalReservationRequestedBytes" to plan.requestedBytes.toString(),
    "movPhysicalReservationInitialReservedBytes" to initialReservedBytes.toString(),
    "movPhysicalReservationCurrentReservedBytes" to reservedBytes.toString(),
    "movPhysicalReservationRollingLeadBytes" to plan.rollingLeadBytes.toString(),
    "movPhysicalReservationRollingExtensionBytes" to plan.rollingExtensionBytes.toString(),
    "movPhysicalReservationExtensionSuccesses" to rollingExtensionCount.toString(),
    "movPhysicalReservationTotalExtendedBytes" to rollingExtendedBytes.toString(),
    "movPhysicalReservationCommittedBytes" to committedBytes.toString(),
    "movPhysicalReservationTrimmed" to reservationTrimmed.toString(),
    "movPhysicalReservationSynced" to synced.toString(),
    "movPhysicalReservationFailure" to failure.orEmpty()
)
