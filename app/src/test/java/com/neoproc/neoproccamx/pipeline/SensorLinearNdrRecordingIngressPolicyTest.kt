package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SensorLinearNdrRecordingIngressPolicyTest {

    @Test
    fun `owned frame limit accepts every legal ImageReader acquisition`() {
        assertEquals(2, SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(2))
        assertEquals(3, SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(3))
        assertEquals(8, SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(8))
    }

    @Test
    fun `invalid reader budgets are normalized to one owned frame`() {
        assertEquals(1, SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(0))
        assertEquals(1, SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(1))
    }

    @Test
    fun `concurrent reservation cannot exceed ImageReader hard ceiling`() {
        val owned = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(12)
        try {
            val attempts = (1..64).map {
                executor.submit<Int?> {
                    SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(
                        ownedFrames = owned,
                        readerMaxImages = 8
                    )
                }
            }
            val accepted = attempts.map { it.get(2L, TimeUnit.SECONDS) }.filterNotNull()

            assertEquals(8, accepted.size)
            assertEquals((1..8).toSet(), accepted.toSet())
            assertEquals(8, owned.get())
            assertNull(
                SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(owned, 8)
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `completion polling follows frame cadence within bounded interval`() {
        assertEquals(1_000_000L, SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(30))
        assertEquals(1_000_000L, SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(60))
        assertEquals(250_000L, SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(1_000))
    }

    @Test
    fun `full ownership waits for exact release instead of admitting frame fifteen`() {
        val owned = AtomicInteger(14)
        val gate = SensorLinearNdrRecordingCapacityGate()
        val admissionOpen = AtomicReference(true)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val wait = executor.submit<SensorLinearNdrCapacityWaitResult> {
                started.countDown()
                gate.awaitCapacity(
                    ownedFrames = owned,
                    readerMaxImages = 14,
                    waitSliceNs = TimeUnit.SECONDS.toNanos(2L),
                    admissionIsCurrent = { admissionOpen.get() }
                )
            }
            assertTrue(started.await(2L, TimeUnit.SECONDS))

            // Retirement publishes capacity before the callback may make another reservation.
            assertEquals(13, owned.decrementAndGet())
            gate.signalCapacityChanged()
            assertEquals(
                SensorLinearNdrCapacityWaitResult.AVAILABLE,
                wait.get(2L, TimeUnit.SECONDS)
            )
            assertEquals(14, SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(owned, 14))
            assertNull(SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(owned, 14))
        } finally {
            admissionOpen.set(false)
            gate.signalCapacityChanged()
            executor.shutdownNow()
        }
    }

    @Test
    fun `capacity waiter exits without acquire when immutable admission closes`() {
        val owned = AtomicInteger(14)
        val gate = SensorLinearNdrRecordingCapacityGate()
        val admissionOpen = AtomicReference(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val wait = executor.submit<SensorLinearNdrCapacityWaitResult> {
                gate.awaitCapacity(
                    ownedFrames = owned,
                    readerMaxImages = 14,
                    waitSliceNs = TimeUnit.SECONDS.toNanos(2L),
                    admissionIsCurrent = { admissionOpen.get() }
                )
            }
            admissionOpen.set(false)
            gate.signalCapacityChanged()
            assertEquals(
                SensorLinearNdrCapacityWaitResult.ADMISSION_CLOSED,
                wait.get(2L, TimeUnit.SECONDS)
            )
            assertEquals(14, owned.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `exact image ownership grants one release despite equality aliases`() {
        data class EqualImage(val ignored: Int) {
            override fun equals(other: Any?): Boolean = other is EqualImage
            override fun hashCode(): Int = 1
        }

        val first = EqualImage(1)
        val second = EqualImage(2)
        val ownership = SensorLinearNdrExactImageOwnership()

        assertTrue(ownership.register(first, 7L))
        assertTrue(ownership.register(second, 7L))
        assertFalse(ownership.register(first, 7L))
        assertEquals(2, ownership.outstandingCount())
        assertFalse(ownership.claimRelease(first, 8L))
        assertTrue(ownership.claimRelease(first, 7L))
        assertFalse(ownership.claimRelease(first, 7L))
        assertTrue(ownership.claimRelease(second, 7L))
        assertEquals(0, ownership.outstandingCount())
    }

    @Test
    fun `completion deadline scales by four frames and stays bounded`() {
        assertEquals(100_000_000L, SensorLinearNdrRecordingIngressPolicy.completionDeadlineNs(60))
        assertEquals(133_333_332L, SensorLinearNdrRecordingIngressPolicy.completionDeadlineNs(30))
        assertEquals(750_000_000L, SensorLinearNdrRecordingIngressPolicy.completionDeadlineNs(1))
    }

    @Test
    fun `profile transition receives a fresh bounded control plane deadline only`() {
        assertEquals(
            100_000_000L,
            SensorLinearNdrRecordingIngressPolicy.retryDeadlineNs(
                60,
                SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT
            )
        )
        assertEquals(
            100_000_000L,
            SensorLinearNdrRecordingIngressPolicy.retryDeadlineNs(
                60,
                SensorLinearNdrRecordingRetryCause.PREVIEW_TRANSPORT
            )
        )
        assertEquals(
            250_000_000L,
            SensorLinearNdrRecordingIngressPolicy.retryDeadlineNs(
                60,
                SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION
            )
        )
        assertEquals(
            399_999_996L,
            SensorLinearNdrRecordingIngressPolicy.retryDeadlineNs(
                30,
                SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION
            )
        )
        assertEquals(
            1_500_000_000L,
            SensorLinearNdrRecordingIngressPolicy.retryDeadlineNs(
                1,
                SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION
            )
        )
    }

    @Test
    fun `same retry cause preserves deadline while cause transition resets it`() {
        val first = SensorLinearNdrRecordingIngressPolicy.transitionRetryWindow(
            current = null,
            cause = SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION,
            nowNs = 10L
        )
        val repeated = SensorLinearNdrRecordingIngressPolicy.transitionRetryWindow(
            current = first,
            cause = SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION,
            nowNs = 90L
        )
        assertSame(first, repeated)
        assertEquals(10L, repeated.startedNs)

        val changed = SensorLinearNdrRecordingIngressPolicy.transitionRetryWindow(
            current = repeated,
            cause = SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT,
            nowNs = 100L
        )
        assertEquals(SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT, changed.cause)
        assertEquals(100L, changed.startedNs)
    }

    @Test
    fun `new accepted native owner restarts same cause deadline`() {
        val prior = SensorLinearNdrRecordingIngressPolicy.restartRetryWindow(
            cause = SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT,
            nowNs = 20L
        )
        val acceptedNext = SensorLinearNdrRecordingIngressPolicy.restartRetryWindow(
            cause = SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT,
            nowNs = 120L
        )
        assertEquals(20L, prior.startedNs)
        assertEquals(120L, acceptedNext.startedNs)
    }

    @Test
    fun `retry deadline and stop race grants exactly one release permit`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(250) {
                val frame = Any()
                val slot = AtomicReference<Any?>(frame)
                val start = CountDownLatch(1)
                val deadlineClaim = executor.submit<Any?> {
                    start.await()
                    SensorLinearNdrExactOwnership.claim(slot, frame)
                }
                val stopClaim = executor.submit<Any?> {
                    start.await()
                    slot.getAndSet(null)
                }

                start.countDown()
                val owners = listOf(
                    deadlineClaim.get(2L, TimeUnit.SECONDS),
                    stopClaim.get(2L, TimeUnit.SECONDS)
                )
                assertEquals(1, owners.count { it === frame })
                assertNull(slot.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `join failure reclaims only caller or exact ready owner`() {
        val incoming = Any()
        val other = Any()
        val ready = linkedMapOf<Long, Any>()

        assertTrue(
            SensorLinearNdrJoinFailureOwnership.claimForRelease(
                transferredToJoin = false,
                sequence = 1L,
                expectedOwner = incoming,
                readyBySequence = ready,
                ownerOf = { it }
            )
        )

        ready[2L] = incoming
        assertTrue(
            SensorLinearNdrJoinFailureOwnership.claimForRelease(
                transferredToJoin = true,
                sequence = 2L,
                expectedOwner = incoming,
                readyBySequence = ready,
                ownerOf = { it }
            )
        )
        assertFalse(ready.containsKey(2L))

        // Missing means the exact frame has already moved to the FIFO; a different value must also
        // remain untouched. In both cases the downstream drain keeps the sole release permit.
        assertFalse(
            SensorLinearNdrJoinFailureOwnership.claimForRelease(
                transferredToJoin = true,
                sequence = 3L,
                expectedOwner = incoming,
                readyBySequence = ready,
                ownerOf = { it }
            )
        )
        ready[4L] = other
        assertFalse(
            SensorLinearNdrJoinFailureOwnership.claimForRelease(
                transferredToJoin = true,
                sequence = 4L,
                expectedOwner = incoming,
                readyBySequence = ready,
                ownerOf = { it }
            )
        )
        assertSame(other, ready[4L])
    }

    @Test
    fun `secondary exposure snapshot exceptions are fail soft`() {
        val expected = IllegalStateException("vendor plane already closed")
        var reported: Throwable? = null

        val captured = SensorLinearNdrSecondarySnapshot.capture<Int>(
            onFailure = { reported = it }
        ) {
            throw expected
        }

        assertNull(captured)
        assertSame(expected, reported)

        // Error reporting is secondary too and cannot reverse protected recording admission.
        val loggerFailure = SensorLinearNdrSecondarySnapshot.capture<Int>(
            onFailure = { throw IllegalArgumentException("logger unavailable") }
        ) {
            throw expected
        }
        assertNull(loggerFailure)
    }
}
