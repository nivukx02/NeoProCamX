package com.neoproc.neoproccamx.camera

internal data class RawSensorReaderDepthPlan(
    val maxImages: Int,
    val estimatedFrameBytes: Long,
    val estimatedReaderBytes: Long,
    val reason: String,
    /** True only when this reader can absorb the measured recorder stall without frame loss. */
    val boundedStallProtected: Boolean = false,
    val boundedStallBudgetNs: Long = 0L
)

internal object RawSensorReaderDepthPolicy {
    private const val RAW_CONTAINER_BYTES_PER_PIXEL = 2L
    private const val TWELVE_MEGAPIXELS = 13_000_000L
    private const val THIRTY_THREE_MEGAPIXELS = 33_000_000L

    // MAXIMUM_SENSOR (un-binned) RAW frames are enormous — a 100MP frame is ~200 MB at 2 bytes/px, so
    // even a shallow queue is heavy. Clamp the queue hard above this to bound reader RAM.
    private const val HUNDRED_MEGAPIXELS = 100_000_000L

    /**
     * CP157 device evidence: one otherwise healthy 4K25 recorder submission was descheduled for
     * 333.360 ms. LQ-001.07 subsequently measured an eleven-image ownership burst while the same
     * 4096x3072 RAW16 route remained at Android thermal status 4. Preserve the normal exact-result
     * join, processor owner, callback hand-off margin, and two additional frames for that measured
     * thermal/storage transient. At 25 fps this resolves to 16 Images (384 MiB exactly), the largest
     * depth permitted by the explicit native-reader guard; raster, cadence and sensor codes do not
     * change.
     */
    internal const val EVIDENCED_BOUNDED_STALL_NS = 334_000_000L
    private const val SENSOR_LINEAR_NDR_BASELINE_OWNERS = 3
    private const val CALLBACK_JOIN_MARGIN_FRAMES = 2
    private const val THERMAL_STORAGE_TRANSIENT_MARGIN_FRAMES = 2
    private const val SENSOR_LINEAR_NDR_REFERENCE_FPS = 25

    /** 384 MiB is an absolute native-reader guard, not a quality fallback. */
    internal const val MAX_BOUNDED_STALL_READER_BYTES = 384L * 1024L * 1024L

    /**
     * Returns whether the reader belongs to a sustained RAW route and must therefore keep the
     * bounded-stall queue depth. A mode-switch standby route remains the same route when its
     * diagnostic reason is qualified with details such as "reader retained". Treating only the
     * unqualified label as sustained caused the standby reader to be rebuilt at depth four on RAW
     * exit and again at depth fourteen on RAW entry, defeating the warm-reader handoff.
     */
    fun requiresSustainedCaptureDepth(
        rawVideoPrearmEnabled: Boolean,
        streamGraphMode: CinemaStreamGraphMode,
        rawRouteReason: String
    ): Boolean =
        rawVideoPrearmEnabled ||
            streamGraphMode == CinemaStreamGraphMode.RAW_ORIGINAL_RECORDING ||
            streamGraphMode == CinemaStreamGraphMode.SENSOR_LINEAR_NDR_PREVIEW ||
            streamGraphMode == CinemaStreamGraphMode.SENSOR_LINEAR_NDR_RECORDING ||
            rawRouteReason == MODE_SWITCH_RAW_STANDBY_REASON ||
            rawRouteReason.startsWith("$MODE_SWITCH_RAW_STANDBY_REASON (")

    fun minimumBoundedStallDepth(targetFps: Int): Int {
        val frameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
        val arrivalsDuringStall = ceilDiv(EVIDENCED_BOUNDED_STALL_NS, frameIntervalNs)
        return SENSOR_LINEAR_NDR_BASELINE_OWNERS +
            arrivalsDuringStall.toInt() +
            CALLBACK_JOIN_MARGIN_FRAMES +
            THERMAL_STORAGE_TRANSIENT_MARGIN_FRAMES
    }

    fun resolve(
        rawSize: CameraStreamSize,
        recordingActive: Boolean
    ): RawSensorReaderDepthPlan {
        val frameBytes = rawSize.area * RAW_CONTAINER_BYTES_PER_PIXEL
        val boundedStallDepth = minimumBoundedStallDepth(SENSOR_LINEAR_NDR_REFERENCE_FPS)
        val boundedStallFitsMemory =
            frameBytes > 0L &&
                boundedStallDepth.toLong() <= MAX_BOUNDED_STALL_READER_BYTES / frameBytes
        val maxImages = when {
            // Full 12 MP RAW16 containers fit the explicit native-memory guard while absorbing the
            // measured CP157 stall and LQ-001.07 thermal/storage transient. This is queue headroom
            // only: source resolution, precision, cadence, bitrate, and encoder quality are unchanged.
            recordingActive && rawSize.area <= TWELVE_MEGAPIXELS && boundedStallFitsMemory ->
                boundedStallDepth
            recordingActive && rawSize.area <= THIRTY_THREE_MEGAPIXELS -> 4
            recordingActive && rawSize.area <= HUNDRED_MEGAPIXELS -> 3
            // Three is the functional minimum: one ImageReader/HAL slot, one queued frame, and one
            // frame owned by the writer. A depth of two makes strict RAW capture fail at startup.
            recordingActive -> 3
            rawSize.area <= TWELVE_MEGAPIXELS -> 4
            rawSize.area <= THIRTY_THREE_MEGAPIXELS -> 3
            else -> 2
        }
        return RawSensorReaderDepthPlan(
            maxImages = maxImages,
            estimatedFrameBytes = frameBytes,
            estimatedReaderBytes = frameBytes * maxImages.toLong(),
            reason = reasonFor(
                rawSize = rawSize,
                recordingActive = recordingActive,
                boundedStallProtected = recordingActive && maxImages >= boundedStallDepth
            ),
            boundedStallProtected = recordingActive && maxImages >= boundedStallDepth,
            boundedStallBudgetNs = if (recordingActive && maxImages >= boundedStallDepth) {
                EVIDENCED_BOUNDED_STALL_NS
            } else {
                0L
            }
        )
    }

    private fun reasonFor(
        rawSize: CameraStreamSize,
        recordingActive: Boolean,
        boundedStallProtected: Boolean
    ): String =
        when {
            boundedStallProtected ->
                "sustained RAW recording queue with bounded 4K25 stall protection"
            recordingActive && rawSize.area > THIRTY_THREE_MEGAPIXELS ->
                "ultra-high-resolution RAW recording memory guard"
            recordingActive ->
                "sustained RAW recording queue"
            rawSize.area > THIRTY_THREE_MEGAPIXELS ->
                "ultra-high-resolution RAW still companion memory guard"
            else ->
                "RAW still companion queue"
        }

    private fun ceilDiv(value: Long, divisor: Long): Long {
        require(value >= 0L)
        require(divisor > 0L)
        return value / divisor + if (value % divisor == 0L) 0L else 1L
    }
}
