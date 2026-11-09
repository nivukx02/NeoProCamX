package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * RawVideoBackpressurePolicy — cinema-oriented RAW video memory pressure management.
 *
 * In a professional RAW video pipeline, ImageReader frames must be explicitly
 * closed after the encoder/muxer has consumed them. If the pipeline stalls
 * (slow disk, encoder backpressure, high CPU load), un-closed images accumulate
 * in the reader's fixed-capacity ring buffer and new frames are dropped.
 *
 * This policy implements:
 *  1. **Dynamic budget management** — calculates safe open-image counts based
 *     on reader capacity and active write concurrency.
 *  2. **Priority-aware admission** — prevents LOW-priority frames from occupying
 *     slots needed for HDR/IFRAME anchors.
 *  3. **Adaptive queue sizing** — scales queue capacity relative to reader limits.
 *  4. **Backpressure signals** — provides structured drop-reason telemetry.
 *  5. **Stall detection** — identifies pipeline stalls from latency metrics.
 *
 * Compatible with HEVC/AV1 10-bit, Neo dynamic metadata sidecars, and CinemaDNG RAW
 * video muxing pipelines.
 */
object RawVideoBackpressurePolicy {

    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * Minimum number of frames reserved for the ImageReader's internal I/O.
     * Must be ≥ 1 to allow the hardware to keep streaming without stalls.
     */
    private const val MIN_READER_RESERVE = 1

    /**
     * Extra slots reserved when in strict high-end mode (HEVC/NIVUKX/AV1)
     * to prevent I-frame starvation during codec refresh cycles.
     */
    private const val STRICT_HIGH_END_EXTRA_RESERVE = 1

    /**
     * Maximum permitted queue depth ratio relative to reader capacity.
     * FLAGSHIP: Increased to 0.90f to allow massive burst depth for sensor-native open gate.
     */
    private const val MAX_QUEUE_FILL_RATIO = 0.90f

    /** Minimum absolute queue capacity regardless of reader size. */
    private const val ABSOLUTE_MIN_QUEUE_CAPACITY = 2 // FLAGSHIP: min 2

    /**
     * Stall detection threshold: if the oldest queued frame is older than
     * this many milliseconds, the pipeline is considered stalled.
     */
    private const val STALL_THRESHOLD_MS = 2000L // FLAGSHIP: sensor-native encodes take time, don't drop early

    /**
     * Target headroom percentage — kept free to absorb bursts without drops.
     * FLAGSHIP: Reduced to 5% to maximize buffer depth utilization.
     */
    private const val TARGET_HEADROOM_RATIO = 0.05f

    // ── Core Budget Calculation ───────────────────────────────────────────────

    /**
     * Calculates the maximum number of images that may be held open
     * simultaneously in the reader without risking a buffer-full stall.
     *
     * @param readerMaxImages The `ImageReader.maxImages` capacity configured at session time.
     * @param strictHighEnd   Whether the strict high-end pipeline is active (adds extra reserve).
     */
    fun maxRetainedOpenImages(
        readerMaxImages: Int,
        strictHighEnd: Boolean = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    ): Int {
        val safeCapacity = readerMaxImages.coerceAtLeast(MIN_READER_RESERVE + 1)
        val reserve = if (strictHighEnd) {
            MIN_READER_RESERVE + STRICT_HIGH_END_EXTRA_RESERVE
        } else {
            MIN_READER_RESERVE
        }
        return (safeCapacity - reserve).coerceAtLeast(1)
    }

    /**
     * Determines whether a newly acquired image frame can be safely retained
     * without exceeding the open-image budget.
     *
     * @param readerMaxImages Total reader capacity.
     * @param queuedImages    Frames currently waiting in the pipeline queue.
     * @param activeWrites    Frames currently being written by the encoder/muxer.
     * @param priority        Frame priority — IFRAME/HDR frames get special admission.
     */
    fun shouldRetainAcquiredFrame(
        readerMaxImages: Int,
        queuedImages: Int,
        activeWrites: Int,
        priority: FramePriority = FramePriority.NORMAL
    ): Boolean {
        val budget = maxRetainedOpenImages(readerMaxImages)
        val currentOpen = queuedImages.coerceAtLeast(0) + activeWrites.coerceAtLeast(0)

        // I-frames and HDR reference frames always admitted if at least 1 slot free
        if (priority == FramePriority.CRITICAL && currentOpen < readerMaxImages) return true

        return currentOpen < budget
    }

    /**
     * Resolves a safe queue capacity that leaves headroom for concurrent writes
     * and burst absorption.
     *
     * @param requestedQueueCapacity Desired queue depth from the pipeline configuration.
     * @param readerMaxImages        ImageReader buffer size.
     * @param reservedActiveWrites   Expected concurrent encoder write slots.
     * @param strictHighEnd          Whether strict high-end pipeline is active.
     */
    fun resolveSafeQueueCapacity(
        requestedQueueCapacity: Int,
        readerMaxImages: Int,
        reservedActiveWrites: Int = 1,
        strictHighEnd: Boolean = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    ): Int {
        val retainedBudget = maxRetainedOpenImages(readerMaxImages, strictHighEnd)
        val safeWriteReserve = reservedActiveWrites.coerceAtLeast(0)
        val maxQueuedImages = (retainedBudget - safeWriteReserve).coerceAtLeast(ABSOLUTE_MIN_QUEUE_CAPACITY)

        // Additionally cap at the fill ratio to leave burst headroom
        val fillCapCap = (readerMaxImages * MAX_QUEUE_FILL_RATIO).toInt().coerceAtLeast(ABSOLUTE_MIN_QUEUE_CAPACITY)
        val effectiveCap = minOf(maxQueuedImages, fillCapCap)

        return requestedQueueCapacity.coerceAtLeast(ABSOLUTE_MIN_QUEUE_CAPACITY).coerceAtMost(effectiveCap)
    }

    /**
     * Calculates target headroom — the number of free slots that should
     * remain unused to absorb short bursts without frame drops.
     *
     * @param readerMaxImages Reader capacity.
     */
    fun targetHeadroomSlots(readerMaxImages: Int): Int =
        (readerMaxImages * TARGET_HEADROOM_RATIO).toInt().coerceAtLeast(1)

    // ── Stall Detection ───────────────────────────────────────────────────────

    /**
     * Returns true if the pipeline appears stalled based on the age of the
     * oldest frame in the queue.
     *
     * A stall typically indicates:
     *  - Encoder/muxer thread blocked on I/O
     *  - Storage throughput exceeded
     *  - Android memory pressure reclaiming GPU buffers
     *
     * @param oldestFrameAgeMs Age in milliseconds of the oldest unprocessed frame.
     * @param thresholdMs      Stall detection threshold (default [STALL_THRESHOLD_MS]).
     */
    fun isPipelineStalled(
        oldestFrameAgeMs: Long,
        thresholdMs: Long = STALL_THRESHOLD_MS
    ): Boolean = oldestFrameAgeMs >= thresholdMs.coerceAtLeast(1L)

    // ── Drop Reason Telemetry ─────────────────────────────────────────────────

    /**
     * Resolves the structured reason for dropping an incoming frame.
     * Used for logcat telemetry and adaptive backpressure escalation.
     */
    fun resolveDropReason(
        readerMaxImages: Int,
        queuedImages: Int,
        activeWrites: Int,
        pipelineStalled: Boolean
    ): FrameDropReason {
        val budget = maxRetainedOpenImages(readerMaxImages)
        val currentOpen = queuedImages.coerceAtLeast(0) + activeWrites.coerceAtLeast(0)
        return when {
            pipelineStalled -> FrameDropReason.PIPELINE_STALL
            currentOpen >= readerMaxImages -> FrameDropReason.READER_BUFFER_FULL
            currentOpen >= budget -> FrameDropReason.BUDGET_EXCEEDED
            activeWrites >= readerMaxImages - 1 -> FrameDropReason.ENCODER_BACKPRESSURE
            else -> FrameDropReason.UNKNOWN
        }
    }

    // ── Adaptive Capacity Recommendation ─────────────────────────────────────

    /**
     * Recommends an adjusted reader capacity based on measured drop rate.
     * If drops are occurring, returns a larger suggested capacity to reduce
     * future drops without changing pipeline logic.
     *
     * @param currentCapacity Current `ImageReader.maxImages`.
     * @param dropRate        Fraction of frames dropped recently (0.0–1.0).
     * @param maxAllowedCapacity Upper bound for the reader size.
     */
    fun recommendAdjustedCapacity(
        currentCapacity: Int,
        dropRate: Float,
        maxAllowedCapacity: Int = 12
    ): Int {
        val safeCapacity = currentCapacity.coerceAtLeast(2)
        return when {
            dropRate > 0.10f -> minOf(safeCapacity + 3, maxAllowedCapacity) // severe
            dropRate > 0.02f -> minOf(safeCapacity + 1, maxAllowedCapacity) // moderate
            dropRate < 0.001f && safeCapacity > 4 -> safeCapacity - 1       // over-provisioned
            else -> safeCapacity
        }
    }

    // ── Data Types ────────────────────────────────────────────────────────────

    /**
     * Priority classification for incoming RAW frames.
     * CRITICAL frames (I-frames, HDR reference frames) receive preferential admission.
     */
    enum class FramePriority {
        NORMAL,
        HIGH,
        CRITICAL
    }

    /**
     * Structured reason for a frame drop decision.
     */
    enum class FrameDropReason(val description: String) {
        READER_BUFFER_FULL("ImageReader buffer capacity reached"),
        BUDGET_EXCEEDED("Retained open-image budget exceeded"),
        ENCODER_BACKPRESSURE("Encoder write queue full"),
        PIPELINE_STALL("Pipeline stalled — oldest frame age threshold exceeded"),
        THERMAL_THROTTLE("Thermal pressure — voluntary frame drop"),
        UNKNOWN("Unknown backpressure condition")
    }

    /**
     * Snapshot of the backpressure state for logging and telemetry.
     */
    data class BackpressureSnapshot(
        val readerCapacity: Int,
        val queuedImages: Int,
        val activeWrites: Int,
        val budget: Int,
        val currentOpen: Int,
        val headroomSlots: Int,
        val fillRatio: Float,
        val isStalled: Boolean,
        val dropReason: FrameDropReason?
    ) {
        val isCritical: Boolean get() = fillRatio > 0.85f
        val summary: String get() =
            "RAW backpressure: ${currentOpen}/${readerCapacity} open " +
            "(budget=$budget, headroom=$headroomSlots, fill=${(fillRatio * 100).toInt()}%" +
            "${if (isStalled) ", STALLED" else ""})"
    }

    /**
     * Creates a full [BackpressureSnapshot] for the current pipeline state.
     */
    fun snapshot(
        readerMaxImages: Int,
        queuedImages: Int,
        activeWrites: Int,
        oldestFrameAgeMs: Long = 0L
    ): BackpressureSnapshot {
        val budget = maxRetainedOpenImages(readerMaxImages)
        val currentOpen = queuedImages.coerceAtLeast(0) + activeWrites.coerceAtLeast(0)
        val headroom = targetHeadroomSlots(readerMaxImages)
        val fillRatio = currentOpen.toFloat() / readerMaxImages.coerceAtLeast(1).toFloat()
        val stalled = isPipelineStalled(oldestFrameAgeMs)
        val dropReason = if (currentOpen >= budget || stalled) {
            resolveDropReason(readerMaxImages, queuedImages, activeWrites, stalled)
        } else null
        return BackpressureSnapshot(
            readerCapacity = readerMaxImages,
            queuedImages = queuedImages,
            activeWrites = activeWrites,
            budget = budget,
            currentOpen = currentOpen,
            headroomSlots = headroom,
            fillRatio = fillRatio,
            isStalled = stalled,
            dropReason = dropReason
        )
    }
}
