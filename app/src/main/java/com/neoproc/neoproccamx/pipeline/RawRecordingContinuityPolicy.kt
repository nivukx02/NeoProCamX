package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy

/** Immutable storage/cadence limits for one bit-exact RAW recording session. */
internal data class RawRecordingStorageBudget(
    val framePacketBytes: Long,
    val targetFps: Int,
    val availableBytes: Long,
    val safetyReserveBytes: Long,
    val writableBytes: Long,
    val maximumFrames: Long,
    val maximumDurationMs: Long
)

internal enum class RawRecordingContinuityBoundaryReason {
    STORAGE_LIMIT,
    PHYSICAL_RESERVATION_LIMIT,
    BACKPRESSURE
}

/** Exact terminal boundary reported to the recording owner before any RAW frame is dropped. */
internal data class RawRecordingContinuityBoundary(
    val reason: RawRecordingContinuityBoundaryReason,
    val acceptedFrames: Long,
    val maximumFrames: Long,
    val framePacketBytes: Long,
    val acceptedFileBytes: Long,
    val writableBytes: Long,
    val projectedEndBytes: Long,
    val physicallyReservedBytes: Long,
    val queuedImages: Int,
    val activeImageOwners: Int
) {
    val message: String
        get() = when (reason) {
            RawRecordingContinuityBoundaryReason.STORAGE_LIMIT ->
                "safe storage-backed RAW duration reached"
            RawRecordingContinuityBoundaryReason.PHYSICAL_RESERVATION_LIMIT ->
                "next RAW extent was not physically reserved in time"
            RawRecordingContinuityBoundaryReason.BACKPRESSURE ->
                "storage throughput reached the protected RAW reader boundary"
        }
}

/**
 * Fail-closed continuity policy for the uncompressed NPRAW master.
 *
 * The policy never changes resolution, frame rate, bit depth, or sensor codes. Instead it chooses
 * an exact terminal frame before either the filesystem safety reserve or Camera2's protected RAW
 * reader budget can be consumed. The already-admitted prefix can then drain and publish with zero
 * gaps while the outer recording owner performs its normal stop transaction.
 */
internal object RawRecordingContinuityPolicy {
    private const val CONTAINER_HEADROOM_BYTES = 16L * 1024L * 1024L
    private const val PROTECTED_STOP_HEADROOM_FRAMES = 1

    fun resolveStorageBudget(
        availableBytes: Long,
        safetyReserveBytes: Long,
        framePacketBytes: Long,
        targetFps: Int
    ): RawRecordingStorageBudget {
        require(framePacketBytes > 0L) { "RAW frame packet bytes must be positive" }
        require(targetFps > 0) { "RAW target FPS must be positive" }
        val available = availableBytes.coerceAtLeast(0L)
        val reserve = safetyReserveBytes.coerceIn(0L, available)
        val safeBytes = (available - reserve).coerceAtLeast(0L)
        val writableBytes = (safeBytes - CONTAINER_HEADROOM_BYTES).coerceAtLeast(0L)
        val maximumFrames = writableBytes / framePacketBytes
        val maximumDurationMs = saturatingMultiply(maximumFrames, 1_000L) / targetFps.toLong()
        return RawRecordingStorageBudget(
            framePacketBytes = framePacketBytes,
            targetFps = targetFps,
            availableBytes = available,
            safetyReserveBytes = reserve,
            writableBytes = writableBytes,
            maximumFrames = maximumFrames,
            maximumDurationMs = maximumDurationMs
        )
    }

    fun resolveStorageBudget(
        plan: RecordingPreallocationPolicy.Plan,
        framePacketBytes: Long,
        targetFps: Int
    ): RawRecordingStorageBudget = resolveStorageBudget(
        availableBytes = plan.availableBytes,
        safetyReserveBytes = plan.safetyReserveBytes,
        framePacketBytes = framePacketBytes,
        targetFps = targetFps
    )

    /** The next sensor frame belongs outside this clip once the exact frame budget is consumed. */
    fun shouldStopBeforeNextFrame(acceptedFrames: Long, maximumFrames: Long): Boolean =
        maximumFrames <= 0L || acceptedFrames.coerceAtLeast(0L) >= maximumFrames

    /** Close admission immediately after accepting the final storage-backed frame. */
    fun shouldStopAfterAcceptedFrame(acceptedFrames: Long, maximumFrames: Long): Boolean =
        maximumFrames > 0L && acceptedFrames >= maximumFrames

    fun projectedEndBytes(acceptedFrames: Long, framePacketBytes: Long): Long {
        require(framePacketBytes > 0L) { "RAW frame packet bytes must be positive" }
        return saturatingMultiply(
            saturatingAdd(acceptedFrames.coerceAtLeast(0L), 1L),
            framePacketBytes
        )
    }

    /**
     * Exact-byte admission for variable-size, bit-exact RAW packets.
     *
     * [acceptedFileBytes] already includes packets accepted by the ordered storage engine. Only
     * Camera2 Images that have not reached that engine remain unknown, so reserve their complete
     * worst-case packet capacity plus the incoming frame. This preserves the filesystem safety
     * reserve without multiplying every previously compressed frame by its worst-case capacity.
     */
    fun projectedAdaptiveEndBytes(
        acceptedFileBytes: Long,
        pendingUnencodedFrames: Int,
        framePacketBytes: Long
    ): Long {
        require(framePacketBytes > 0L) { "RAW frame packet bytes must be positive" }
        val pendingWithIncoming = saturatingAdd(
            pendingUnencodedFrames.coerceAtLeast(0).toLong(),
            1L
        )
        return saturatingAdd(
            acceptedFileBytes.coerceAtLeast(0L),
            saturatingMultiply(pendingWithIncoming, framePacketBytes)
        )
    }

    fun shouldStopBeforeAdaptivePacket(
        projectedEndBytes: Long,
        writableBytes: Long
    ): Boolean = writableBytes <= 0L || projectedEndBytes > writableBytes

    /** Conservative telemetry ceiling from the current byte position; never used to admit data. */
    fun conservativeMaximumFramesFromCurrentBytes(
        acceptedFrames: Long,
        acceptedFileBytes: Long,
        writableBytes: Long,
        framePacketBytes: Long
    ): Long {
        require(framePacketBytes > 0L) { "RAW frame packet bytes must be positive" }
        val remainingBytes = (
            writableBytes.coerceAtLeast(0L) - acceptedFileBytes.coerceAtLeast(0L)
        ).coerceAtLeast(0L)
        return saturatingAdd(
            acceptedFrames.coerceAtLeast(0L),
            remainingBytes / framePacketBytes
        )
    }

    /**
     * Preserve one protected ImageReader slot. Crossing this boundary risks rejecting the next
     * sensor frame, so a strict master ends at the current accepted frame instead.
     */
    fun shouldStopForBackpressure(
        readerMaxImages: Int,
        queuedImages: Int,
        activeImageOwners: Int
    ): Boolean {
        val budget = RawVideoBackpressurePolicy.maxRetainedOpenImages(readerMaxImages)
        val retained = saturatingAdd(
            queuedImages.coerceAtLeast(0).toLong(),
            activeImageOwners.coerceAtLeast(0).toLong()
        )
        val stopBoundary = (budget - PROTECTED_STOP_HEADROOM_FRAMES).coerceAtLeast(1)
        return retained >= stopBoundary.toLong()
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
