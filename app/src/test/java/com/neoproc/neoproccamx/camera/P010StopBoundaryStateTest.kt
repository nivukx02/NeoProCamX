package com.neoproc.neoproccamx.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P010StopBoundaryStateTest {
    private val session = Any()

    private fun state(repeatingSequence: Int = 41) = P010StopBoundaryState(
        P010RepeatingSequenceIdentity(
            session = session,
            sessionGeneration = 7L,
            sequenceId = repeatingSequence
        )
    )

    private fun registerMarker(
        state: P010StopBoundaryState<Any>,
        repeatingSequence: Int = 41,
        markerSequence: Int = 42
    ) {
        assertTrue(state.onRepeatingSequenceCompleted(session, 7L, repeatingSequence))
        assertTrue(state.registerMarkerSequence(session, 7L, markerSequence))
    }

    private fun finishReaderProof(
        state: P010StopBoundaryState<Any>,
        timestampNs: Long
    ): P010StopBoundaryResult {
        assertTrue(state.onRecorderImageOwnershipTransferred(timestampNs))
        assertTrue(state.claimReaderFifoDrain())
        assertTrue(state.onReaderFifoDrained())
        return state.await(TimeUnit.SECONDS.toNanos(1L))
    }

    @Test
    fun `delayed old sequence alone authorizes exactly one marker submission`() {
        val state = state()

        assertFalse(state.onRepeatingSequenceCompleted(session, 7L, 40))
        assertTrue(state.onRepeatingSequenceCompleted(session, 7L, 41))
        assertFalse(state.onRepeatingSequenceCompleted(session, 7L, 41))
        assertTrue(state.registerMarkerSequence(session, 7L, 42))
    }

    @Test
    fun `submitting marker before old sequence completion fails closed`() {
        val state = state()

        assertFalse(state.registerMarkerSequence(session, 7L, 42))
        assertTrue(state.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test
    fun `buffered tail with Image before result completes only exact marker ownership`() {
        val state = state()
        registerMarker(state)

        assertFalse(state.onRecorderImageOwnershipTransferred(8_000L))
        assertFalse(state.onRecorderImageOwnershipTransferred(9_000L))
        assertTrue(state.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(state.onMarkerSequenceCompleted(session, 7L, 42))
        assertTrue(state.awaitCameraMarker(1L) is P010StopBoundaryCameraResult.MarkerReady)

        assertTrue(state.claimReaderFifoDrain())
        assertTrue(state.onReaderFifoDrained())
        assertEquals(P010StopBoundaryResult.Proven(9_000L), state.await(1L))
    }

    @Test
    fun `result before Image cannot complete until recorder owns exact timestamp`() {
        val state = state()
        registerMarker(state)

        assertTrue(state.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(state.onMarkerSequenceCompleted(session, 7L, 42))
        assertTrue(state.awaitCameraMarker(1L) is P010StopBoundaryCameraResult.MarkerReady)
        assertFalse(state.onRecorderImageOwnershipTransferred(9_001L))
        assertFalse(state.claimReaderFifoDrain())

        assertEquals(
            P010StopBoundaryResult.Proven(9_000L),
            finishReaderProof(state, 9_000L)
        )
    }

    @Test
    fun `marker sequence terminal is mandatory even after result and Image`() {
        val state = state()
        registerMarker(state)
        assertTrue(state.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(state.onRecorderImageOwnershipTransferred(9_000L))

        assertTrue(state.awaitCameraMarker(1L) is P010StopBoundaryCameraResult.Failed)
        assertFalse(state.onMarkerSequenceCompleted(session, 7L, 42))
    }

    @Test
    fun `timeout makes missing marker terminal and late callbacks inert`() {
        val state = state()
        assertTrue(state.awaitCameraMarker(1L) is P010StopBoundaryCameraResult.Failed)

        assertFalse(state.onRepeatingSequenceCompleted(session, 7L, 41))
        assertFalse(state.registerMarkerSequence(session, 7L, 42))
        assertFalse(state.onRecorderImageOwnershipTransferred(9_000L))
    }

    @Test
    fun `missing declined or unsupported marker Image can only fail`() {
        val state = state()
        registerMarker(state)
        assertTrue(state.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(state.onMarkerSequenceCompleted(session, 7L, 42))
        assertTrue(state.awaitCameraMarker(1L) is P010StopBoundaryCameraResult.MarkerReady)
        assertFalse(state.claimReaderFifoDrain())

        assertTrue(state.fail("exact marker was not accepted by the live sink"))
        assertTrue(state.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test
    fun `recorder boundary establishment is atomic with exact proven transition`() {
        val state = state()
        registerMarker(state)
        assertTrue(state.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(state.onMarkerSequenceCompleted(session, 7L, 42))
        assertTrue(state.onRecorderImageOwnershipTransferred(9_000L))
        assertTrue(state.claimReaderFifoDrain())
        val establishmentCalled = AtomicBoolean(false)
        val establishedTimestamp = AtomicReference<Long>()

        assertFalse(state.onReaderFifoDrained { markerTimestampNs ->
            establishmentCalled.set(true)
            establishedTimestamp.set(markerTimestampNs)
            false
        })
        assertTrue(establishmentCalled.get())
        assertEquals(9_000L, establishedTimestamp.get())
        assertTrue(state.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test
    fun `abort of old or marker sequence fails closed`() {
        val oldAbort = state()
        assertTrue(oldAbort.onSequenceAborted(session, 7L, 41))
        assertTrue(oldAbort.await(1L) is P010StopBoundaryResult.Failed)

        val markerAbort = state()
        registerMarker(markerAbort)
        assertTrue(markerAbort.onSequenceAborted(session, 7L, 42))
        assertTrue(markerAbort.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test
    fun `stale generation marker fails and cannot be replaced by current callback`() {
        val state = state()
        registerMarker(state)

        assertFalse(state.onMarkerCaptureResult(session, 8L, 42, 9_000L))
        assertFalse(state.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(state.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test
    fun `duplicate exact callbacks are idempotent but changed timestamp fails`() {
        val exact = state()
        registerMarker(exact)
        assertTrue(exact.registerMarkerSequence(session, 7L, 42))
        assertTrue(exact.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(exact.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(exact.onMarkerSequenceCompleted(session, 7L, 42))
        assertTrue(exact.onMarkerSequenceCompleted(session, 7L, 42))
        assertEquals(P010StopBoundaryResult.Proven(9_000L), finishReaderProof(exact, 9_000L))

        val changed = state()
        registerMarker(changed)
        assertTrue(changed.onMarkerCaptureResult(session, 7L, 42, 9_000L))
        assertTrue(changed.onMarkerCaptureResult(session, 7L, 42, 9_001L))
        assertTrue(changed.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test
    fun `interrupted await restores interrupt and fails terminal`() {
        val state = state()
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val result = AtomicReference<P010StopBoundaryCameraResult>()
        val thread = Thread {
            started.countDown()
            result.set(state.awaitCameraMarker(TimeUnit.MINUTES.toNanos(1L)))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        thread.start()
        assertTrue(started.await(1L, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(1_000L)

        assertTrue(result.get() is P010StopBoundaryCameraResult.Failed)
        assertTrue(interrupted.get())
        assertFalse(state.onRepeatingSequenceCompleted(session, 7L, 41))
    }
}
