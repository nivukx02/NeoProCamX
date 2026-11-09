package com.neoproc.neoproccamx.recording

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class RecordingFramePresentation(
    val sensorTimestampNs: Long,
    val encoderPresentationTimeUs: Long,
    val submittedAtNs: Long
)

/** Releases sensor identity only after the container backend confirms the sample write. */
internal object RecordingFrameCommitPolicy {
    fun committedSensorTimestampNs(
        framePresentation: RecordingFramePresentation?,
        containerWriteSucceeded: Boolean
    ): Long? =
        framePresentation
            ?.sensorTimestampNs
            ?.takeIf { containerWriteSucceeded && it > 0L }
}

internal data class RecordingFramePresentationOfferResult(
    val accepted: Boolean,
    val evictedPresentations: Int = 0,
    val duplicatePresentationTime: Boolean = false,
    val producerContentionRejected: Boolean = false
)

internal data class RecordingFramePresentationMatchResult(
    val presentation: RecordingFramePresentation?,
    val pendingAfterMatch: Int
)

internal data class RecordingFramePresentationTrackerSnapshot(
    val pendingPresentations: Int,
    val submittedPresentations: Long,
    val matchedPresentations: Long,
    val unmatchedEncoderOutputs: Long,
    val evictedPresentations: Long,
    val duplicatePresentations: Long,
    val producerContentionRejections: Long
) {
    val lostPresentations: Long
        get() = evictedPresentations + duplicatePresentations + producerContentionRejections
}

/**
 * Associates encoder output with the exact sensor identity submitted for that presentation time.
 *
 * A conflated/FIFO-only queue can silently attach frame N's sensor timestamp to frame N+1 when the
 * encoder lags, reorders output, or omits an input. Exact PTS lookup keeps later identities valid
 * after a miss. The bound protects memory. Producer admission is serialized with the encoder drain
 * for the few map operations below: dropping identity merely because the consumer owns the lock for
 * that instant would permanently destroy otherwise valid frame-lineage evidence.
 */
internal class RecordingFramePresentationTracker(maxPendingPresentations: Int) {
    private val capacity = maxPendingPresentations.coerceAtLeast(1)
    private val lock = ReentrantLock()
    private val pendingByEncoderPtsUs =
        LinkedHashMap<Long, RecordingFramePresentation>(capacity.coerceAtMost(256))

    private val submittedPresentations = AtomicLong(0L)
    private val producerContentionRejections = AtomicLong(0L)
    private var matchedPresentations = 0L
    private var unmatchedEncoderOutputs = 0L
    private var evictedPresentations = 0L
    private var duplicatePresentations = 0L

    fun offer(presentation: RecordingFramePresentation): RecordingFramePresentationOfferResult {
        submittedPresentations.incrementAndGet()
        return lock.withLock {
            if (pendingByEncoderPtsUs.containsKey(presentation.encoderPresentationTimeUs)) {
                duplicatePresentations += 1L
                return@withLock RecordingFramePresentationOfferResult(
                    accepted = false,
                    duplicatePresentationTime = true
                )
            }

            var evicted = 0
            if (pendingByEncoderPtsUs.size >= capacity) {
                val oldest = pendingByEncoderPtsUs.entries.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                    evictedPresentations += 1L
                    evicted = 1
                }
            }
            pendingByEncoderPtsUs[presentation.encoderPresentationTimeUs] = presentation
            RecordingFramePresentationOfferResult(
                accepted = true,
                evictedPresentations = evicted
            )
        }
    }

    fun matchWithDepth(encoderPresentationTimeUs: Long): RecordingFramePresentationMatchResult =
        lock.withLock {
            val presentation = pendingByEncoderPtsUs.remove(encoderPresentationTimeUs)
            if (presentation == null) {
                unmatchedEncoderOutputs += 1L
            } else {
                matchedPresentations += 1L
            }
            RecordingFramePresentationMatchResult(
                presentation = presentation,
                pendingAfterMatch = pendingByEncoderPtsUs.size
            )
        }

    fun match(encoderPresentationTimeUs: Long): RecordingFramePresentation? =
        matchWithDepth(encoderPresentationTimeUs).presentation

    fun reset() = lock.withLock {
        pendingByEncoderPtsUs.clear()
        submittedPresentations.set(0L)
        producerContentionRejections.set(0L)
        matchedPresentations = 0L
        unmatchedEncoderOutputs = 0L
        evictedPresentations = 0L
        duplicatePresentations = 0L
    }

    fun snapshot(): RecordingFramePresentationTrackerSnapshot = lock.withLock {
        RecordingFramePresentationTrackerSnapshot(
            pendingPresentations = pendingByEncoderPtsUs.size,
            submittedPresentations = submittedPresentations.get(),
            matchedPresentations = matchedPresentations,
            unmatchedEncoderOutputs = unmatchedEncoderOutputs,
            evictedPresentations = evictedPresentations,
            duplicatePresentations = duplicatePresentations,
            producerContentionRejections = producerContentionRejections.get()
        )
    }
}
