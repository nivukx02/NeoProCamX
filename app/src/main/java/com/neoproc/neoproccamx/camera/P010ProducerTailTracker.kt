package com.neoproc.neoproccamx.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal sealed interface P010CapturedProducerTail {
    data class Proven(val frameNumber: Long, val sensorTimestampNs: Long) : P010CapturedProducerTail
    data class Failed(val reason: String) : P010CapturedProducerTail
}

/**
 * Proves the last already-requested P010 exposure without requesting an extra control exposure.
 * Tracks repeating replacements AND one-shot controls. Sequence completion alone is insufficient:
 * the greatest completed frame number must match an actual capture result and every submitted
 * sequence must finish normally. Image ownership and writer durability are proven separately.
 *
 * Registration and Camera2 callback delivery are serialized by the controller's submission lock.
 */
internal class P010ProducerTailTracker<T : Any>(val session: T, val generation: Long) {
    private val lock = Any()
    private val pendingSequences = mutableSetOf<Int>()
    private val knownSequences = linkedSetOf<Int>()
    private val completion = CompletableFuture<P010CapturedProducerTail>()
    private var frozen = false
    private var latestFrameNumber = -1L
    private var latestSensorTimestampNs = 0L
    private var latestCompletedFrameNumber = -1L

    fun belongsTo(candidate: T, candidateGeneration: Long): Boolean =
        session === candidate && generation == candidateGeneration

    fun isFrozen(): Boolean = synchronized(lock) { frozen }
    internal fun pendingSequenceCount(): Int = synchronized(lock) { pendingSequences.size }

    fun registerSequence(sequenceId: Int): Boolean = synchronized(lock) {
        if (frozen || completion.isDone || sequenceId < 0 ||
            pendingSequences.size >= MAX_PENDING_SEQUENCES || !pendingSequences.add(sequenceId)) return false
        knownSequences.add(sequenceId)
        // Retain recently completed identities for delayed results, never evict a live sequence.
        val iterator = knownSequences.iterator()
        while (knownSequences.size > RETAINED_IDENTITIES && iterator.hasNext()) {
            if (iterator.next() !in pendingSequences) iterator.remove()
        }
        true
    }

    fun onFrameCompleted(
        candidate: T, candidateGeneration: Long, sequenceId: Int,
        frameNumber: Long, sensorTimestampNs: Long
    ): Boolean = synchronized(lock) {
        if (!belongsTo(candidate, candidateGeneration) || sequenceId !in knownSequences ||
            completion.isDone) return false
        if (frameNumber < 0L || sensorTimestampNs <= 0L) {
            if (frozen) failLocked("P010 producer result lacks exact frame/timestamp identity")
            return false
        }
        if (frameNumber == latestFrameNumber && sensorTimestampNs != latestSensorTimestampNs) {
            failLocked("P010 producer frame changed its sensor timestamp")
            return false
        }
        if (frameNumber > latestFrameNumber) {
            if (latestFrameNumber >= 0L && sensorTimestampNs <= latestSensorTimestampNs) {
                failLocked("P010 producer sensor timestamp regressed")
                return false
            }
            latestFrameNumber = frameNumber
            latestSensorTimestampNs = sensorTimestampNs
        }
        completeIfProvenLocked()
        true
    }

    fun onSequenceCompleted(
        candidate: T, candidateGeneration: Long, sequenceId: Int, lastFrameNumber: Long
    ): Boolean = synchronized(lock) {
        if (!belongsTo(candidate, candidateGeneration) || completion.isDone ||
            sequenceId !in knownSequences) return false
        if (lastFrameNumber < 0L) {
            if (frozen) failLocked("P010 sequence completion has no captured tail frame")
            pendingSequences.remove(sequenceId)
            return false
        }
        pendingSequences.remove(sequenceId)
        latestCompletedFrameNumber = maxOf(latestCompletedFrameNumber, lastFrameNumber)
        completeIfProvenLocked()
        true
    }

    fun onSequenceAborted(candidate: T, candidateGeneration: Long, sequenceId: Int): Boolean =
        synchronized(lock) {
            if (!belongsTo(candidate, candidateGeneration) || !pendingSequences.remove(sequenceId)) {
                return false
            }
            if (frozen) failLocked("Camera2 aborted an owned P010 producer sequence id=$sequenceId")
            true
        }

    fun failWhileFrozen(candidate: T, candidateGeneration: Long, reason: String) = synchronized(lock) {
        if (frozen && belongsTo(candidate, candidateGeneration)) failLocked(reason)
    }

    fun failSequenceWhileFrozen(
        candidate: T, candidateGeneration: Long, sequenceId: Int, reason: String
    ) = synchronized(lock) {
        if (frozen && belongsTo(candidate, candidateGeneration) && sequenceId in knownSequences) {
            failLocked(reason)
        }
    }

    fun freeze(expectedRepeatingSequenceId: Int): Boolean = synchronized(lock) {
        if (frozen || completion.isDone || expectedRepeatingSequenceId !in pendingSequences) return false
        frozen = true
        completeIfProvenLocked()
        true
    }

    fun await(timeoutNanos: Long): P010CapturedProducerTail {
        if (timeoutNanos <= 0L) {
            synchronized(lock) { failLocked("P010 producer tail deadline expired") }
            return completion.getNow(P010CapturedProducerTail.Failed("P010 producer tail deadline expired"))
        }
        return try {
            completion.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(lock) { failLocked("P010 producer tail wait interrupted") }
            P010CapturedProducerTail.Failed("P010 producer tail wait interrupted")
        } catch (failure: Exception) {
            val reason = "P010 producer tail was not proven: ${failure.javaClass.simpleName}"
            synchronized(lock) { failLocked(reason) }
            P010CapturedProducerTail.Failed(reason)
        }
    }

    private fun completeIfProvenLocked() {
        if (frozen && pendingSequences.isEmpty() && latestFrameNumber >= 0L &&
            latestFrameNumber == latestCompletedFrameNumber && latestSensorTimestampNs > 0L) {
            completion.complete(P010CapturedProducerTail.Proven(latestFrameNumber, latestSensorTimestampNs))
        }
    }

    private fun failLocked(reason: String) {
        completion.complete(P010CapturedProducerTail.Failed(reason))
    }

    private companion object {
        const val RETAINED_IDENTITIES = 256
        const val MAX_PENDING_SEQUENCES = 256
    }
}
