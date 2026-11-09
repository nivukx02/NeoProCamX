package com.neoproc.neoproccamx.rendering

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrRenderProfileRevisionAdmissionTest {
    @Test
    fun `newer geometry and enhancement revisions replace within one mode generation`() {
        val admission = admission(initialGeneration = 12L)

        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.admit(identity(12L, 1L), Payload("geometry-a", "enhance-a"))
        )
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.admit(identity(12L, 2L), Payload("geometry-b", "enhance-a"))
        )
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.admit(identity(12L, 3L), Payload("geometry-b", "enhance-b"))
        )
        assertEquals(identity(12L, 3L), admission.latestIdentity())
    }

    @Test
    fun `duplicate is idempotent while conflicting duplicate fails closed`() {
        val admission = admission(initialGeneration = 4L)
        val payload = Payload("geometry", "enhancement")
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.admit(identity(4L, 8L), payload)
        )
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.IDEMPOTENT,
            admission.admit(identity(4L, 8L), payload.copy())
        )
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.CONFLICTING_REVISION,
            admission.admit(identity(4L, 8L), payload.copy(geometry = "different"))
        )
        assertEquals(identity(4L, 8L), admission.latestIdentity())
    }

    @Test
    fun `inspection classifies without advancing the accepted revision`() {
        val admission = admission(initialGeneration = 6L)
        val first = Payload("geometry-a", "enhancement-a")
        val next = Payload("geometry-b", "enhancement-b")

        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.inspect(identity(6L, 1L), first)
        )
        assertEquals(null, admission.latestIdentity())
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.admit(identity(6L, 1L), first)
        )
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.inspect(identity(6L, 2L), next)
        )
        assertEquals(identity(6L, 1L), admission.latestIdentity())
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.CONFLICTING_REVISION,
            admission.inspect(identity(6L, 1L), next)
        )
        assertEquals(identity(6L, 1L), admission.latestIdentity())
    }

    @Test
    fun `out of order and prior generation work cannot replace latest identity`() {
        val admission = admission(initialGeneration = 9L)
        admission.admit(identity(9L, 5L), Payload("new", "new"))

        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.STALE_REVISION,
            admission.admit(identity(9L, 4L), Payload("old", "old"))
        )
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.STALE_GENERATION,
            admission.admit(identity(8L, 99L), Payload("prior-mode", "prior-mode"))
        )
        assertEquals(identity(9L, 5L), admission.latestIdentity())
    }

    @Test
    fun `concurrent replacements converge on greatest revision`() {
        val admission = admission(initialGeneration = 21L)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val workerFailure = AtomicReference<Throwable?>()

        val workers = (1L..16L).map { revision ->
            thread(start = true, name = "ndr-revision-$revision") {
                try {
                    check(start.await(3L, TimeUnit.SECONDS))
                    admission.admit(
                        identity(21L, revision),
                        Payload("geometry-$revision", "enhancement-$revision")
                    )
                } catch (failure: Throwable) {
                    workerFailure.compareAndSet(null, failure)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5L, TimeUnit.SECONDS))
        workers.forEach { it.join(1_000L) }
        workerFailure.get()?.let { throw AssertionError("revision worker failed", it) }
        assertEquals(identity(21L, 16L), admission.latestIdentity())
    }

    @Test
    fun `generation advance resets revision domain and lifecycle close cancels admission`() {
        val admission = admission(initialGeneration = 2L)
        admission.admit(identity(2L, 40L), Payload("old", "old"))

        assertEquals(3L, admission.advanceGeneration(3L))
        assertEquals(null, admission.latestIdentity())
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW,
            admission.admit(identity(3L, 1L), Payload("new", "new"))
        )

        admission.close()
        assertEquals(null, admission.latestIdentity())
        assertEquals(
            NdrRenderProfileRevisionAdmission.Result.CANCELLED_LIFECYCLE,
            admission.admit(identity(3L, 2L), Payload("closed", "closed"))
        )
        assertEquals(3L, admission.advanceGeneration(4L))
    }

    private data class Payload(val geometry: String, val enhancement: String)

    private fun admission(initialGeneration: Long) =
        NdrRenderProfileRevisionAdmission<Payload>(initialGeneration) { left, right ->
            left == right
        }

    private fun identity(generation: Long, revision: Long) =
        NdrRenderProfileIdentity(generation, revision)
}
