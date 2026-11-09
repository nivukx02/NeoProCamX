package com.neoproc.neoproccamx.pipeline

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Recorder-owned RAW ingress policy for the project sensor-linear NDR path.
 *
 * The ImageReader budget includes the image currently processed by the GPU worker. The callback must
 * reserve before acquire and, when every legal acquisition is owned, wait outside the recorder
 * lifecycle lock for an exact release. Capacity pressure is flow control; native failure remains a
 * recording failure, while a healthy slow consumer must never turn the next unacquired frame into a
 * false hard-ceiling abort or a silent preview-style skip.
 */
internal object SensorLinearNdrRecordingIngressPolicy {
    private const val MIN_READER_IMAGES = 1
    private const val MAX_RETRY_POLL_NS = 1_000_000L
    private const val MIN_RETRY_POLL_NS = 250_000L
    private const val MIN_CAPACITY_WAIT_SLICE_NS = 4_000_000L
    private const val MAX_CAPACITY_WAIT_SLICE_NS = 50_000_000L
    private const val MIN_COMPLETION_DEADLINE_NS = 100_000_000L
    private const val MAX_COMPLETION_DEADLINE_NS = 750_000_000L
    private const val PROFILE_TRANSITION_FRAME_BUDGET = 12L
    private const val MIN_PROFILE_TRANSITION_DEADLINE_NS = 250_000_000L
    private const val MAX_PROFILE_TRANSITION_DEADLINE_NS = 1_500_000_000L

    /**
     * Hard application ownership ceiling. ImageReader permits exactly [readerMaxImages] acquired
     * Images; treating the last legal acquisition as an overflow aborts a valid startup burst.
     * Camera/HAL headroom is provided by the reader-depth policy, while a callback beyond this hard
     * ceiling is still rejected before acquireNextImage can throw.
     */
    fun ownedFrameLimit(readerMaxImages: Int): Int =
        readerMaxImages.coerceAtLeast(MIN_READER_IMAGES)

    /**
     * Atomically reserves ownership before ImageReader acquisition. This closes the window where a
     * stop or a second callback could observe an acquired Image that was not yet charged to the
     * recorder generation.
     *
     * @return the new owned count, or null when the hard ImageReader ceiling is already reserved.
     */
    fun tryReserveOwnedFrame(
        ownedFrames: AtomicInteger,
        readerMaxImages: Int
    ): Int? {
        val limit = ownedFrameLimit(readerMaxImages)
        while (true) {
            val current = ownedFrames.get()
            if (current >= limit) return null
            val reserved = current + 1
            if (ownedFrames.compareAndSet(current, reserved)) return reserved
        }
    }

    fun retryPollDelayNs(targetFps: Int): Long {
        val frameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
        return (frameIntervalNs / 8L).coerceIn(MIN_RETRY_POLL_NS, MAX_RETRY_POLL_NS)
    }

    /**
     * Capacity waiting is condition-driven, so this interval is only a lifecycle watchdog. It keeps a
     * lost vendor wake-up bounded without introducing completion-poll spin on the Camera2 ingress
     * thread.
     */
    fun capacityWaitSliceNs(targetFps: Int): Long =
        (1_000_000_000L / targetFps.coerceAtLeast(1))
            .coerceIn(MIN_CAPACITY_WAIT_SLICE_NS, MAX_CAPACITY_WAIT_SLICE_NS)

    fun completionDeadlineNs(targetFps: Int): Long {
        val frameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
        return (frameIntervalNs * 4L).coerceIn(
            MIN_COMPLETION_DEADLINE_NS,
            MAX_COMPLETION_DEADLINE_NS
        )
    }

    /**
     * A render-profile replacement is a bounded control-plane handoff, not evidence that Vulkan,
     * EGL, or the preview ring is stalled. Give its Main-thread replay a fresh deadline without
     * extending the strict four-frame deadline used for physical output ownership/backpressure.
     */
    fun retryDeadlineNs(
        targetFps: Int,
        cause: SensorLinearNdrRecordingRetryCause
    ): Long = when (cause) {
        SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION -> {
            val frameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
            (frameIntervalNs * PROFILE_TRANSITION_FRAME_BUDGET).coerceIn(
                MIN_PROFILE_TRANSITION_DEADLINE_NS,
                MAX_PROFILE_TRANSITION_DEADLINE_NS
            )
        }
        SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT,
        SensorLinearNdrRecordingRetryCause.PREVIEW_TRANSPORT -> completionDeadlineNs(targetFps)
    }

    /**
     * Starts a new deadline only when the physical/control-plane reason changes. Repeated polls for
     * one cause retain their original deadline and therefore cannot hide a real hung GPU or surface.
     */
    fun transitionRetryWindow(
        current: SensorLinearNdrRecordingRetryWindow?,
        cause: SensorLinearNdrRecordingRetryCause,
        nowNs: Long
    ): SensorLinearNdrRecordingRetryWindow =
        if (current != null && current.cause == cause && current.startedNs > 0L) {
            current
        } else {
            restartRetryWindow(cause = cause, nowNs = nowNs)
        }

    /** A newly accepted frame is a new physical owner even when its cause enum is unchanged. */
    fun restartRetryWindow(
        cause: SensorLinearNdrRecordingRetryCause,
        nowNs: Long
    ): SensorLinearNdrRecordingRetryWindow = SensorLinearNdrRecordingRetryWindow(
        cause = cause,
        startedNs = nowNs.coerceAtLeast(1L)
    )

}

internal enum class SensorLinearNdrRecordingRetryCause {
    /** An accepted Vulkan output or exact EGL encoder-read fence is still physically owned. */
    NATIVE_OUTPUT,

    /** No frame was dispatched because the independent preview transport has no free slot. */
    PREVIEW_TRANSPORT,

    /** RAW output is drained and the retained immutable render profile is being committed. */
    RENDER_PROFILE_TRANSITION
}

internal data class SensorLinearNdrRecordingRetryWindow(
    val cause: SensorLinearNdrRecordingRetryCause,
    val startedNs: Long
)

internal enum class SensorLinearNdrCapacityWaitResult {
    AVAILABLE,
    STILL_FULL,
    ADMISSION_CLOSED,
    INTERRUPTED
}

/**
 * Lost-wake-safe capacity notification for the recorder's pre-acquire reservation gate.
 *
 * The Camera2 callback never waits while holding RawVideoManager's lifecycle or result locks. Exact
 * Image retirement signals this condition after decrementing ownership, so a full reader resumes as
 * soon as one legal acquisition is available. A timed slice only revalidates lifecycle publication;
 * it is not a frame-drop or recording-failure deadline.
 */
internal class SensorLinearNdrRecordingCapacityGate {
    private val lock = ReentrantLock()
    private val capacityChanged = lock.newCondition()

    fun awaitCapacity(
        ownedFrames: AtomicInteger,
        readerMaxImages: Int,
        waitSliceNs: Long,
        admissionIsCurrent: () -> Boolean
    ): SensorLinearNdrCapacityWaitResult = lock.withLock {
        if (!admissionIsCurrent()) {
            return@withLock SensorLinearNdrCapacityWaitResult.ADMISSION_CLOSED
        }
        val limit = SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(readerMaxImages)
        if (ownedFrames.get() < limit) {
            return@withLock SensorLinearNdrCapacityWaitResult.AVAILABLE
        }
        try {
            capacityChanged.awaitNanos(waitSliceNs.coerceAtLeast(1L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@withLock SensorLinearNdrCapacityWaitResult.INTERRUPTED
        }
        when {
            !admissionIsCurrent() -> SensorLinearNdrCapacityWaitResult.ADMISSION_CLOSED
            ownedFrames.get() < limit -> SensorLinearNdrCapacityWaitResult.AVAILABLE
            else -> SensorLinearNdrCapacityWaitResult.STILL_FULL
        }
    }

    fun signalCapacityChanged() {
        lock.withLock { capacityChanged.signalAll() }
    }
}

/**
 * Identity-based release permits for acquired Android Images.
 *
 * Image equality is deliberately ignored: only the exact object registered immediately after a
 * successful acquire can be claimed once for close/decrement. Generation is part of the permit so a
 * delayed old callback can never retire a restarted recorder's ownership budget.
 */
internal class SensorLinearNdrExactImageOwnership {
    private val lock = ReentrantLock()
    private val owners = IdentityHashMap<Any, Long>()

    fun register(image: Any, ingressGeneration: Long): Boolean = lock.withLock {
        if (owners.containsKey(image)) return@withLock false
        owners[image] = ingressGeneration
        true
    }

    fun claimRelease(image: Any, ingressGeneration: Long): Boolean = lock.withLock {
        if (owners[image] != ingressGeneration) return@withLock false
        owners.remove(image)
        true
    }

    fun outstandingCount(): Int = lock.withLock { owners.size }
}

/**
 * Exact-owner operations shared by recorder stop and deadline failure paths.
 *
 * The returned value is the caller's release permit. A null result means another path already claimed
 * the object and is solely responsible for closing/decrementing it.
 */
internal object SensorLinearNdrExactOwnership {
    fun <T : Any> claim(slot: AtomicReference<T?>, expected: T?): T? {
        if (expected == null) return null
        return if (slot.compareAndSet(expected, null)) expected else null
    }
}

/**
 * Resolves caller ownership while the ordered join lock is still held.
 *
 * [transferredToJoin] is false when the failing image was never published to a join collection, so the
 * callback remains its owner. Once transferred, the callback may reclaim only the exact value still in
 * [readyBySequence]. A missing value has already moved to the recorder FIFO and remains drain-owned.
 */
internal object SensorLinearNdrJoinFailureOwnership {
    fun <K, V, O : Any> claimForRelease(
        transferredToJoin: Boolean,
        sequence: K,
        expectedOwner: O,
        readyBySequence: MutableMap<K, V>,
        ownerOf: (V) -> O
    ): Boolean {
        if (!transferredToJoin) return true
        val ready = readyBySequence[sequence] ?: return false
        if (ownerOf(ready) !== expectedOwner) return false
        readyBySequence.remove(sequence)
        return true
    }
}

/** Secondary sensor observers are never allowed to fail the protected recording admission. */
internal object SensorLinearNdrSecondarySnapshot {
    inline fun <T> capture(
        onFailure: (Throwable) -> Unit = {},
        snapshot: () -> T?
    ): T? = try {
        snapshot()
    } catch (failure: Throwable) {
        // Diagnostics are secondary as well; even a platform logger failure cannot escape this boundary.
        runCatching { onFailure(failure) }
        null
    }
}

internal enum class SensorLinearNdrRecordingSubmissionStatus {
    ACCEPTED,
    RETRY_PENDING,
    FAILED
}

internal data class SensorLinearNdrRecordingSubmission(
    val status: SensorLinearNdrRecordingSubmissionStatus,
    val completionPending: Boolean = false,
    val retryCause: SensorLinearNdrRecordingRetryCause? = null,
    val reason: String? = null
)

/** Immutable Camera2/ingress lineage carried through GPU completion and encoder submission. */
internal data class SensorLinearNdrRecordingFrameIdentity(
    val sequence: Long,
    val ingressGeneration: Long,
    val sessionGeneration: Long,
    val cameraFrameNumber: Long,
    val sensorTimestampNs: Long
)

internal enum class SensorLinearNdrRecordingCompletionStatus {
    IDLE,
    COMPLETED,
    PENDING,
    FAILED
}

internal data class SensorLinearNdrRecordingCompletion(
    val status: SensorLinearNdrRecordingCompletionStatus,
    val reason: String? = null
)

internal data class SensorLinearNdrRecordingFailure(
    val reason: String,
    val sensorTimestampNs: Long? = null,
    val cause: Throwable? = null
)

/**
 * Independent stop facts for the protected sensor-linear recording ingress.
 *
 * [teardownSafe] is an ownership proof: no Java Image or native/encoder submission can still reach
 * resources that the caller may retire. [ingressFailed] is the clip-quality verdict accumulated by
 * the strict admission/processing gates. A failed clip can therefore be safe to tear down without
 * being relabelled as successful, and callers must never infer one fact from the other.
 */
internal data class SensorLinearNdrRecordingStopResult(
    val teardownSafe: Boolean,
    val ingressFailed: Boolean
)
