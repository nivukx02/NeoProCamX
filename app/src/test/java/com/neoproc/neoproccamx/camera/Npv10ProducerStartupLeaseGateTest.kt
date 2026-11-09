package com.neoproc.neoproccamx.camera

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10ProducerStartupLeaseGateTest {
    @Test
    fun `authorized initial request is admitted only for its exact session`() {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)

        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, lease)
        )
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.STALE_AUTHORIZATION,
            gate.admitSubmission(SESSION + 1L, lease)
        )
    }

    @Test
    fun `control refreshes remain gated until the authorized replay is published`() {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)

        repeat(20) {
            assertEquals(
                Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
                gate.admitSubmission(SESSION, authorization = null)
            )
        }

        val firstRelease = gate.release(lease)
        assertFalse(firstRelease.released)
        assertTrue(firstRelease.replayDeferredRefresh)
        assertTrue(gate.deferredReplayPending(lease))
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            gate.admitSubmission(SESSION, authorization = null)
        )
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = lease)
        )
        assertTrue(gate.completeDeferredReplay(lease))
        assertTrue(runBlocking { lease.commitCompletion.await().committed })
        assertFalse(gate.deferredReplayPending(lease))

        val duplicateRelease = gate.release(lease)
        assertFalse(duplicateRelease.released)
        assertFalse(duplicateRelease.replayDeferredRefresh)
        assertTrue(duplicateRelease.duplicate)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = null)
        )
    }

    @Test
    fun `release without a deferred control update does not manufacture refresh work`() {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)

        val release = gate.release(lease)

        assertTrue(release.released)
        assertFalse(release.replayDeferredRefresh)
        assertTrue(runBlocking { lease.commitCompletion.await().committed })
    }

    @Test
    fun `deferred refresh can be replayed while startup lease remains authoritative`() {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        val handle = handle(gate, lease)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            gate.admitSubmission(SESSION, authorization = null)
        )

        assertTrue(handle.replayDeferredRefreshIfPending())
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = lease)
        )
        assertFalse(handle.replayDeferredRefreshIfPending())
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            gate.admitSubmission(SESSION, authorization = null)
        )
        assertTrue(handle.replayDeferredRefreshIfPending())
    }

    @Test
    fun `stable commit fails closed when a late control refresh races writer boundary`() = runBlocking {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        val handle = handle(gate, lease)
        gate.admitSubmission(SESSION, authorization = null)

        val result = handle.commitAfterCadenceStabilization()

        assertFalse(result.committed)
        assertTrue(result.reason.contains("controls changed"))
        assertTrue(lease.revoked.get())
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = null)
        )
    }

    @Test
    fun `stable commit releases a settled producer without replay`() = runBlocking {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        val handle = handle(gate, lease)

        val result = handle.commitAfterCadenceStabilization()

        assertTrue(result.committed)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = null)
        )
    }

    @Test
    fun `session invalidation revokes authorization and suppresses stale replay`() {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            gate.admitSubmission(SESSION, authorization = null)
        )

        assertTrue(gate.invalidate())
        assertTrue(lease.revoked.get())
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.STALE_AUTHORIZATION,
            gate.admitSubmission(SESSION, lease)
        )
        val release = gate.release(lease)
        assertFalse(release.released)
        assertFalse(release.replayDeferredRefresh)
        assertFalse(runBlocking { lease.commitCompletion.await().committed })
    }

    @Test
    fun `caller handle exposes revocation and duplicate close is harmless`() {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        var cancelCalls = 0
        val handle = handle(gate, lease) { cancelCalls += 1 }

        assertFalse(handle.isRevoked)
        assertTrue(gate.invalidate())
        assertTrue(handle.isRevoked)
        handle.close()
        handle.close()

        assertEquals(1, cancelCalls)
    }

    @Test
    fun `handle commit waits until deferred replay is atomically published`() = runBlocking {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        val handle = handle(gate, lease)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            gate.admitSubmission(SESSION, authorization = null)
        )

        val commit = async(start = CoroutineStart.UNDISPATCHED) { handle.commit() }
        assertFalse(commit.isCompleted)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = lease)
        )
        assertTrue(gate.completeDeferredReplay(lease))

        assertTrue(commit.await().committed)
    }

    @Test
    fun `invalidation between commit request and replay fails the observed commit`() = runBlocking {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        val handle = handle(gate, lease)
        gate.admitSubmission(SESSION, authorization = null)

        val commit = async(start = CoroutineStart.UNDISPATCHED) { handle.commit() }
        assertFalse(commit.isCompleted)
        assertTrue(gate.invalidate("replacement session"))

        val result = commit.await()
        assertFalse(result.committed)
        assertEquals("replacement session", result.reason)
    }

    @Test
    fun `cancelling a pending commit revokes and retires the gate`() = runBlocking {
        val gate = Npv10ProducerStartupLeaseGate()
        val lease = gate.acquire(SESSION)
        val handle = handle(gate, lease)
        gate.admitSubmission(SESSION, authorization = null)
        val commit = async(start = CoroutineStart.UNDISPATCHED) { handle.commit() }

        commit.cancel()
        commit.join()

        assertTrue(lease.revoked.get())
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            gate.admitSubmission(SESSION, authorization = null)
        )
        assertFalse(lease.commitCompletion.await().committed)
    }

    @Test(expected = IllegalStateException::class)
    fun `only one startup lease may own submissions`() {
        val gate = Npv10ProducerStartupLeaseGate()
        gate.acquire(SESSION)

        gate.acquire(SESSION)
    }

    private companion object {
        const val SESSION = 17L
    }

    private fun handle(
        gate: Npv10ProducerStartupLeaseGate,
        lease: Npv10ProducerStartupLeaseGate.Lease,
        onCancel: () -> Unit = {}
    ) = Npv10ProducerStartupLeaseHandle(
        lease = lease,
        requestCommit = { gate.release(it) },
        requestStableCommit = { gate.releaseAfterCadenceStabilization(it) },
        replayDeferredRefresh = { gate.claimDeferredReplayWhileRetained(it) },
        cancel = { target, reason ->
            onCancel()
            gate.revoke(target, reason)
        }
    )
}
