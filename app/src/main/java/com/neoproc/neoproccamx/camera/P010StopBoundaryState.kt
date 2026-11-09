package com.neoproc.neoproccamx.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact identity of the repeating Camera2 sequence that still owns a P010 producer. */
internal data class P010RepeatingSequenceIdentity<T : Any>(
    val session: T,
    val sessionGeneration: Long,
    val sequenceId: Int
)

/** Terminal result of the complete Camera2 + ImageReader + recorder stop boundary. */
internal sealed interface P010StopBoundaryResult {
    data class Proven(val markerTimestampNs: Long) : P010StopBoundaryResult
    data class Failed(val reason: String) : P010StopBoundaryResult
}

/** Camera2 proof available before ImageReader/recorder ownership is established. */
internal sealed interface P010StopBoundaryCameraResult {
    data class MarkerReady(val markerTimestampNs: Long) : P010StopBoundaryCameraResult
    data class Failed(val reason: String) : P010StopBoundaryCameraResult
}

/**
 * Pure, thread-safe state machine for an exact NPV10 P010 stop boundary.
 *
 * Completion requires all of the following, with no timing guesses:
 *  1. every submitted P010 sequence completes and its exact captured tail is proven, OR the
 *     legacy protocol completes the old repeating sequence and one explicitly requested marker,
 *  2. the captured tail/legacy marker has a positive SENSOR_TIMESTAMP,
 *  3. the identity belongs to this exact session generation,
 *  4. the live recorder accepts ownership of the Image with that exact timestamp, and
 *  5. a callback-handler FIFO barrier runs after the accepting ImageReader callback returns.
 *
 * Marker Image and capture-result callbacks may arrive in either order. Duplicate exact callbacks
 * are idempotent; contradictory identities or timestamps fail closed. Once terminal, late callbacks
 * are inert and cannot prove or alter a later transaction.
 */
internal class P010StopBoundaryState<T : Any>(
    private val expectedRepeating: P010RepeatingSequenceIdentity<T>
) {
    private val lock = Any()
    private val terminal = CompletableFuture<P010StopBoundaryResult>()
    private val cameraTerminal = CompletableFuture<P010StopBoundaryCameraResult>()
    private val recorderOwnedTimestamps = linkedSetOf<Long>()
    private var repeatingCompleted = false
    private var markerSubmissionClaimed = false
    private var markerSequenceId = UNSET_SEQUENCE_ID
    private var markerSequenceCompleted = false
    private var markerTimestampNs = 0L
    private var markerOwnershipTransferred = false
    private var readerDrainClaimed = false
    private var readerFifoDrained = false
    private var completedCapturedTail = false
    private var capturedTailFrameNumber = -1L

    /** Alternative to an extra exposure: the controller has drained every registered sequence. */
    fun onCompletedCapturedProducerTail(
        session: T, sessionGeneration: Long, frameNumber: Long, sensorTimestampNs: Long
    ): Boolean = synchronized(lock) {
        if (terminal.isDone) return false
        if (!owns(session, sessionGeneration) || frameNumber < 0L || sensorTimestampNs <= 0L ||
            markerSubmissionClaimed ||
            (completedCapturedTail && (markerTimestampNs != sensorTimestampNs ||
                capturedTailFrameNumber != frameNumber))) {
            failLocked("P010 captured producer tail identity was not proven")
            return false
        }
        completedCapturedTail = true
        capturedTailFrameNumber = frameNumber
        markerTimestampNs = sensorTimestampNs
        markerOwnershipTransferred = recorderOwnedTimestamps.contains(sensorTimestampNs)
        completeIfProvenLocked()
        true
    }

    /** Returns true exactly once, authorizing marker submission after the exact old sequence ends. */
    fun onRepeatingSequenceCompleted(
        session: T,
        sessionGeneration: Long,
        sequenceId: Int
    ): Boolean = synchronized(lock) {
        if (terminal.isDone || completedCapturedTail || !owns(session, sessionGeneration) ||
            sequenceId != expectedRepeating.sequenceId
        ) {
            return false
        }
        repeatingCompleted = true
        if (markerSubmissionClaimed) return false
        markerSubmissionClaimed = true
        true
    }

    fun registerMarkerSequence(
        session: T,
        sessionGeneration: Long,
        sequenceId: Int
    ): Boolean = synchronized(lock) {
        if (terminal.isDone) return false
        if (!owns(session, sessionGeneration)) {
            failLocked("P010 stop marker was registered for a stale session generation")
            return false
        }
        if (!repeatingCompleted || !markerSubmissionClaimed) {
            failLocked("P010 stop marker was submitted before the old repeating sequence completed")
            return false
        }
        if (sequenceId < 0) {
            failLocked("P010 stop marker sequence id was invalid")
            return false
        }
        if (markerSequenceId != UNSET_SEQUENCE_ID && markerSequenceId != sequenceId) {
            failLocked("P010 stop marker sequence identity changed")
            return false
        }
        markerSequenceId = sequenceId
        completeIfProvenLocked()
        true
    }

    fun onMarkerCaptureResult(
        session: T,
        sessionGeneration: Long,
        sequenceId: Int,
        sensorTimestampNs: Long
    ): Boolean = synchronized(lock) {
        if (terminal.isDone) return false
        if (!ownsRegisteredMarker(session, sessionGeneration, sequenceId)) {
            failLocked("P010 stop marker result did not match its exact session and sequence")
            return false
        }
        if (sensorTimestampNs <= 0L) {
            failLocked("P010 stop marker did not provide a positive SENSOR_TIMESTAMP")
            return true
        }
        if (markerTimestampNs > 0L && markerTimestampNs != sensorTimestampNs) {
            failLocked("P010 stop marker SENSOR_TIMESTAMP changed across duplicate results")
            return true
        }
        markerTimestampNs = sensorTimestampNs
        markerOwnershipTransferred = recorderOwnedTimestamps.contains(sensorTimestampNs)
        completeIfProvenLocked()
        true
    }

    /** Called only after the live NPV10 recorder has accepted ownership of this exact Image. */
    fun onRecorderImageOwnershipTransferred(sensorTimestampNs: Long): Boolean = synchronized(lock) {
        if (terminal.isDone || sensorTimestampNs <= 0L) return false
        recorderOwnedTimestamps.add(sensorTimestampNs)
        if (markerTimestampNs == sensorTimestampNs) markerOwnershipTransferred = true
        completeIfProvenLocked()
        markerTimestampNs == sensorTimestampNs
    }

    fun onMarkerSequenceCompleted(
        session: T,
        sessionGeneration: Long,
        sequenceId: Int
    ): Boolean = synchronized(lock) {
        if (terminal.isDone) return false
        if (!ownsRegisteredMarker(session, sessionGeneration, sequenceId)) return false
        markerSequenceCompleted = true
        completeIfProvenLocked()
        true
    }

    /** Returns true exactly once when a handler FIFO barrier should be posted. */
    fun claimReaderFifoDrain(): Boolean = synchronized(lock) {
        if (terminal.isDone || !markerOwnershipTransferred || readerDrainClaimed) return false
        readerDrainClaimed = true
        true
    }

    fun onReaderFifoDrained(
        establishRecorderBoundary: (Long) -> Boolean = { true }
    ): Boolean = synchronized(lock) {
        if (terminal.isDone || !readerDrainClaimed || !markerOwnershipTransferred) return false
        // Linearize recorder publication ownership with the exact state transition to Proven. A
        // lifecycle poison racing this callback must win before proof or lose after establishment;
        // there is no proven-but-still-OPEN interval.
        if (!establishRecorderBoundary(markerTimestampNs)) {
            failLocked("NPV10 recorder refused exact producer-boundary establishment")
            return false
        }
        readerFifoDrained = true
        completeIfProvenLocked()
        true
    }

    fun onSequenceAborted(
        session: T,
        sessionGeneration: Long,
        sequenceId: Int
    ): Boolean = synchronized(lock) {
        if (terminal.isDone || !owns(session, sessionGeneration)) return false
        val ownedSequence = sequenceId == expectedRepeating.sequenceId ||
            (markerSequenceId != UNSET_SEQUENCE_ID && sequenceId == markerSequenceId)
        if (!ownedSequence) return false
        failLocked("Camera2 aborted an owned P010 stop sequence id=$sequenceId")
        true
    }

    fun onMarkerCaptureFailed(reason: String): Boolean =
        fail("P010 stop marker capture failed: $reason")

    fun fail(reason: String): Boolean = synchronized(lock) { failLocked(reason) }

    fun await(timeoutNanos: Long): P010StopBoundaryResult {
        if (timeoutNanos <= 0L) {
            fail("P010 stop boundary deadline expired")
            return terminal.getNow(P010StopBoundaryResult.Failed("P010 stop boundary deadline expired"))
        }
        return try {
            terminal.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            fail("P010 stop boundary timed out awaiting exact tail ownership")
            terminal.getNow(
                P010StopBoundaryResult.Failed(
                    "P010 stop boundary timed out awaiting exact tail ownership"
                )
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail("P010 stop boundary wait was interrupted")
            terminal.getNow(P010StopBoundaryResult.Failed("P010 stop boundary wait was interrupted"))
        } catch (failure: ExecutionException) {
            fail("P010 stop boundary completion failed: ${failure.cause?.message ?: failure.message}")
            terminal.getNow(P010StopBoundaryResult.Failed("P010 stop boundary completion failed"))
        }
    }

    fun awaitCameraMarker(timeoutNanos: Long): P010StopBoundaryCameraResult {
        if (timeoutNanos <= 0L) {
            fail("P010 stop Camera2 marker deadline expired")
            return cameraTerminal.getNow(
                P010StopBoundaryCameraResult.Failed("P010 stop Camera2 marker deadline expired")
            )
        }
        return try {
            cameraTerminal.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            fail("P010 stop boundary timed out awaiting exact Camera2 marker")
            cameraTerminal.getNow(
                P010StopBoundaryCameraResult.Failed(
                    "P010 stop boundary timed out awaiting exact Camera2 marker"
                )
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail("P010 stop Camera2 marker wait was interrupted")
            cameraTerminal.getNow(
                P010StopBoundaryCameraResult.Failed("P010 stop Camera2 marker wait was interrupted")
            )
        } catch (failure: ExecutionException) {
            fail("P010 stop Camera2 marker completion failed: ${failure.cause?.message ?: failure.message}")
            cameraTerminal.getNow(
                P010StopBoundaryCameraResult.Failed("P010 stop Camera2 marker completion failed")
            )
        }
    }

    fun markerSequenceId(): Int = synchronized(lock) { markerSequenceId }
    fun isTerminal(): Boolean = terminal.isDone

    private fun owns(session: T, sessionGeneration: Long): Boolean =
        expectedRepeating.session === session &&
            expectedRepeating.sessionGeneration == sessionGeneration

    private fun ownsRegisteredMarker(
        session: T,
        sessionGeneration: Long,
        sequenceId: Int
    ): Boolean = owns(session, sessionGeneration) &&
        markerSequenceId != UNSET_SEQUENCE_ID && markerSequenceId == sequenceId

    private fun completeIfProvenLocked() {
        val cameraProven = completedCapturedTail || (repeatingCompleted && markerSubmissionClaimed &&
            markerSequenceId != UNSET_SEQUENCE_ID && markerSequenceCompleted &&
            markerTimestampNs > 0L)
        if (cameraProven) {
            cameraTerminal.complete(P010StopBoundaryCameraResult.MarkerReady(markerTimestampNs))
        }
        if (cameraProven && markerOwnershipTransferred && readerFifoDrained) {
            terminal.complete(P010StopBoundaryResult.Proven(markerTimestampNs))
            recorderOwnedTimestamps.clear()
        }
    }

    private fun failLocked(reason: String): Boolean {
        cameraTerminal.complete(P010StopBoundaryCameraResult.Failed(reason))
        return terminal.complete(P010StopBoundaryResult.Failed(reason)).also { completed ->
            if (completed) recorderOwnedTimestamps.clear()
        }
    }

    private companion object {
        const val UNSET_SEQUENCE_ID = -1
    }
}
