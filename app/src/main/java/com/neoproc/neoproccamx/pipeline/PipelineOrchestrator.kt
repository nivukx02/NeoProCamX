package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.camera.DualGainHdrFusionPolicy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs

data class RealtimeQueuedFrame<T>(
    val frame: T,
    val sensorTimestampNs: Long,
    val enqueuedAtNs: Long
)

data class RealtimeFrameOfferResult(
    val accepted: Boolean,
    val reason: String
)

data class RealtimeFrameDispatchTelemetry(
    val acceptedFrames: Long,
    val droppedFrames: Long,
    val rejectedFrames: Long,
    val averageQueueLatencyNs: Long,
    val queuedFrames: Int = 0,
    /** Monitoring-only skips; these are deliberately distinct from recording frame loss. */
    val skippedPreviewFrames: Long = 0L,
    val stalePreviewFrames: Long = 0L,
    /** Number of terminal dequeue/conflation observations represented by the latency average. */
    val queueLatencySamples: Long = 0L
)

class RealtimeFrameDispatcher<T>(
    capacity: Int,
    private val acquireGuardNs: Long,
    private val closeFrame: (T) -> Unit,
    val allowsStaleFrameConflation: Boolean = true
) {
    private val safeCapacity = capacity.coerceAtLeast(1)
    private val slots = AtomicReferenceArray<RealtimeQueuedFrame<T>?>(safeCapacity)
    private val writeIndex = AtomicLong(0L)
    private val readIndex = AtomicLong(0L)
    private val acceptedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val rejectedFrames = AtomicLong(0L)
    private val skippedPreviewFrames = AtomicLong(0L)
    private val stalePreviewFrames = AtomicLong(0L)
    private val totalQueueLatencyNs = AtomicLong(0L)
    private val queueLatencySamples = AtomicLong(0L)

    /*
     * Queue ownership barrier. A clear is a lifecycle boundary: every operation that acquired a slot
     * before the boundary finishes first, then clear atomically detaches every remaining frame and
     * resets the ring. New offers/polls fail fast while that boundary (including Image.close calls) is
     * active, so a capture callback never waits behind teardown and cannot publish into a slot that
     * clear has already scanned.
     *
     * The read side is shared, preserving the flag-controlled multi-producer behavior below. The write
     * side is held only for bounded atomic slot/index work; potentially slow closeFrame calls run after
     * it is released. activeClearOperations remains non-zero until those closes finish so an overlapping
     * producer cannot transfer ownership into a stopped dispatcher.
     */
    private val queueOwnershipLock = ReentrantReadWriteLock()
    private val queueOperationLock = queueOwnershipLock.readLock()
    private val queueClearLock = queueOwnershipLock.writeLock()
    private val activeClearOperations = AtomicInteger(0)

    // FIX D-1(B): RealtimeFrameDispatcher is a multi-producer / single-consumer ring. offer() picks its
    // ring slot from writeIndex.get() and only later calls writeIndex.incrementAndGet(), so two
    // producers (the Camera2 hardware-import listener thread and the hardware-dispatch executor thread)
    // can read the same writeIndex, target the SAME slot, and one is spuriously rejected/conflated even
    // while another slot is free - a smoothness/temporal downgrade (a false certification failure in
    // strict high-end mode). Serialize offer() so slot selection + slot claim + writeIndex advance are
    // one atomic unit. A bare getAndIncrement() is deliberately NOT used: this ring advances writeIndex
    // only on ACCEPT (the producer-race-reject / producer-cas-reject paths intentionally leave it
    // untouched); advancing it on every offer would leave the writer pointing at an occupied slot after
    // any reject and spuriously reject later frames that had a free slot - a regression. The lock keeps
    // the existing semantics exactly. Gated by HardwareDrainSerializationFlag (default ON); flag OFF
    // restores the shipped producer interleaving while the lifecycle ownership barrier remains active.
    private val producerLock = Any()

    fun offer(
        frame: T,
        sensorTimestampNs: Long,
        nowNs: Long,
        allowStaleFrameConflation: Boolean = allowsStaleFrameConflation
    ): RealtimeFrameOfferResult {
        if (!tryAcquireQueueOperation()) {
            rejectedFrames.incrementAndGet()
            return RealtimeFrameOfferResult(
                accepted = false,
                reason = "dispatcher-clear-in-progress-nonblocking-reject"
            )
        }
        try {
            if (!HardwareDrainSerializationFlag.enabled) {
                return offerInternal(frame, sensorTimestampNs, nowNs, allowStaleFrameConflation)
            }
            return synchronized(producerLock) {
                offerInternal(frame, sensorTimestampNs, nowNs, allowStaleFrameConflation)
            }
        } finally {
            queueOperationLock.unlock()
        }
    }

    private fun tryAcquireQueueOperation(): Boolean {
        if (activeClearOperations.get() != 0) return false
        if (!queueOperationLock.tryLock()) return false
        if (activeClearOperations.get() == 0) return true
        queueOperationLock.unlock()
        return false
    }

    private fun offerInternal(
        frame: T,
        sensorTimestampNs: Long,
        nowNs: Long,
        allowStaleFrameConflation: Boolean
    ): RealtimeFrameOfferResult {
        val queued = RealtimeQueuedFrame(frame, sensorTimestampNs, nowNs)
        val index = (writeIndex.get() % safeCapacity).toInt()
        val existing = slots.get(index)
        if (existing != null) {
            val ageNs = (nowNs - existing.enqueuedAtNs).coerceAtLeast(0L)
            if (ageNs < acquireGuardNs) {
                rejectedFrames.incrementAndGet()
                return RealtimeFrameOfferResult(
                    accepted = false,
                    reason = "producer-nonblocking-reject ageNs=$ageNs guardNs=$acquireGuardNs"
                )
            }
            if (!allowStaleFrameConflation) {
                rejectedFrames.incrementAndGet()
                return RealtimeFrameOfferResult(
                    accepted = false,
                    reason = "strict-no-conflation-backpressure ageNs=$ageNs guardNs=$acquireGuardNs"
                )
            }
            if (slots.compareAndSet(index, existing, queued)) {
                closeFrame(existing.frame)
                droppedFrames.incrementAndGet()
                acceptedFrames.incrementAndGet()
                totalQueueLatencyNs.addAndGet(ageNs)
                queueLatencySamples.incrementAndGet()
                writeIndex.incrementAndGet()
                return RealtimeFrameOfferResult(
                    accepted = true,
                    reason = "conflated-oldest ageNs=$ageNs guardNs=$acquireGuardNs"
                )
            }
            rejectedFrames.incrementAndGet()
            return RealtimeFrameOfferResult(
                accepted = false,
                reason = "producer-race-reject"
            )
        }

        if (!slots.compareAndSet(index, null, queued)) {
            rejectedFrames.incrementAndGet()
            return RealtimeFrameOfferResult(
                accepted = false,
                reason = "producer-cas-reject"
            )
        }
        acceptedFrames.incrementAndGet()
        writeIndex.incrementAndGet()
        return RealtimeFrameOfferResult(
            accepted = true,
            reason = "accepted"
        )
    }

    fun poll(nowNs: Long = System.nanoTime()): RealtimeQueuedFrame<T>? {
        if (!tryAcquireQueueOperation()) return null
        try {
            return pollInternal(nowNs)
        } finally {
            queueOperationLock.unlock()
        }
    }

    private fun pollInternal(nowNs: Long): RealtimeQueuedFrame<T>? {
        val index = (readIndex.get() % safeCapacity).toInt()
        val frame = slots.get(index) ?: return null
        if (!slots.compareAndSet(index, frame, null)) return null
        readIndex.incrementAndGet()
        totalQueueLatencyNs.addAndGet((nowNs - frame.enqueuedAtNs).coerceAtLeast(0L))
        queueLatencySamples.incrementAndGet()
        return frame
    }

    /**
     * Preview-only dequeue. Historical monitoring frames are released and the newest valid frame wins.
     * The recording path must continue to call [poll] so its accepted frames remain strictly ordered.
     */
    fun pollLatest(
        nowNs: Long = System.nanoTime(),
        maxFrameAgeNs: Long = Long.MAX_VALUE,
        onSkipped: (RealtimeQueuedFrame<T>, Boolean) -> Unit = { _, _ -> }
    ): RealtimeQueuedFrame<T>? {
        var firstReleaseFailure: Throwable? = null
        val recordReleaseFailure: (Throwable) -> Unit = { failure ->
            val first = firstReleaseFailure
            if (first == null) {
                firstReleaseFailure = failure
            } else if (first !== failure) {
                first.addSuppressed(failure)
            }
        }
        var latest: RealtimeQueuedFrame<T>? = null
        while (true) {
            val candidate = poll(nowNs) ?: break
            val currentLatest = latest
            if (currentLatest == null) {
                latest = candidate
            } else if (isNewerPreviewFrame(candidate, currentLatest)) {
                latest = candidate
                releaseSkippedPreviewFrame(
                    currentLatest,
                    stale = false,
                    onSkipped,
                    recordReleaseFailure
                )
            } else {
                releaseSkippedPreviewFrame(
                    candidate,
                    stale = false,
                    onSkipped,
                    recordReleaseFailure
                )
            }
        }
        val resolved = latest ?: return null
        val stale = maxFrameAgeNs != Long.MAX_VALUE &&
            (nowNs - resolved.enqueuedAtNs).coerceAtLeast(0L) > maxFrameAgeNs.coerceAtLeast(0L)
        if (stale) {
            stalePreviewFrames.incrementAndGet()
            releaseSkippedPreviewFrame(
                resolved,
                stale = true,
                onSkipped,
                recordReleaseFailure
            )
            firstReleaseFailure?.let { throw it }
            return null
        }
        val failureBeforeReturn = firstReleaseFailure
        if (failureBeforeReturn != null) {
            // The caller cannot receive ownership when this operation propagates a release failure.
            // Terminally close the selected frame too, after every dequeued historical frame has been
            // handled, and retain any close failure as a suppressed cause.
            closePreviewFrame(resolved.frame, recordReleaseFailure)
            throw firstReleaseFailure ?: failureBeforeReturn
        }
        return resolved
    }

    private fun isNewerPreviewFrame(
        candidate: RealtimeQueuedFrame<T>,
        current: RealtimeQueuedFrame<T>
    ): Boolean {
        val candidateHasSensorTime = candidate.sensorTimestampNs > 0L
        val currentHasSensorTime = current.sensorTimestampNs > 0L
        if (candidateHasSensorTime != currentHasSensorTime) {
            return candidateHasSensorTime
        }
        if (
            candidateHasSensorTime &&
            currentHasSensorTime &&
            candidate.sensorTimestampNs != current.sensorTimestampNs
        ) {
            return candidate.sensorTimestampNs > current.sensorTimestampNs
        }
        if (candidate.enqueuedAtNs != current.enqueuedAtNs) {
            return candidate.enqueuedAtNs > current.enqueuedAtNs
        }
        return candidate.sensorTimestampNs > current.sensorTimestampNs
    }

    private fun releaseSkippedPreviewFrame(
        frame: RealtimeQueuedFrame<T>,
        stale: Boolean,
        onSkipped: (RealtimeQueuedFrame<T>, Boolean) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        skippedPreviewFrames.incrementAndGet()
        try {
            onSkipped(frame, stale)
        } catch (failure: Throwable) {
            onFailure(failure)
        }
        // Ownership was removed from the ring by poll(); this is its single terminal release. Callback
        // failure cannot bypass it, and close failure cannot prevent later dequeued frames from release.
        closePreviewFrame(frame.frame, onFailure)
    }

    private fun closePreviewFrame(frame: T, onFailure: (Throwable) -> Unit) {
        try {
            closeFrame(frame)
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }

    fun hasQueuedFrames(): Boolean {
        return queuedFrameCount() > 0
    }

    fun queuedFrameCount(): Int {
        var count = 0
        for (i in 0 until safeCapacity) {
            if (slots.get(i) != null) count++
        }
        return count
    }

    fun telemetry(): RealtimeFrameDispatchTelemetry {
        val accepted = acceptedFrames.get()
        val latencySamples = queueLatencySamples.get()
        return RealtimeFrameDispatchTelemetry(
            acceptedFrames = accepted,
            droppedFrames = droppedFrames.get(),
            rejectedFrames = rejectedFrames.get(),
            averageQueueLatencyNs = if (latencySamples == 0L) {
                0L
            } else {
                totalQueueLatencyNs.get() / latencySamples
            },
            queuedFrames = queuedFrameCount(),
            skippedPreviewFrames = skippedPreviewFrames.get(),
            stalePreviewFrames = stalePreviewFrames.get(),
            queueLatencySamples = latencySamples
        )
    }

    fun clear() {
        activeClearOperations.incrementAndGet()
        try {
            val detachedFrames = ArrayList<T>(safeCapacity)
            queueClearLock.lock()
            try {
                for (i in 0 until safeCapacity) {
                    slots.getAndSet(i, null)?.let { detachedFrames += it.frame }
                }
                writeIndex.set(0L)
                readIndex.set(0L)
                acceptedFrames.set(0L)
                droppedFrames.set(0L)
                rejectedFrames.set(0L)
                skippedPreviewFrames.set(0L)
                stalePreviewFrames.set(0L)
                totalQueueLatencyNs.set(0L)
                queueLatencySamples.set(0L)
            } finally {
                queueClearLock.unlock()
            }
            closeAllDetachedFrames(detachedFrames)
        } finally {
            activeClearOperations.decrementAndGet()
        }
    }

    private fun closeAllDetachedFrames(frames: List<T>) {
        var firstFailure: Throwable? = null
        for (frame in frames) {
            try {
                closeFrame(frame)
            } catch (failure: Throwable) {
                val previousFailure = firstFailure
                if (previousFailure == null) {
                    firstFailure = failure
                } else {
                    previousFailure.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}

class SensorPresentationClock(initialLatencyNs: Long = 5_000_000L) {
    private companion object {
        const val MAX_SENSOR_CLOCK_SKEW_NS = 250_000_000L
        const val MAX_PRESENTATION_LEAD_NS = 33_333_333L
    }

    private val latencyEwmaNs = AtomicLong(initialLatencyNs.coerceAtLeast(0L))
    private val lastPresentationNs = AtomicLong(0L)

    fun presentationTimeFor(sensorTimestampNs: Long, nowNs: Long): Long {
        val sensorClockIsUsable =
            sensorTimestampNs > 0L &&
                kotlin.math.abs(sensorTimestampNs - nowNs) <= MAX_SENSOR_CLOCK_SKEW_NS
        val baseTimestamp = if (sensorClockIsUsable) sensorTimestampNs else nowNs
        val maxFutureTimestamp = nowNs + MAX_PRESENTATION_LEAD_NS
        val candidate = (baseTimestamp + latencyEwmaNs.get()).coerceAtMost(maxFutureTimestamp)
        while (true) {
            val previous = lastPresentationNs.get()
            val monotonicCandidate = if (previous > 0L) {
                maxOf(candidate, previous + 1L)
            } else {
                candidate
            }
            if (lastPresentationNs.compareAndSet(previous, monotonicCandidate)) {
                return monotonicCandidate
            }
        }
    }

    fun recordGpuCompletion(sensorTimestampNs: Long, completionNs: Long) {
        if (sensorTimestampNs <= 0L || completionNs <= sensorTimestampNs) return
        val measured = (completionNs - sensorTimestampNs).coerceAtMost(100_000_000L)
        while (true) {
            val current = latencyEwmaNs.get()
            val next = ((current * 4L) + measured) / 5L
            if (latencyEwmaNs.compareAndSet(current, next)) return
        }
    }

    fun telemetrySignature(): String =
        "sensor-to-display-pts=ewma|latencyNs=${latencyEwmaNs.get()}|presentation=monotonic-safe"
}

data class DualGainAlignmentDecision(
    val driftNs: Long,
    val requiresOpticalFlowWarp: Boolean,
    val shaderDirective: String
)

data class ProcessingStageTuning(
    val deconvolutionIterations: Int,
    val temporalHistoryFrames: Int,
    val wavefrontEnabled: Boolean,
    val previewMustContinue: Boolean,
    val qualityDowngradeApplied: Boolean = false,
    val strictAdmissionFailure: Boolean = false,
    val reason: String
)

object PipelineOrchestrator {
    const val FRAME_ACQUIRE_GUARD_NS = 2_000_000L

    fun resolveDualGainAlignment(
        longExposureTimestampNs: Long,
        shortExposureTimestampNs: Long
    ): DualGainAlignmentDecision {
        val drift = abs(longExposureTimestampNs - shortExposureTimestampNs)
        val warp = drift > DualGainHdrFusionPolicy.MAX_TIMESTAMP_SKEW_NS
        return DualGainAlignmentDecision(
            driftNs = drift,
            requiresOpticalFlowWarp = warp,
            shaderDirective = if (warp) {
                "warp_reconstruct.comp|subPixelOpticalFlow=true|guardNs=${DualGainHdrFusionPolicy.MAX_TIMESTAMP_SKEW_NS}"
            } else {
                "dual_gain_fusion.comp|directFusion=true"
            }
        )
    }

    fun resolveBackpressureTuning(
        droppedFrames: Long,
        rejectedFrames: Long,
        averageQueueLatencyNs: Long,
        strictNoQualityDowngrade: Boolean = false
    ): ProcessingStageTuning {
        val severe = droppedFrames >= 10L ||
            rejectedFrames >= 4L ||
            averageQueueLatencyNs > FRAME_ACQUIRE_GUARD_NS * 2L
        val moderate = droppedFrames >= 3L ||
            rejectedFrames >= 1L ||
            averageQueueLatencyNs > FRAME_ACQUIRE_GUARD_NS
        if (strictNoQualityDowngrade && (severe || moderate)) {
            return ProcessingStageTuning(
                deconvolutionIterations = 5,
                temporalHistoryFrames = 12,
                wavefrontEnabled = true,
                previewMustContinue = true,
                qualityDowngradeApplied = false,
                strictAdmissionFailure = true,
                reason = "strict-high-end-backpressure-refuse-quality-downgrade"
            )
        }
        return when {
            severe -> ProcessingStageTuning(
                deconvolutionIterations = 2,
                temporalHistoryFrames = 6,
                wavefrontEnabled = true,
                previewMustContinue = true,
                qualityDowngradeApplied = true,
                reason = "severe-backpressure-shed-heavy-iterations"
            )
            moderate -> ProcessingStageTuning(
                deconvolutionIterations = 3,
                temporalHistoryFrames = 8,
                wavefrontEnabled = true,
                previewMustContinue = true,
                qualityDowngradeApplied = true,
                reason = "moderate-backpressure-reduce-history"
            )
            else -> ProcessingStageTuning(
                deconvolutionIterations = 5,
                temporalHistoryFrames = 12,
                wavefrontEnabled = true,
                previewMustContinue = true,
                reason = "stable-full-quality"
            )
        }
    }
}
