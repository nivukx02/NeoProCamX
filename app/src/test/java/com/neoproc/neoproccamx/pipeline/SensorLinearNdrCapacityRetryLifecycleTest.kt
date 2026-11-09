package com.neoproc.neoproccamx.pipeline

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact host model for the RETRY_PENDING(completionPending) recorder ownership split. */
class SensorLinearNdrCapacityRetryLifecycleTest {
    private data class Frame(
        val sequence: Long,
        val sensorTimestampNs: Long,
        var closeCount: Int = 0,
        var submissionCount: Int = 0
    )

    private class Lifecycle {
        private val queue = ArrayDeque<Frame>()
        private val retrySlot = AtomicReference<Frame?>()

        var active = true
            private set
        var accepting = true
            private set
        var nativePending = false
            private set
        var discardRequested = false
            private set
        var destroyRequested = false
            private set
        var destroyed = false
            private set
        var ownedFrames = 0
            private set
        var finishCount = 0
            private set
        var failureCallbackCount = 0
            private set

        val retryFrame: Frame?
            get() = retrySlot.get()

        fun enqueue(frame: Frame) {
            check(active && accepting && !discardRequested)
            queue.addLast(frame)
            ownedFrames += 1
        }

        fun requestStop(discard: Boolean) {
            accepting = false
            discardRequested = discardRequested || discard
        }

        fun requestDestroy() {
            destroyRequested = true
            accepting = false
            discardRequested = true
        }

        fun requestRetryTimeout(expected: Frame): Boolean {
            val claimed = SensorLinearNdrExactOwnership.claim(retrySlot, expected) ?: return false
            close(claimed)
            failOnce()
            discardUnsubmitted()
            return true
        }

        fun turn(
            submit: (Frame) -> SensorLinearNdrRecordingSubmission,
            pollNative: () -> Boolean = { true }
        ) {
            check(active)
            if (discardRequested) discardUnsubmitted()
            val frame = retrySlot.getAndSet(null) ?: queue.pollFirst()
            if (frame != null) {
                if (discardRequested) {
                    close(frame)
                } else {
                    frame.submissionCount += 1
                    val submission = submit(frame)
                    when (submission.status) {
                        SensorLinearNdrRecordingSubmissionStatus.ACCEPTED -> {
                            close(frame)
                            nativePending = submission.completionPending
                        }

                        SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING -> {
                            check(retrySlot.compareAndSet(null, frame))
                            nativePending = submission.completionPending
                        }

                        SensorLinearNdrRecordingSubmissionStatus.FAILED -> {
                            close(frame)
                            failOnce()
                        }
                    }
                }
            } else if (nativePending && pollNative()) {
                nativePending = false
            }
            if (discardRequested) discardUnsubmitted()
            finishIfPossible()
        }

        fun isQuiescent(): Boolean =
            !active && !nativePending && retrySlot.get() == null && queue.isEmpty() && ownedFrames == 0

        private fun failOnce() {
            if (failureCallbackCount == 0) failureCallbackCount = 1
            accepting = false
            discardRequested = true
        }

        private fun discardUnsubmitted() {
            retrySlot.getAndSet(null)?.let(::close)
            while (queue.isNotEmpty()) close(queue.removeFirst())
        }

        private fun close(frame: Frame) {
            frame.closeCount += 1
            check(frame.closeCount == 1) { "frame ${frame.sequence} closed more than once" }
            ownedFrames -= 1
            check(ownedFrames >= 0) { "full-resolution owner count underflow" }
        }

        private fun finishIfPossible() {
            if (active && !accepting && !nativePending && retrySlot.get() == null &&
                queue.isEmpty() && ownedFrames == 0
            ) {
                active = false
                finishCount += 1
            }
            if (destroyRequested && isQuiescent()) destroyed = true
        }
    }

    @Test
    fun `capacity retries preserve exact identity sequence timestamp and close once on acceptance`() {
        val lifecycle = Lifecycle()
        val first = Frame(sequence = 7L, sensorTimestampNs = 8_700_000_007L)
        val second = Frame(sequence = 8L, sensorTimestampNs = 8_700_000_008L)
        lifecycle.enqueue(first)
        lifecycle.enqueue(second)
        val observed = mutableListOf<Triple<Frame, Long, Long>>()
        var nativePolls = 0

        repeat(4) { attempt ->
            lifecycle.turn(
                submit = { frame ->
                    observed += Triple(frame, frame.sequence, frame.sensorTimestampNs)
                    if (attempt < 3) {
                        SensorLinearNdrRecordingSubmission(
                            SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING,
                            completionPending = false
                        )
                    } else {
                        SensorLinearNdrRecordingSubmission(
                            SensorLinearNdrRecordingSubmissionStatus.ACCEPTED,
                            completionPending = false
                        )
                    }
                },
                pollNative = {
                    nativePolls += 1
                    true
                }
            )
            if (attempt < 3) {
                assertSame(first, lifecycle.retryFrame)
                assertEquals(0, first.closeCount)
                assertEquals(0, second.submissionCount)
                assertFalse(lifecycle.nativePending)
            }
        }

        observed.forEach { (frame, sequence, timestamp) ->
            assertSame(first, frame)
            assertEquals(7L, sequence)
            assertEquals(8_700_000_007L, timestamp)
        }
        assertEquals(0, nativePolls)
        assertEquals(4, first.submissionCount)
        assertEquals(1, first.closeCount)
        assertEquals(0, second.submissionCount)
    }

    @Test
    fun `stop and destroy during capacity retry close once with zero native polls`() {
        listOf(false, true).forEach { destroy ->
            val lifecycle = Lifecycle()
            val frame = Frame(sequence = 9L, sensorTimestampNs = 8_900_000_009L)
            lifecycle.enqueue(frame)
            lifecycle.turn(submit = {
                SensorLinearNdrRecordingSubmission(
                    SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING,
                    completionPending = false
                )
            })
            if (destroy) lifecycle.requestDestroy() else lifecycle.requestStop(discard = true)
            var nativePolls = 0

            lifecycle.turn(
                submit = { throw AssertionError("discarded capacity retry reached processor") },
                pollNative = {
                    nativePolls += 1
                    true
                }
            )

            assertEquals(0, nativePolls)
            assertEquals(1, frame.closeCount)
            assertEquals(0, lifecycle.ownedFrames)
            assertEquals(1, lifecycle.finishCount)
            assertTrue(lifecycle.isQuiescent())
            assertEquals(destroy, lifecycle.destroyed)
        }
    }

    @Test
    fun `capacity timeout releases exact retry and reports one failure without phantom drain`() {
        val lifecycle = Lifecycle()
        val frame = Frame(sequence = 10L, sensorTimestampNs = 9_000_000_010L)
        lifecycle.enqueue(frame)
        lifecycle.turn(submit = {
            SensorLinearNdrRecordingSubmission(
                SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING,
                completionPending = false
            )
        })

        assertTrue(lifecycle.requestRetryTimeout(frame))
        assertFalse(lifecycle.requestRetryTimeout(frame))
        assertEquals(1, frame.closeCount)
        assertEquals(1, lifecycle.failureCallbackCount)
        assertFalse(lifecycle.nativePending)
        var nativePolls = 0
        lifecycle.turn(
            submit = { throw AssertionError("timed-out retry reached processor") },
            pollNative = {
                nativePolls += 1
                true
            }
        )

        assertEquals(0, nativePolls)
        assertEquals(1, lifecycle.failureCallbackCount)
        assertEquals(0, lifecycle.ownedFrames)
        assertTrue(lifecycle.isQuiescent())
    }

    @Test
    fun `retry with older native tail discards retry then drains that exact tail`() {
        val lifecycle = Lifecycle()
        val accepted = Frame(sequence = 11L, sensorTimestampNs = 9_100_000_011L)
        val retry = Frame(sequence = 12L, sensorTimestampNs = 9_100_000_012L)
        lifecycle.enqueue(accepted)
        lifecycle.enqueue(retry)
        lifecycle.turn(submit = { frame ->
            assertSame(accepted, frame)
            SensorLinearNdrRecordingSubmission(
                SensorLinearNdrRecordingSubmissionStatus.ACCEPTED,
                completionPending = true
            )
        })
        lifecycle.turn(submit = { frame ->
            assertSame(retry, frame)
            SensorLinearNdrRecordingSubmission(
                SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING,
                completionPending = true
            )
        })
        lifecycle.requestStop(discard = true)
        var nativePolls = 0
        lifecycle.turn(
            submit = { throw AssertionError("discarded retry reached processor") },
            pollNative = {
                nativePolls += 1
                true
            }
        )

        assertEquals(1, nativePolls)
        assertEquals(1, accepted.closeCount)
        assertEquals(1, retry.closeCount)
        assertEquals(0, lifecycle.ownedFrames)
        assertTrue(lifecycle.isQuiescent())
    }

    @Test
    fun `later queued frames never overtake retained retry head`() {
        val lifecycle = Lifecycle()
        val frames = (20L..22L).map { sequence ->
            Frame(sequence, sensorTimestampNs = 10_000_000_000L + sequence)
        }
        frames.forEach(lifecycle::enqueue)
        val order = mutableListOf<Frame>()

        repeat(5) {
            lifecycle.turn(submit = { frame ->
                order += frame
                if (frame === frames[0] && frame.submissionCount < 3) {
                    SensorLinearNdrRecordingSubmission(
                        SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING,
                        completionPending = false
                    )
                } else {
                    SensorLinearNdrRecordingSubmission(
                        SensorLinearNdrRecordingSubmissionStatus.ACCEPTED,
                        completionPending = false
                    )
                }
            })
        }

        assertEquals(listOf(frames[0], frames[0], frames[0], frames[1], frames[2]), order)
        assertEquals(listOf(1, 1, 1), frames.map { it.closeCount })
        assertEquals(0, lifecycle.ownedFrames)
    }
}
