package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

enum class PipelineStage {
    SENSOR_CAPTURE,
    FRAME_DISTRIBUTION,
    RAW_PROCESSING,
    PREVIEW,
    ENCODER,
    RECORDING,
    STORAGE,
    AI,
    IMAGE_PROCESSING,
    VFX,
    METADATA,
    DISPLAY
}

data class PipelineResourceSample(
    val measuredAtNs: Long,
    val cpuUtilization: Float? = null,
    val gpuUtilization: Float? = null,
    val npuDspUtilization: Float? = null,
    val memoryUsedBytes: Long? = null,
    val memoryPressure: Float? = null,
    val storageWriteMegabytesPerSecond: Float? = null,
    val storageQueuePressure: Float? = null,
    val thermal: TimedThermalSample? = null
)

data class StageTimingSnapshot(
    val samples: Long,
    val failures: Long,
    val missedDeadlines: Long,
    val averageProcessingNs: Long,
    val maximumProcessingNs: Long,
    val averageQueueWaitNs: Long,
    val maximumQueueWaitNs: Long,
    val currentQueueDepth: Int,
    val maximumQueueDepth: Int
)

data class SensorPipelineDiagnosticsSnapshot(
    val targetFps: Double,
    val captureFps: Double,
    val recordingFps: Double,
    val previewFps: Double,
    val displayFps: Double,
    val capturedFrames: Long,
    val recordedFrames: Long,
    val previewFrames: Long,
    val displayedFrames: Long,
    val droppedCaptureFrames: Long,
    val droppedRecordingFrames: Long,
    val explicitDroppedRecordingFrames: Long,
    val inferredRecordingTimestampGapFrames: Long,
    val unrecoveredRecordingTimestampGapFrames: Long,
    val recordingTimestampGapEvents: Long,
    val recordingMaximumTimestampGapNs: Long,
    val recordingFirstSensorTimestampNs: Long,
    val recordingLastSensorTimestampNs: Long,
    val recordingSourceFps: Double,
    val firstExplicitRecordingDropReason: String,
    val firstRecordingGapPreviousTimestampNs: Long,
    val firstRecordingGapTimestampNs: Long,
    val skippedPreviewFrames: Long,
    val stalePreviewFrames: Long,
    val lateCaptureFrames: Long,
    val identityConflicts: Long,
    val stageTiming: Map<PipelineStage, StageTimingSnapshot>,
    val domainFailures: Map<FrameDomain, Long>,
    val resources: PipelineResourceSample?
) {
    val encoderStalls: Long
        get() = stageTiming[PipelineStage.ENCODER]?.missedDeadlines ?: 0L
    val storageStalls: Long
        get() = stageTiming[PipelineStage.STORAGE]?.missedDeadlines ?: 0L
    val previewStalls: Long
        get() = stageTiming[PipelineStage.PREVIEW]?.missedDeadlines ?: 0L
}

class StageTimingToken internal constructor(
    val stage: PipelineStage,
    val frameIdentity: FrameIdentity,
    val startedAtNs: Long,
    val queueEnteredAtNs: Long,
    val deadlineNs: Long,
    val queueDepthAtStart: Int
)

/** Accurate per-domain counters. Preview skips are intentionally never added to recording loss. */
class PipelineDeadlineMonitor(
    targetFps: Double,
    private val monotonicClockNs: () -> Long
) {
    private val targetFrameIntervalNs = AtomicLong(intervalForFps(targetFps))
    private val captureRate = RateCounter()
    private val recordingRate = RateCounter()
    private val previewRate = RateCounter()
    private val displayRate = RateCounter()
    private val captureSequence = AtomicReference<SequencePoint?>(null)
    private val recordingSequence = AtomicReference<SequencePoint?>(null)
    private val capturedFrames = AtomicLong(0L)
    private val recordedFrames = AtomicLong(0L)
    private val previewFrames = AtomicLong(0L)
    private val displayedFrames = AtomicLong(0L)
    private val droppedCaptureFrames = AtomicLong(0L)
    private val explicitDroppedRecordingFrames = AtomicLong(0L)
    private val inferredRecordingTimestampGapFrames = AtomicLong(0L)
    private val recordingTimestampGapEvents = AtomicLong(0L)
    private val recordingMaximumTimestampGapNs = AtomicLong(0L)
    private val recordingFirstSensorTimestampNs = AtomicLong(0L)
    private val recordingLastSensorTimestampNs = AtomicLong(0L)
    private val firstExplicitRecordingDropReason = AtomicReference("")
    private val firstRecordingGapPreviousTimestampNs = AtomicLong(0L)
    private val firstRecordingGapTimestampNs = AtomicLong(0L)
    private val recordingTimestampContinuity = RecordingTimestampContinuityTracker(
        targetFrameIntervalNs = targetFrameIntervalNs.get()
    )
    private val skippedPreviewFrames = AtomicLong(0L)
    private val stalePreviewFrames = AtomicLong(0L)
    private val lateCaptureFrames = AtomicLong(0L)
    private val identityConflicts = AtomicLong(0L)
    private val stages = PipelineStage.entries.associateWith { StageAccumulator() }
    private val domainFailures = FrameDomain.entries.associateWith { AtomicLong(0L) }
    private val resources = AtomicReference<PipelineResourceSample?>(null)

    fun updateTargetFps(targetFps: Double) {
        val intervalNs = intervalForFps(targetFps)
        targetFrameIntervalNs.set(intervalNs)
        recordingTimestampContinuity.updateTargetFrameIntervalNs(intervalNs)
    }

    fun recordCapture(identity: FrameIdentity) {
        capturedFrames.incrementAndGet()
        val timestampNs = identity.sensorTimestampNs.takeIf { it > 0L } ?: monotonicClockNs()
        captureRate.mark(timestampNs)
        val gap = advanceSequence(captureSequence, identity, timestampNs)
        if (gap.missingFrames > 0L) droppedCaptureFrames.addAndGet(gap.missingFrames)
        if (gap.intervalNs > captureDeadlineNs()) lateCaptureFrames.incrementAndGet()
    }

    fun recordRecordingCompleted(identity: FrameIdentity, completedAtNs: Long = monotonicClockNs()) {
        recordedFrames.incrementAndGet()
        recordingRate.mark(completedAtNs)
        val sensorTimestampNs = identity.sensorTimestampNs
        if (sensorTimestampNs > 0L) {
            recordingFirstSensorTimestampNs.compareAndSet(0L, sensorTimestampNs)
            recordingLastSensorTimestampNs.updateAndGet { previous ->
                if (sensorTimestampNs > previous) sensorTimestampNs else previous
            }
        }
        val gap = advanceSequence(recordingSequence, identity, sensorTimestampNs)
        if (gap.missingFrames > 0L) {
            inferredRecordingTimestampGapFrames.addAndGet(gap.missingFrames)
            recordingTimestampGapEvents.incrementAndGet()
            recordingMaximumTimestampGapNs.updateAndGet { previous ->
                maxOf(previous, gap.intervalNs)
            }
            firstRecordingGapPreviousTimestampNs.compareAndSet(0L, gap.previousTimestampNs)
            firstRecordingGapTimestampNs.compareAndSet(0L, sensorTimestampNs)
        }
        recordingTimestampContinuity.record(sensorTimestampNs)
    }

    fun recordRecordingDrop(count: Long = 1L, reason: String = "unspecified") {
        if (count <= 0L) return
        explicitDroppedRecordingFrames.addAndGet(count)
        firstExplicitRecordingDropReason.compareAndSet("", reason.ifBlank { "unspecified" })
    }

    fun recordPreviewReady(presentedAtNs: Long = monotonicClockNs()) {
        previewFrames.incrementAndGet()
        previewRate.mark(presentedAtNs)
    }

    fun recordPreviewSkipped(stale: Boolean) {
        skippedPreviewFrames.incrementAndGet()
        if (stale) stalePreviewFrames.incrementAndGet()
    }

    fun recordDisplayed(displayedAtNs: Long = monotonicClockNs()) {
        displayedFrames.incrementAndGet()
        displayRate.mark(displayedAtNs)
    }

    fun recordIdentityConflict() {
        identityConflicts.incrementAndGet()
    }

    fun recordDomainFailure(domain: FrameDomain) {
        domainFailures.getValue(domain).incrementAndGet()
    }

    fun beginStage(
        stage: PipelineStage,
        identity: FrameIdentity,
        queueEnteredAtNs: Long,
        deadlineNs: Long,
        queueDepth: Int,
        startedAtNs: Long = monotonicClockNs()
    ): StageTimingToken {
        stages.getValue(stage).setQueueDepth(queueDepth)
        return StageTimingToken(
            stage = stage,
            frameIdentity = identity,
            startedAtNs = startedAtNs,
            queueEnteredAtNs = queueEnteredAtNs,
            deadlineNs = deadlineNs,
            queueDepthAtStart = queueDepth
        )
    }

    fun finishStage(
        token: StageTimingToken,
        success: Boolean,
        endedAtNs: Long = monotonicClockNs()
    ) {
        val durationNs = (endedAtNs - token.startedAtNs).coerceAtLeast(0L)
        val queueWaitNs = (token.startedAtNs - token.queueEnteredAtNs).coerceAtLeast(0L)
        stages.getValue(token.stage).record(
            durationNs = durationNs,
            queueWaitNs = queueWaitNs,
            missedDeadline = token.deadlineNs > 0L && endedAtNs > token.deadlineNs,
            success = success,
            queueDepth = token.queueDepthAtStart
        )
    }

    fun setQueueDepth(stage: PipelineStage, depth: Int) {
        stages.getValue(stage).setQueueDepth(depth)
    }

    fun updateResources(sample: PipelineResourceSample) {
        require(sample.measuredAtNs > 0L) { "Resource sample must carry a real monotonic timestamp" }
        require(sample.cpuUtilization?.let { it.isFinite() && it in 0f..1f } != false)
        require(sample.gpuUtilization?.let { it.isFinite() && it in 0f..1f } != false)
        require(sample.npuDspUtilization?.let { it.isFinite() && it in 0f..1f } != false)
        require(sample.memoryPressure?.let { it.isFinite() && it in 0f..1f } != false)
        require(sample.storageWriteMegabytesPerSecond?.let { it.isFinite() && it >= 0f } != false)
        require(sample.storageQueuePressure?.let { it.isFinite() && it in 0f..1f } != false)
        while (true) {
            val previous = resources.get()
            if (previous != null && sample.measuredAtNs < previous.measuredAtNs) return
            if (resources.compareAndSet(previous, sample)) return
        }
    }

    fun snapshot(): SensorPipelineDiagnosticsSnapshot {
        val continuity = recordingTimestampContinuity.snapshot()
        val explicitDrops = explicitDroppedRecordingFrames.get()
        return SensorPipelineDiagnosticsSnapshot(
        targetFps = 1_000_000_000.0 / targetFrameIntervalNs.get().toDouble(),
        captureFps = captureRate.fps(monotonicClockNs()),
        recordingFps = recordingRate.fps(monotonicClockNs()),
        previewFps = previewRate.fps(monotonicClockNs()),
        displayFps = displayRate.fps(monotonicClockNs()),
        capturedFrames = capturedFrames.get(),
        recordedFrames = recordedFrames.get(),
        previewFrames = previewFrames.get(),
        displayedFrames = displayedFrames.get(),
        droppedCaptureFrames = droppedCaptureFrames.get(),
        droppedRecordingFrames = explicitDrops + continuity.unrecoveredGapFrames,
        explicitDroppedRecordingFrames = explicitDrops,
        inferredRecordingTimestampGapFrames = inferredRecordingTimestampGapFrames.get(),
        unrecoveredRecordingTimestampGapFrames = continuity.unrecoveredGapFrames,
        recordingTimestampGapEvents = recordingTimestampGapEvents.get(),
        recordingMaximumTimestampGapNs = recordingMaximumTimestampGapNs.get(),
        recordingFirstSensorTimestampNs = recordingFirstSensorTimestampNs.get(),
        recordingLastSensorTimestampNs = recordingLastSensorTimestampNs.get(),
        recordingSourceFps = continuity.sourceFps,
        firstExplicitRecordingDropReason = firstExplicitRecordingDropReason.get(),
        firstRecordingGapPreviousTimestampNs = firstRecordingGapPreviousTimestampNs.get(),
        firstRecordingGapTimestampNs = firstRecordingGapTimestampNs.get(),
        skippedPreviewFrames = skippedPreviewFrames.get(),
        stalePreviewFrames = stalePreviewFrames.get(),
        lateCaptureFrames = lateCaptureFrames.get(),
        identityConflicts = identityConflicts.get(),
        stageTiming = stages.mapValues { it.value.snapshot() },
        domainFailures = domainFailures.mapValues { it.value.get() },
        resources = resources.get()
        )
    }

    fun reset() {
        captureRate.reset()
        recordingRate.reset()
        previewRate.reset()
        displayRate.reset()
        captureSequence.set(null)
        recordingSequence.set(null)
        capturedFrames.set(0L)
        recordedFrames.set(0L)
        previewFrames.set(0L)
        displayedFrames.set(0L)
        droppedCaptureFrames.set(0L)
        explicitDroppedRecordingFrames.set(0L)
        inferredRecordingTimestampGapFrames.set(0L)
        recordingTimestampGapEvents.set(0L)
        recordingMaximumTimestampGapNs.set(0L)
        recordingFirstSensorTimestampNs.set(0L)
        recordingLastSensorTimestampNs.set(0L)
        firstExplicitRecordingDropReason.set("")
        firstRecordingGapPreviousTimestampNs.set(0L)
        firstRecordingGapTimestampNs.set(0L)
        recordingTimestampContinuity.reset(targetFrameIntervalNs.get())
        skippedPreviewFrames.set(0L)
        stalePreviewFrames.set(0L)
        lateCaptureFrames.set(0L)
        identityConflicts.set(0L)
        stages.values.forEach(StageAccumulator::reset)
        domainFailures.values.forEach { it.set(0L) }
        resources.set(null)
    }

    private fun advanceSequence(
        target: AtomicReference<SequencePoint?>,
        identity: FrameIdentity,
        timestampNs: Long
    ): SequenceGap {
        while (true) {
            val previous = target.get()
            val current = SequencePoint(
                sessionGeneration = identity.sessionGeneration,
                frameNumber = identity.frameNumber,
                timestampNs = timestampNs
            )
            if (previous != null && belongsToDifferentAssignedSession(previous, current)) {
                if (target.compareAndSet(previous, current)) {
                    return SequenceGap(intervalNs = 0L, missingFrames = 0L, previousTimestampNs = 0L)
                }
                continue
            }
            if (previous != null && timestampNs <= previous.timestampNs) {
                return SequenceGap(intervalNs = 0L, missingFrames = 0L, previousTimestampNs = 0L)
            }
            if (target.compareAndSet(previous, current)) {
                if (previous == null) return SequenceGap(0L, 0L, 0L)
                val interval = (timestampNs - previous.timestampNs).coerceAtLeast(0L)
                val timestampGap = missingFramesFromTimestampInterval(interval)
                val frameNumberGap = if (identity.frameNumber != null && previous.frameNumber != null) {
                    (identity.frameNumber - previous.frameNumber - 1L).coerceAtLeast(0L)
                } else {
                    null
                }
                // Camera frame numbers may include requests routed to another surface. When that
                // sequence conflicts with the physical timestamp cadence, the latter is the only
                // valid measure of frames that this pipeline actually missed.
                val missingFrames = frameNumberGap
                    ?.takeIf { it == timestampGap }
                    ?: timestampGap
                return SequenceGap(interval, missingFrames, previous.timestampNs)
            }
        }
    }

    private fun missingFramesFromTimestampInterval(intervalNs: Long): Long {
        val expected = targetFrameIntervalNs.get().coerceAtLeast(1L)
        return ((intervalNs + expected / 2L) / expected - 1L).coerceAtLeast(0L)
    }

    private fun belongsToDifferentAssignedSession(
        previous: SequencePoint,
        current: SequencePoint
    ): Boolean =
        previous.sessionGeneration != FrameIdentity.UNASSIGNED_SESSION_GENERATION &&
            current.sessionGeneration != FrameIdentity.UNASSIGNED_SESSION_GENERATION &&
            previous.sessionGeneration != current.sessionGeneration

    private fun captureDeadlineNs(): Long =
        targetFrameIntervalNs.get() + targetFrameIntervalNs.get() / 3L

    private data class SequencePoint(
        val sessionGeneration: Long,
        val frameNumber: Long?,
        val timestampNs: Long
    )
    private data class SequenceGap(
        val intervalNs: Long,
        val missingFrames: Long,
        val previousTimestampNs: Long
    )

    private companion object {
        fun intervalForFps(fps: Double): Long {
            require(fps.isFinite() && fps > 0.0) { "Target FPS must be finite and positive" }
            return (1_000_000_000.0 / fps).toLong().coerceAtLeast(1L)
        }
    }
}

/**
 * Distinguishes a real missing source frame from ordinary Camera2 timestamp jitter.
 *
 * A single interval can be long because a vendor delivers one callback late and the following
 * callback early. Counting that long interval immediately invents a drop even though no source
 * frame was lost. This tracker gives a candidate gap three subsequent intervals to repay its phase
 * error. Only the unrepaid whole-frame debt is reported as a cadence loss. Explicit queue/submit
 * failures remain independently counted and never enter this estimator.
 */
private class RecordingTimestampContinuityTracker(targetFrameIntervalNs: Long) {
    private var expectedIntervalNs = targetFrameIntervalNs.coerceAtLeast(1L)
    private var observedFrames = 0L
    private var firstTimestampNs = 0L
    private var lastTimestampNs = 0L
    private var pendingExcessNs = 0L
    private var pendingAgeIntervals = 0
    private var confirmedMissingFrames = 0L

    @Synchronized
    fun updateTargetFrameIntervalNs(value: Long) {
        expectedIntervalNs = value.coerceAtLeast(1L)
        pendingExcessNs = 0L
        pendingAgeIntervals = 0
    }

    @Synchronized
    fun record(timestampNs: Long) {
        if (timestampNs <= 0L) return
        if (lastTimestampNs <= 0L) {
            firstTimestampNs = timestampNs
            lastTimestampNs = timestampNs
            observedFrames = 1L
            return
        }
        if (timestampNs <= lastTimestampNs) return

        val intervalNs = timestampNs - lastTimestampNs
        lastTimestampNs = timestampNs
        observedFrames = addSaturated(observedFrames, 1L)
        val deviationNs = intervalNs - expectedIntervalNs

        if (pendingAgeIntervals == 0) {
            // Half an interval is the same threshold used by nearest-frame interval rounding.
            if (deviationNs >= expectedIntervalNs / 2L) {
                pendingExcessNs = deviationNs
                pendingAgeIntervals = 1
            }
            return
        }

        pendingExcessNs = saturatingAddSigned(pendingExcessNs, deviationNs)
        pendingAgeIntervals++
        if (pendingExcessNs < expectedIntervalNs / 2L) {
            // The short follow-up interval repaid the delayed callback: jitter, not loss.
            pendingExcessNs = 0L
            pendingAgeIntervals = 0
            return
        }
        if (pendingAgeIntervals >= RECOVERY_LOOKAHEAD_INTERVALS) {
            val missing = ((pendingExcessNs + expectedIntervalNs / 2L) / expectedIntervalNs)
                .coerceAtLeast(1L)
            confirmedMissingFrames = addSaturated(confirmedMissingFrames, missing)
            pendingExcessNs = (pendingExcessNs - missing * expectedIntervalNs).coerceAtLeast(0L)
            pendingAgeIntervals = 0
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val spanNs = (lastTimestampNs - firstTimestampNs).coerceAtLeast(0L)
        val sourceFps = if (observedFrames > 1L && spanNs > 0L) {
            (observedFrames - 1L).toDouble() * 1_000_000_000.0 / spanNs.toDouble()
        } else {
            0.0
        }
        return Snapshot(
            sourceFps = sourceFps,
            unrecoveredGapFrames = confirmedMissingFrames
        )
    }

    @Synchronized
    fun reset(targetFrameIntervalNs: Long) {
        expectedIntervalNs = targetFrameIntervalNs.coerceAtLeast(1L)
        observedFrames = 0L
        firstTimestampNs = 0L
        lastTimestampNs = 0L
        pendingExcessNs = 0L
        pendingAgeIntervals = 0
        confirmedMissingFrames = 0L
    }

    data class Snapshot(
        val sourceFps: Double,
        val unrecoveredGapFrames: Long
    )

    private fun addSaturated(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun saturatingAddSigned(left: Long, right: Long): Long = when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }

    private companion object {
        const val RECOVERY_LOOKAHEAD_INTERVALS = 3
    }
}

private class RateCounter {
    /**
     * Fixed-storage rolling window. Camera/encoder producers only perform two atomics and one array
     * write; they never allocate, lock, wait, or prune a collection. Snapshot readers scan the bounded
     * ring, which keeps all diagnostic work outside the timing-critical ingress path.
     */
    private val samples = AtomicLongArray(SAMPLE_CAPACITY)
    private val acceptedSamples = AtomicLong(0L)
    private val latestTimestampNs = AtomicLong(0L)

    fun mark(timestampNs: Long) {
        if (timestampNs <= 0L) return
        while (true) {
            val previous = latestTimestampNs.get()
            if (timestampNs <= previous) return
            if (!latestTimestampNs.compareAndSet(previous, timestampNs)) continue
            val sequence = acceptedSamples.getAndIncrement()
            samples.set((sequence % SAMPLE_CAPACITY).toInt(), timestampNs)
            return
        }
    }

    fun fps(nowNs: Long): Double {
        val latest = latestTimestampNs.get()
        if (latest <= 0L) return 0.0

        // Sensor and elapsed-realtime clocks are expected to share Android's monotonic timebase. If a
        // vendor reports another origin, retaining the latest sample as the reference avoids declaring
        // a false multi-year stall while the rolling sample interval remains fully usable.
        val referenceNs = nowNs.takeIf {
            it >= latest && it - latest <= MAX_REASONABLE_CLOCK_OFFSET_NS
        } ?: latest
        val cutoffNs = (referenceNs - WINDOW_DURATION_NS).coerceAtLeast(0L)
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var count = 0
        for (index in 0 until SAMPLE_CAPACITY) {
            val timestamp = samples.get(index)
            if (timestamp > cutoffNs && timestamp <= referenceNs) {
                first = minOf(first, timestamp)
                last = maxOf(last, timestamp)
                count += 1
            }
        }
        if (count < 2 || first == Long.MAX_VALUE || last <= first) return 0.0

        // Include time since the newest event. This makes a real capture/encoder/storage stall decay
        // immediately instead of reporting the pre-stall cadence until the complete window expires.
        val observedDurationNs = (referenceNs - first).coerceAtLeast(last - first)
        return if (observedDurationNs <= 0L) 0.0 else {
            (count - 1).toDouble() * 1_000_000_000.0 / observedDurationNs.toDouble()
        }
    }

    fun reset() {
        latestTimestampNs.set(0L)
        acceptedSamples.set(0L)
        for (index in 0 until SAMPLE_CAPACITY) samples.set(index, 0L)
    }

    private companion object {
        const val SAMPLE_CAPACITY = 1024
        const val WINDOW_DURATION_NS = 2_000_000_000L
        const val MAX_REASONABLE_CLOCK_OFFSET_NS = 60_000_000_000L
    }
}

private class StageAccumulator {
    private val samples = AtomicLong(0L)
    private val failures = AtomicLong(0L)
    private val missedDeadlines = AtomicLong(0L)
    private val totalProcessingNs = AtomicLong(0L)
    private val maximumProcessingNs = AtomicLong(0L)
    private val totalQueueWaitNs = AtomicLong(0L)
    private val maximumQueueWaitNs = AtomicLong(0L)
    private val currentQueueDepth = AtomicInteger(0)
    private val maximumQueueDepth = AtomicInteger(0)

    fun record(
        durationNs: Long,
        queueWaitNs: Long,
        missedDeadline: Boolean,
        success: Boolean,
        queueDepth: Int
    ) {
        samples.incrementAndGet()
        if (!success) failures.incrementAndGet()
        if (missedDeadline) missedDeadlines.incrementAndGet()
        totalProcessingNs.addAndGet(durationNs)
        totalQueueWaitNs.addAndGet(queueWaitNs)
        maximumProcessingNs.updateMaximum(durationNs)
        maximumQueueWaitNs.updateMaximum(queueWaitNs)
        // beginStage() already sampled this depth for the high-watermark. Do not overwrite the
        // live depth here: producers may have published a newer queue depth while this stage ran.
        maximumQueueDepth.updateMaximum(queueDepth.coerceAtLeast(0))
    }

    fun setQueueDepth(depth: Int) {
        val safeDepth = depth.coerceAtLeast(0)
        currentQueueDepth.set(safeDepth)
        while (true) {
            val previous = maximumQueueDepth.get()
            if (safeDepth <= previous || maximumQueueDepth.compareAndSet(previous, safeDepth)) return
        }
    }

    fun snapshot(): StageTimingSnapshot {
        val count = samples.get()
        return StageTimingSnapshot(
            samples = count,
            failures = failures.get(),
            missedDeadlines = missedDeadlines.get(),
            averageProcessingNs = if (count == 0L) 0L else totalProcessingNs.get() / count,
            maximumProcessingNs = maximumProcessingNs.get(),
            averageQueueWaitNs = if (count == 0L) 0L else totalQueueWaitNs.get() / count,
            maximumQueueWaitNs = maximumQueueWaitNs.get(),
            currentQueueDepth = currentQueueDepth.get(),
            maximumQueueDepth = maximumQueueDepth.get()
        )
    }

    fun reset() {
        samples.set(0L)
        failures.set(0L)
        missedDeadlines.set(0L)
        totalProcessingNs.set(0L)
        maximumProcessingNs.set(0L)
        totalQueueWaitNs.set(0L)
        maximumQueueWaitNs.set(0L)
        currentQueueDepth.set(0)
        maximumQueueDepth.set(0)
    }
}

private fun AtomicLong.updateMaximum(candidate: Long) {
    while (true) {
        val previous = get()
        if (candidate <= previous || compareAndSet(previous, candidate)) return
    }
}

private fun AtomicInteger.updateMaximum(candidate: Int) {
    while (true) {
        val previous = get()
        if (candidate <= previous || compareAndSet(previous, candidate)) return
    }
}
