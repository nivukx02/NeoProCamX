package com.neoproc.neoproccamx.recording

/** Bounds full-resolution direct buffers without changing a single recorded pixel or packet. */
internal object Npv10InFlightBufferPolicy {
    const val MIN_PENDING_FRAMES = 3
    const val MAX_PENDING_FRAMES = 12
    // Twelve full-sensor packed-P010 leases cover 480 ms at 25 fps. Keeping the DMC working set at
    // 342 MiB avoids the extra 90 MiB of hot anonymous pages that made the sixteen-slot device run
    // thermally worse. The unchanged eighteen-frame ownership envelope is completed by six reader
    // slots below, so transient HAL/scheduler bursts are absorbed before strict loss detection.
    // One additional MiB covers NLC2's bounded per-chunk framing while preserving the existing
    // twelve-slot full-sensor queue. This avoids silently reducing burst tolerance after enabling
    // compression and still keeps the complete working set explicitly bounded.
    const val MAX_DIRECT_BUFFER_BYTES = 343L * 1024L * 1024L
    // NIV99 has four persistent full-resolution canonical buffers, matching the four packing lanes.
    // Keep that processing path on its independently bounded 256 MiB ceiling instead of inheriting
    // the larger ordinary pool. At 4096x3072, four canonical buffers plus four maximum NLC2 packet
    // slots remain below this ceiling, so the added throughput cannot reduce queue headroom.
    const val NIV99_MAX_DIRECT_BUFFER_BYTES = 256L * 1024L * 1024L
    const val READER_RESERVED_IMAGES = 6
    /** One canonical copy per packing worker; all are allocated before recording admission. */
    const val NIV99_CANONICAL_BUFFER_COUNT = 4
    /** Leaves the full-sensor twelve-packet pool plus fallback intermediaries at 342 MiB. */
    const val MAX_CONCURRENT_CANONICAL_FALLBACKS = 2
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val ADMISSION_WAIT_FRAME_PERIODS = 4L
    private const val MIN_ADMISSION_WAIT_NANOS = 2_000_000L
    private const val MAX_ADMISSION_WAIT_NANOS = 200_000_000L

    fun pendingFrames(
        width: Int,
        height: Int,
        packetCodec: Npv10PacketCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
    ): Int {
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val encodedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            packetCodec
        )
        val canonicalFallbackBytes = decodedBytes * MAX_CONCURRENT_CANONICAL_FALLBACKS
        val packetBudget =
            (MAX_DIRECT_BUFFER_BYTES - canonicalFallbackBytes).coerceAtLeast(encodedBytes)
        return (packetBudget / encodedBytes.coerceAtLeast(1L)).toInt()
            .coerceIn(MIN_PENDING_FRAMES, MAX_PENDING_FRAMES)
    }

    fun ordinaryResidentBufferBytes(
        width: Int,
        height: Int,
        packetCodec: Npv10PacketCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
    ): Long {
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val encodedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            packetCodec
        )
        return decodedBytes * MAX_CONCURRENT_CANONICAL_FALLBACKS +
            encodedBytes * pendingFrames(width, height, packetCodec)
    }

    /**
     * Bounds the NIV99 packet pool after reserving four full-resolution canonical P010 copies.
     * At 4096x3072 this yields four 22.5 MiB packet leases plus four 36 MiB canonical leases:
     * about 234 MiB total, below [NIV99_MAX_DIRECT_BUFFER_BYTES].
     */
    fun niv99PendingFrames(
        width: Int,
        height: Int,
        packetCodec: Npv10PacketCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
    ): Int {
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val encodedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            packetCodec
        )
        val canonicalBytes = decodedBytes * NIV99_CANONICAL_BUFFER_COUNT
        val packetBudget =
            (NIV99_MAX_DIRECT_BUFFER_BYTES - canonicalBytes).coerceAtLeast(encodedBytes)
        return (packetBudget / encodedBytes.coerceAtLeast(1L)).toInt()
            .coerceIn(MIN_PENDING_FRAMES, MAX_PENDING_FRAMES)
    }

    fun niv99ResidentBufferBytes(
        width: Int,
        height: Int,
        packetCodec: Npv10PacketCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
    ): Long {
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val encodedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            packetCodec
        )
        return decodedBytes * NIV99_CANONICAL_BUFFER_COUNT +
            encodedBytes * niv99PendingFrames(width, height, packetCodec)
    }

    fun readerMaxImages(
        width: Int,
        height: Int,
        packetCodec: Npv10PacketCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
    ): Int = pendingFrames(width, height, packetCodec) + READER_RESERVED_IMAGES

    /**
     * Gives a sustainable writer one bounded opportunity to release a lease before strict failure.
     *
     * The P010 ImageReader has six slots beyond the writer queue. The callback currently owns one;
     * a four-frame-period wait leaves one further slot as hard HAL headroom. This bounded recovery
     * window absorbs the measured thermal scheduler burst without increasing the total eighteen
     * ImageReader slots or hiding sustained overload. Timestamp lineage and the terminal cadence
     * audit still fail closed if Camera2 loses a frame while the producer is backpressured.
     */
    fun admissionWaitNanos(fps: Int): Long {
        require(fps > 0) { "NPV10 admission FPS must be positive" }
        val framePeriodNanos = (NANOS_PER_SECOND + fps.toLong() - 1L) / fps.toLong()
        return (framePeriodNanos * ADMISSION_WAIT_FRAME_PERIODS)
            .coerceIn(MIN_ADMISSION_WAIT_NANOS, MAX_ADMISSION_WAIT_NANOS)
    }
}
