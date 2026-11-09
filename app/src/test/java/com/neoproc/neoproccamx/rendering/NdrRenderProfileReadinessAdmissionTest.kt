package com.neoproc.neoproccamx.rendering

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrRenderProfileReadinessAdmissionTest {
    @Test
    fun `close racing submit never invokes a consumer after close returns`() {
        repeat(200) {
            val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 1L)
            val start = CountDownLatch(1)
            val closeReturned = AtomicBoolean(false)
            val appliedAfterClose = AtomicBoolean(false)
            val applies = AtomicInteger(0)
            val threadFailure = AtomicReference<Throwable?>(null)
            val submitter = Thread {
                try {
                    start.await()
                    admission.submit(1L, "packet", consumersReady = true) {
                        if (closeReturned.get()) appliedAfterClose.set(true)
                        applies.incrementAndGet()
                        true
                    }
                } catch (failure: Throwable) {
                    threadFailure.compareAndSet(null, failure)
                }
            }
            val closer = Thread {
                try {
                    start.await()
                    admission.close()
                    closeReturned.set(true)
                } catch (failure: Throwable) {
                    threadFailure.compareAndSet(null, failure)
                }
            }

            submitter.start()
            closer.start()
            start.countDown()
            submitter.join()
            closer.join()

            assertNull(threadFailure.get())
            assertFalse(appliedAfterClose.get())
            assertTrue(applies.get() in 0..1)
            assertEquals(
                NdrRenderProfileReadinessAdmission.Result.CANCELLED_LIFECYCLE,
                admission.submit(1L, "late", consumersReady = true) {
                    appliedAfterClose.set(true)
                    true
                }
            )
            assertFalse(appliedAfterClose.get())
        }
    }

    @Test
    fun `close racing deferred replay cannot publish after close returns`() {
        repeat(200) {
            val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 3L)
            admission.submit(3L, "retained", consumersReady = false) { error("not ready") }
            val start = CountDownLatch(1)
            val closeReturned = AtomicBoolean(false)
            val appliedAfterClose = AtomicBoolean(false)
            val threadFailure = AtomicReference<Throwable?>(null)
            val replayer = Thread {
                try {
                    start.await()
                    admission.replayWhenReady(3L, consumersReady = true) {
                        if (closeReturned.get()) appliedAfterClose.set(true)
                        true
                    }
                } catch (failure: Throwable) {
                    threadFailure.compareAndSet(null, failure)
                }
            }
            val closer = Thread {
                try {
                    start.await()
                    admission.close()
                    closeReturned.set(true)
                } catch (failure: Throwable) {
                    threadFailure.compareAndSet(null, failure)
                }
            }

            replayer.start()
            closer.start()
            start.countDown()
            replayer.join()
            closer.join()

            assertNull(threadFailure.get())
            assertFalse(appliedAfterClose.get())
            assertFalse(admission.hasPendingIntent())
            assertNull(admission.replayWhenReady(3L, consumersReady = true) {
                appliedAfterClose.set(true)
                true
            })
            assertFalse(appliedAfterClose.get())
        }
    }

    @Test
    fun `generation zero is never retained or applied`() {
        val admission = NdrRenderProfileReadinessAdmission<String>()
        var applies = 0

        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.STALE_GENERATION,
            admission.submit(0L, "sentinel", consumersReady = false) {
                applies += 1
                true
            }
        )
        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.STALE_GENERATION,
            admission.submit(0L, "sentinel", consumersReady = true) {
                applies += 1
                true
            }
        )
        assertEquals(0, applies)
        assertFalse(admission.hasPendingIntent())
    }

    @Test
    fun `generation zero replay cannot consume a retained positive generation`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 9L)
        admission.submit(9L, "retained", consumersReady = false) { error("not ready") }
        val applied = mutableListOf<String>()

        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.STALE_GENERATION,
            admission.replayWhenReady(0L, consumersReady = true) {
                applied += it
                true
            }
        )
        assertTrue(admission.hasPendingIntent())
        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.APPLIED,
            admission.replayWhenReady(9L, consumersReady = true) {
                applied += it
                true
            }
        )
        assertEquals(listOf("retained"), applied)
    }

    @Test
    fun `before-ready commit is retained without invoking a consumer`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 7L)
        var applies = 0

        val result = admission.submit(7L, "NDR10", consumersReady = false) {
            applies += 1
            true
        }

        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.DEFERRED_UNREADY,
            result
        )
        assertEquals(0, applies)
        assertTrue(admission.hasPendingIntent())
    }

    @Test
    fun `actual readiness replays retained intent exactly once`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 3L)
        admission.submit(3L, "NIVUKX", consumersReady = false) { error("not ready") }
        val applied = mutableListOf<String>()

        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.APPLIED,
            admission.replayWhenReady(3L, consumersReady = true) {
                applied += it
                true
            }
        )
        assertNull(admission.replayWhenReady(3L, consumersReady = true) {
            applied += it
            true
        })
        assertEquals(listOf("NIVUKX"), applied)
    }

    @Test
    fun `same-generation startup updates coalesce to the latest intent`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 5L)
        admission.submit(5L, "first", consumersReady = false) { false }
        admission.submit(5L, "latest", consumersReady = false) { false }
        val applied = mutableListOf<String>()

        admission.replayWhenReady(5L, consumersReady = true) {
            applied += it
            true
        }

        assertEquals(listOf("latest"), applied)
    }

    @Test
    fun `generation advance cancels deferred startup intent`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 10L)
        admission.submit(10L, "old", consumersReady = false) { false }

        assertEquals(11L, admission.advanceGeneration(11L))
        assertNull(admission.replayWhenReady(11L, consumersReady = true) { true })
        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.STALE_GENERATION,
            admission.submit(10L, "stale", consumersReady = true) { true }
        )
    }

    @Test
    fun `lifecycle close cancels pending replay and rejects later submissions`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 2L)
        admission.submit(2L, "pending", consumersReady = false) { false }

        admission.close()

        assertFalse(admission.hasPendingIntent())
        assertNull(admission.replayWhenReady(2L, consumersReady = true) { true })
        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.CANCELLED_LIFECYCLE,
            admission.submit(2L, "late", consumersReady = true) { true }
        )
    }

    @Test
    fun `real apply failure is terminal and is never converted into a readiness retry`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 4L)
        var applies = 0

        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.APPLY_FAILED,
            admission.submit(4L, "coherent-but-native-failed", consumersReady = true) {
                applies += 1
                false
            }
        )
        assertEquals(1, applies)
        assertNull(admission.replayWhenReady(4L, consumersReady = true) {
            applies += 1
            true
        })
        assertEquals(1, applies)
    }

    @Test
    fun `permission-denied startup stays deferred and quiet until a real readiness edge`() {
        val admission = NdrRenderProfileReadinessAdmission<String>(initialGeneration = 1L)
        var nativeOrCameraTouches = 0

        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.DEFERRED_UNREADY,
            admission.submit(1L, "persisted-profile", consumersReady = false) {
                nativeOrCameraTouches += 1
                true
            }
        )
        assertEquals(
            NdrRenderProfileReadinessAdmission.Result.DEFERRED_UNREADY,
            admission.replayWhenReady(1L, consumersReady = false) {
                nativeOrCameraTouches += 1
                true
            }
        )
        assertEquals(0, nativeOrCameraTouches)
        assertTrue(admission.hasPendingIntent())
    }
}
