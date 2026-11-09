package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NdrRenderProfileMutationDeferralTest {
    @Test
    fun `queued old-revision preview owner delays replacement until task retirement`() {
        val deferral = NdrRenderProfileMutationDeferral()
        val replacement = NdrRenderProfileIdentity(12L, 5L)
        val taskStarted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        val readyEdge = CountDownLatch(1)
        val published = AtomicReference<NdrRenderProfileIdentity?>(null)
        lateinit var worker: OrderedNdrPreviewTransportWorker
        worker = OrderedNdrPreviewTransportWorker(
            maxOutstanding = 1,
            threadName = "ndr-profile-drain-test",
            onTaskRetired = {
                published.set(
                    deferral.readyIdentity(
                        drained = worker.snapshot().outstandingTasks == 0
                    )
                )
                readyEdge.countDown()
            }
        )
        try {
            assertTrue(worker.resumeAfterDrain())
            assertTrue(
                worker.submit("old-revision-preview") {
                    taskStarted.countDown()
                    assertTrue(releaseTask.await(2L, TimeUnit.SECONDS))
                }
            )
            assertTrue(taskStarted.await(2L, TimeUnit.SECONDS))
            deferral.defer(replacement)

            assertNull(
                deferral.readyIdentity(
                    drained = worker.snapshot().outstandingTasks == 0
                )
            )
            releaseTask.countDown()
            assertTrue(readyEdge.await(2L, TimeUnit.SECONDS))
            assertEquals(replacement, published.get())
        } finally {
            releaseTask.countDown()
            worker.closeAndDrain(2_000L)
        }
    }

    @Test
    fun `newest same-generation replacement waits for exact raw drain`() {
        val deferral = NdrRenderProfileMutationDeferral()
        val revisionTwo = NdrRenderProfileIdentity(7L, 2L)
        val revisionThree = NdrRenderProfileIdentity(7L, 3L)

        assertEquals(revisionTwo, deferral.defer(revisionTwo))
        assertEquals(revisionThree, deferral.defer(revisionThree))
        assertNull(deferral.readyIdentity(drained = false))
        assertEquals(revisionThree, deferral.readyIdentity(drained = true))
        assertEquals(
            NdrRenderProfileMutationDeferral.Admission.STALE_REVISION,
            deferral.admission(revisionTwo)
        )
        assertEquals(
            NdrRenderProfileMutationDeferral.Admission.PROCEED,
            deferral.admission(revisionThree)
        )
        assertNull(deferral.pendingIdentity())
    }

    @Test
    fun `generation advance supersedes deferred identity and clear retires lifecycle state`() {
        val deferral = NdrRenderProfileMutationDeferral()
        deferral.defer(NdrRenderProfileIdentity(4L, 9L))
        val nextGeneration = NdrRenderProfileIdentity(5L, 1L)

        assertEquals(nextGeneration, deferral.defer(nextGeneration))
        assertEquals(
            NdrRenderProfileMutationDeferral.Admission.PROCEED,
            deferral.admission(nextGeneration)
        )
        deferral.defer(NdrRenderProfileIdentity(5L, 2L))
        deferral.clear()
        assertNull(deferral.pendingIdentity())
    }

    @Test
    fun `exact idempotent packet commits before raw ownership deferral`() {
        val runtime = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        ).readText()
        val commit = runtime
            .substringAfter("internal fun commitNdrRenderProfilePacket(")
            .substringBefore("private fun applyNdrRenderProfilePacketNow(")
        val preflight = commit.indexOf("ndrRenderProfileRevisionAdmission.inspect(")
        val idempotent = commit.indexOf(
            "NdrRenderProfileRevisionAdmission.Result.IDEMPOTENT ->",
            preflight
        )
        val exactApplied = commit.indexOf("matchesAppliedNdrRenderPacket(retainedPacket)", idempotent)
        val committed = commit.indexOf("NdrRenderProfileCommitResult.COMMITTED", exactApplied)
        val rawDeferral = commit.indexOf("deferNdrRenderProfileWhileRawOutputOwnedLocked(")
        val newRevision = commit.indexOf(
            "NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW -> Unit",
            idempotent
        )

        assertTrue(preflight >= 0)
        assertTrue(idempotent > preflight)
        assertTrue(exactApplied > idempotent)
        assertTrue(committed > exactApplied)
        assertTrue(rawDeferral > committed)
        assertTrue(newRevision in (idempotent + 1) until rawDeferral)
        assertTrue(commit.contains("admitNdrRenderProfileRevisionLocked(retainedPacket)"))
    }
}
