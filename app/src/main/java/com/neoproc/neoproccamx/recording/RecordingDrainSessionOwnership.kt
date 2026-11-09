package com.neoproc.neoproccamx.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable identity and completion barrier for one encoder-drain generation.
 *
 * A codec output producer may outlive `MediaCodec.stop()` on vendor implementations.  Keeping its
 * ownership separate from the mutable RecordingManager fields makes that late producer harmless:
 * retirement closes admission immediately, cancels every attached job, and a replacement generation
 * cannot open until all producers from the retired generation have actually completed.
 */
internal class RecordingDrainSession internal constructor(
    val generation: Long
) {
    private val lifecycleLock = Any()
    private val acceptingOutput = AtomicBoolean(true)
    private val muxerQueueGeneration = AtomicLong(UNBOUND_QUEUE_GENERATION)
    private val videoTrack = AtomicInteger(UNPUBLISHED_TRACK)
    private val audioTrack = AtomicInteger(UNPUBLISHED_TRACK)

    private val videoLane = DrainLane()
    private val audioLane = DrainLane()
    private val tickerLane = DrainLane()

    /** Audio terminal state is generation-local; an old AAC drain cannot poison a new session. */
    val audioCodecTerminalShutdown = AtomicBoolean(false)

    /** Error recovery is generation-local for the same reason. */
    val audioDrainErrorCount = AtomicInteger(0)

    fun acceptsProducerOutput(): Boolean = acceptingOutput.get()

    /**
     * Runs a short publication/start transaction atomically with respect to retirement.
     * Long-running codec work must stay outside this lease.
     */
    fun <T> withProducerLease(block: () -> T): T? = synchronized(lifecycleLock) {
        if (!acceptingOutput.get()) null else block()
    }

    fun bindMuxerQueueGeneration(generation: Long) {
        require(generation >= 0L) { "Muxer queue generation must be non-negative" }
        check(acceptingOutput.get()) { "Cannot bind a retired recording drain session" }
        val previous = muxerQueueGeneration.get()
        check(
            previous == generation ||
                (previous == UNBOUND_QUEUE_GENERATION &&
                    muxerQueueGeneration.compareAndSet(UNBOUND_QUEUE_GENERATION, generation))
        ) {
            "Recording drain session already owns muxer generation $previous, not $generation"
        }
    }

    fun ownsMuxerQueueGeneration(generation: Long): Boolean =
        acceptingOutput.get() && muxerQueueGeneration.get() == generation

    fun muxerQueueGenerationOrNull(): Long? =
        muxerQueueGeneration.get().takeUnless { it == UNBOUND_QUEUE_GENERATION }

    fun publishVideoTrack(trackIndex: Int): Boolean =
        publishTrack(videoTrack, trackIndex)

    fun publishAudioTrack(trackIndex: Int): Boolean =
        publishTrack(audioTrack, trackIndex)

    fun videoTrackIndex(): Int = videoTrack.get()

    fun audioTrackIndex(): Int = audioTrack.get()

    fun attachVideoDrain(cancelAction: () -> Unit): Boolean =
        attachLane(videoLane, cancelAction)

    fun attachAudioDrain(cancelAction: () -> Unit): Boolean =
        attachLane(audioLane, cancelAction)

    fun attachTicker(cancelAction: () -> Unit): Boolean =
        attachLane(tickerLane, cancelAction)

    fun completeVideoDrain() = videoLane.complete()

    fun completeAudioDrain() = audioLane.complete()

    fun completeTicker() = tickerLane.complete()

    /** Idempotently closes producer admission and cooperatively cancels every attached job. */
    fun retire() {
        val retiredNow = synchronized(lifecycleLock) {
            acceptingOutput.compareAndSet(true, false)
        }
        if (!retiredNow) return
        videoLane.retire()
        audioLane.retire()
        tickerLane.retire()
    }

    fun awaitTermination(timeoutMs: Long): RecordingDrainTermination {
        val deadlineNs = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val videoComplete = videoLane.awaitUntil(deadlineNs)
        val audioComplete = audioLane.awaitUntil(deadlineNs)
        val tickerComplete = tickerLane.awaitUntil(deadlineNs)
        return RecordingDrainTermination(
            videoComplete = videoComplete,
            audioComplete = audioComplete,
            tickerComplete = tickerComplete
        )
    }

    fun isTerminated(): Boolean =
        videoLane.isComplete() && audioLane.isComplete() && tickerLane.isComplete()

    private fun publishTrack(target: AtomicInteger, trackIndex: Int): Boolean {
        if (trackIndex < 0 || !acceptingOutput.get()) return false
        val previous = target.get()
        return previous == trackIndex ||
            (previous == UNPUBLISHED_TRACK && target.compareAndSet(UNPUBLISHED_TRACK, trackIndex))
    }

    private fun attachLane(lane: DrainLane, cancelAction: () -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (!acceptingOutput.get()) false else lane.attach(cancelAction)
        }

    private class DrainLane {
        private val lock = Any()
        private val completion = CountDownLatch(1)
        private val cancelAction = AtomicReference<(() -> Unit)?>(null)
        private var attached = false
        private var retired = false

        fun attach(action: () -> Unit): Boolean {
            val cancelImmediately = synchronized(lock) {
                check(!attached) { "Drain lane may only be attached once" }
                attached = true
                cancelAction.set(action)
                retired
            }
            if (cancelImmediately) {
                runCatching(action)
                completion.countDown()
                return false
            }
            return true
        }

        fun retire() {
            val action = synchronized(lock) {
                retired = true
                if (!attached) completion.countDown()
                cancelAction.get()
            }
            if (action != null) runCatching(action)
        }

        fun complete() {
            cancelAction.set(null)
            completion.countDown()
        }

        fun awaitUntil(deadlineNs: Long): Boolean {
            if (completion.count == 0L) return true
            val remainingNs = (deadlineNs - System.nanoTime()).coerceAtLeast(0L)
            return try {
                completion.await(remainingNs, TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun isComplete(): Boolean = completion.count == 0L
    }

    private companion object {
        const val UNBOUND_QUEUE_GENERATION = -1L
        const val UNPUBLISHED_TRACK = -1
    }
}

internal data class RecordingDrainTermination(
    val videoComplete: Boolean,
    val audioComplete: Boolean,
    val tickerComplete: Boolean
) {
    val complete: Boolean
        get() = videoComplete && audioComplete && tickerComplete
}

/**
 * Serializes session replacement and retains the last retired generation as a fail-closed barrier.
 */
internal class RecordingDrainSessionOwnership {
    private val mutationLock = Any()
    private val nextGeneration = AtomicLong(0L)
    private val activeSession = AtomicReference<RecordingDrainSession?>(null)
    private var retiredBarrier: RecordingDrainSession? = null

    fun open(): RecordingDrainSession = synchronized(mutationLock) {
        check(activeSession.get() == null) { "A recording drain session is already active" }
        retiredBarrier?.let { retired ->
            check(retired.isTerminated()) {
                "Recording drain generation ${retired.generation} has not terminated"
            }
        }
        RecordingDrainSession(nextGeneration.incrementAndGet()).also(activeSession::set)
    }

    fun currentOrNull(): RecordingDrainSession? = activeSession.get()

    fun isCurrent(session: RecordingDrainSession): Boolean =
        activeSession.get() === session && session.acceptsProducerOutput()

    fun retireCurrent(): RecordingDrainSession? = synchronized(mutationLock) {
        activeSession.getAndSet(null)?.also(::retireLocked)
    }

    /** Returns null for stale/idempotent teardown, without touching the replacement generation. */
    fun retire(expected: RecordingDrainSession): RecordingDrainSession? = synchronized(mutationLock) {
        if (!activeSession.compareAndSet(expected, null)) return@synchronized null
        expected.also(::retireLocked)
    }

    private fun retireLocked(session: RecordingDrainSession) {
        session.retire()
        retiredBarrier = session
    }
}
