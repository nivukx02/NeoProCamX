package com.neoproc.neoproccamx.recording

/** Immutable byte budget for one selected NPV10 stream. */
data class Npv10PhysicalReservationProfile(
    val frameRecordBytes: Long,
    val bytesPerSecond: Long,
    val initialCoverageBytes: Long,
    val rollingLeadBytes: Long,
    val rollingExtensionBytes: Long
)

/** One fail-closed physical-allocation decision. */
data class Npv10PhysicalReservationPlan(
    val profile: Npv10PhysicalReservationProfile,
    val availableBytesIncludingCurrentReservation: Long,
    val safetyReserveBytes: Long,
    val maxReservableBytes: Long,
    val currentReservedBytes: Long,
    val requestedTargetBytes: Long,
    val targetReservedBytes: Long,
    val fullyCoversRequest: Boolean,
    val reason: String
) {
    val growthBytes: Long
        get() = (targetReservedBytes - currentReservedBytes).coerceAtLeast(0L)

    val enabled: Boolean
        get() = targetReservedBytes >= RecordingPreallocationPolicy.MIN_RESERVATION_BYTES

    fun toRecordingPlan(): RecordingPreallocationPolicy.Plan =
        RecordingPreallocationPolicy.Plan(
            requestedBytes = requestedTargetBytes,
            availableBytes = availableBytesIncludingCurrentReservation,
            safetyReserveBytes = safetyReserveBytes,
            reservationBytes = if (enabled) targetReservedBytes else 0L,
            enabled = enabled,
            reason = reason
        )
}

/** Runtime evidence for reservation coverage; all byte counts describe physical extents. */
data class Npv10PhysicalReservationTelemetry(
    val enabled: Boolean = false,
    val frameRecordBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val initialCoverageRequiredBytes: Long = 0L,
    val initialReservedBytes: Long = 0L,
    val currentReservedBytes: Long = 0L,
    val rollingLeadBytes: Long = 0L,
    val rollingExtensionBytes: Long = 0L,
    val extensionAttempts: Long = 0L,
    val extensionSuccesses: Long = 0L,
    val extensionFailures: Long = 0L,
    val totalExtendedBytes: Long = 0L,
    val minimumHeadroomBytes: Long = 0L,
    val unreservedWriteRefusals: Long = 0L,
    val lastFailure: String = "",
    val allocationSliceLimitBytes: Long = 0L,
    val allocationSlices: Long = 0L,
    val maximumAllocationSliceNs: Long = 0L
) {
    /**
     * Stable, flat metadata written into the recording quality certificate.
     *
     * Reservation proof must survive logd rotation and process death. Keeping this conversion next
     * to the telemetry model also prevents the runtime writer and the device-test oracle from
     * silently disagreeing about field names or byte semantics.
     */
    fun toEvidenceMetadata(): Map<String, String> = linkedMapOf(
        "npv10PhysicalReservationEnabled" to enabled.toString(),
        "npv10PhysicalReservationFrameRecordBytes" to frameRecordBytes.toString(),
        "npv10PhysicalReservationBytesPerSecond" to bytesPerSecond.toString(),
        "npv10PhysicalReservationInitialCoverageRequiredBytes" to
            initialCoverageRequiredBytes.toString(),
        "npv10PhysicalReservationInitialReservedBytes" to initialReservedBytes.toString(),
        "npv10PhysicalReservationCurrentReservedBytes" to currentReservedBytes.toString(),
        "npv10PhysicalReservationRollingLeadBytes" to rollingLeadBytes.toString(),
        "npv10PhysicalReservationRollingExtensionBytes" to rollingExtensionBytes.toString(),
        "npv10PhysicalReservationExtensionAttempts" to extensionAttempts.toString(),
        "npv10PhysicalReservationExtensionSuccesses" to extensionSuccesses.toString(),
        "npv10PhysicalReservationExtensionFailures" to extensionFailures.toString(),
        "npv10PhysicalReservationTotalExtendedBytes" to totalExtendedBytes.toString(),
        "npv10PhysicalReservationMinimumHeadroomBytes" to minimumHeadroomBytes.toString(),
        "npv10PhysicalReservationUnreservedWriteRefusals" to unreservedWriteRefusals.toString(),
        "npv10PhysicalReservationLastFailure" to lastFailure,
        "npv10PhysicalReservationAllocationSliceLimitBytes" to allocationSliceLimitBytes.toString(),
        "npv10PhysicalReservationAllocationSlices" to allocationSlices.toString(),
        "npv10PhysicalReservationMaxAllocationSliceNs" to maximumAllocationSliceNs.toString()
    )
}

/**
 * NPV10-specific reservation math.
 *
 * Both the direct-gallery MOV writer and this lossless private-master writer use the same rolling
 * storage contract: reserve 10 GiB, start the next reservation while one GiB remains, and continue
 * in additional 10 GiB extents for as long as the destination can preserve its safety reserve.
 * Growth runs on the writer's maintenance executor; frame writes remain on the ordered writer lane.
 */
object Npv10PhysicalReservationPolicy {
    const val INITIAL_RESERVATION_BYTES = RecordingPreallocationPolicy.PROCESSED_TARGET_BYTES
    const val ROLLING_LEAD_BYTES = RecordingPreallocationPolicy.MOV_ROLLING_LEAD_BYTES
    const val ROLLING_EXTENSION_BYTES = RecordingPreallocationPolicy.MOV_ROLLING_EXTENSION_BYTES
    const val FRAME_HEADER_BYTES = 4L + Long.SIZE_BYTES + Int.SIZE_BYTES * 4L
    const val FILE_HEADER_ALLOWANCE_BYTES = 1L * 1024L * 1024L

    fun profile(config: Npv10SessionConfig): Npv10PhysicalReservationProfile =
        profile(
            width = config.width,
            height = config.height,
            fps = config.fps,
            payloadFormat = config.payloadFormat,
            packetCodec = config.packetCodec
        )

    fun profile(
        width: Int,
        height: Int,
        fps: Int,
        payloadFormat: Npv10PayloadFormat,
        packetCodec: Npv10PacketCodec
    ): Npv10PhysicalReservationProfile {
        require(width > 0 && height > 0) { "NPV10 reservation dimensions must be positive" }
        require(fps > 0) { "NPV10 reservation FPS must be positive" }
        val storedPayloadBytes = Npv10ContainerPolicy.estimatedStoredBytesPerFrame(
            width = width,
            height = height,
            payloadFormat = payloadFormat,
            packetCodec = packetCodec
        )
        val frameRecordBytes = saturatingAdd(storedPayloadBytes, FRAME_HEADER_BYTES)
        val bytesPerSecond = saturatingMultiply(frameRecordBytes, fps.toLong())
        return Npv10PhysicalReservationProfile(
            frameRecordBytes = frameRecordBytes,
            bytesPerSecond = bytesPerSecond,
            initialCoverageBytes = alignUp(
                maxOf(
                    INITIAL_RESERVATION_BYTES,
                    saturatingAdd(frameRecordBytes, FILE_HEADER_ALLOWANCE_BYTES)
                )
            ),
            rollingLeadBytes = alignUp(
                maxOf(frameRecordBytes, ROLLING_LEAD_BYTES)
            ),
            rollingExtensionBytes = alignUp(
                maxOf(frameRecordBytes, ROLLING_EXTENSION_BYTES)
            )
        )
    }

    fun initialPlan(
        profile: Npv10PhysicalReservationProfile,
        availableBytesIncludingCurrentReservation: Long,
        currentReservedBytes: Long
    ): Npv10PhysicalReservationPlan = resolve(
        profile = profile,
        availableBytesIncludingCurrentReservation = availableBytesIncludingCurrentReservation,
        currentReservedBytes = currentReservedBytes,
        requestedTargetBytes = maxOf(currentReservedBytes, profile.initialCoverageBytes),
        operation = "initial-10gib"
    )

    fun rollingPlan(
        profile: Npv10PhysicalReservationProfile,
        availableBytesIncludingCurrentReservation: Long,
        currentReservedBytes: Long
    ): Npv10PhysicalReservationPlan = resolve(
        profile = profile,
        availableBytesIncludingCurrentReservation = availableBytesIncludingCurrentReservation,
        currentReservedBytes = currentReservedBytes,
        requestedTargetBytes = saturatingAdd(currentReservedBytes, profile.rollingExtensionBytes),
        operation = "rolling-10gib"
    )

    fun shouldScheduleGrowth(
        committedBytes: Long,
        reservedBytes: Long,
        rollingLeadBytes: Long
    ): Boolean =
        reservedBytes > 0L &&
            (reservedBytes - committedBytes.coerceAtLeast(0L)).coerceAtLeast(0L) <=
            rollingLeadBytes.coerceAtLeast(0L)

    fun isWritePhysicallyCovered(requiredEndBytes: Long, reservedBytes: Long): Boolean =
        requiredEndBytes >= 0L && requiredEndBytes <= reservedBytes.coerceAtLeast(0L)

    fun availableIncludingReservation(usableBytes: Long, reservedBytes: Long): Long =
        saturatingAdd(usableBytes.coerceAtLeast(0L), reservedBytes.coerceAtLeast(0L))

    private fun resolve(
        profile: Npv10PhysicalReservationProfile,
        availableBytesIncludingCurrentReservation: Long,
        currentReservedBytes: Long,
        requestedTargetBytes: Long,
        operation: String
    ): Npv10PhysicalReservationPlan {
        val available = availableBytesIncludingCurrentReservation.coerceAtLeast(0L)
        val current = currentReservedBytes.coerceAtLeast(0L)
        val requested = alignUp(maxOf(current, requestedTargetBytes.coerceAtLeast(0L)))
        val percentageReserve = available / 10L
        val safetyReserve = maxOf(
            RecordingPreallocationPolicy.MIN_SAFETY_RESERVE_BYTES,
            percentageReserve
        ).coerceAtMost(available)
        val storageBound = alignDown((available - safetyReserve).coerceAtLeast(0L))
        // Existing physical blocks cannot be unallocated by a growth decision. They remain truthful
        // even if another process consumed free space after the original reservation completed.
        val maxReservable = maxOf(current, storageBound)
        val target = minOf(requested, maxReservable).coerceAtLeast(current)
        val fullyCovered = target >= requested
        val reason = when {
            target < RecordingPreallocationPolicy.MIN_RESERVATION_BYTES ->
                "$operation-insufficient-space-after-safety-reserve"
            fullyCovered -> "$operation-target-reserved"
            target > current -> "$operation-space-bounded-growth"
            else -> "$operation-no-safe-growth"
        }
        return Npv10PhysicalReservationPlan(
            profile = profile,
            availableBytesIncludingCurrentReservation = available,
            safetyReserveBytes = safetyReserve,
            maxReservableBytes = maxReservable,
            currentReservedBytes = current,
            requestedTargetBytes = requested,
            targetReservedBytes = target,
            fullyCoversRequest = fullyCovered,
            reason = reason
        )
    }

    private fun alignUp(value: Long): Long {
        val alignment = RecordingPreallocationPolicy.BLOCK_BYTES
        if (value <= 0L || alignment <= 1L) return value.coerceAtLeast(0L)
        val remainder = value % alignment
        if (remainder == 0L) return value
        return saturatingAdd(value, alignment - remainder)
    }

    private fun alignDown(value: Long): Long {
        val alignment = RecordingPreallocationPolicy.BLOCK_BYTES
        if (value <= 0L || alignment <= 1L) return value.coerceAtLeast(0L)
        return value - value % alignment
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        if (safeLeft == 0L || safeRight == 0L) return 0L
        return if (safeLeft > Long.MAX_VALUE / safeRight) Long.MAX_VALUE else safeLeft * safeRight
    }
}
