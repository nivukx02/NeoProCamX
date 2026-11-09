package com.neoproc.neoproccamx.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10AutonomousControlRefreshGateTest {
    @Test
    fun `automatic feedback executes normally outside startup`() {
        val gate = Npv10AutonomousControlRefreshGate()

        assertFalse(gate.deferIfActive(SESSION))
    }

    @Test
    fun `automatic feedback burst becomes one latest-state replay`() {
        val lease = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)

        repeat(37) { assertTrue(gate.deferIfActive(SESSION)) }
        val result = gate.retire(lease)

        assertTrue(result.retired)
        assertTrue(result.replayLatestControls)
        assertEquals(37L, result.coalescedRefreshCount)
        assertFalse(gate.deferIfActive(SESSION))
    }

    @Test
    fun `automatic feedback is claimed while startup lease remains active`() {
        val strict = Npv10ProducerStartupLeaseGate()
        val lease = strict.acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)
        repeat(7) { assertTrue(gate.deferIfActive(SESSION)) }

        val claim = gate.claimReplayWhileRetained(lease)

        assertTrue(claim.claimed)
        assertEquals(7L, claim.coalescedRefreshCount)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED,
            strict.admitSubmission(SESSION, lease)
        )
        assertFalse(gate.claimReplayWhileRetained(lease).claimed)
        assertFalse(gate.retire(lease).replayLatestControls)
    }

    @Test
    fun `automatic feedback racing a replay is retained for another pass`() {
        val lease = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)
        assertTrue(gate.deferIfActive(SESSION))
        assertEquals(1L, gate.claimReplayWhileRetained(lease).coalescedRefreshCount)

        repeat(5) { assertTrue(gate.deferIfActive(SESSION)) }
        val nextPass = gate.claimReplayWhileRetained(lease)

        assertTrue(nextPass.claimed)
        assertEquals(5L, nextPass.coalescedRefreshCount)
        assertFalse(gate.retire(lease).replayLatestControls)
    }

    @Test
    fun `terminal lease cannot claim autonomous replay`() {
        val strict = Npv10ProducerStartupLeaseGate()
        val lease = strict.acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)
        assertTrue(gate.deferIfActive(SESSION))
        assertTrue(strict.releaseAfterCadenceStabilization(lease).released)

        val claim = gate.claimReplayWhileRetained(lease)

        assertFalse(claim.claimed)
        assertEquals(0L, claim.coalescedRefreshCount)
        assertTrue(gate.retire(lease).replayLatestControls)
    }

    @Test
    fun `automatic feedback cannot revoke strict stable commit`() {
        val strict = Npv10ProducerStartupLeaseGate()
        val lease = strict.acquire(SESSION)
        val automatic = Npv10AutonomousControlRefreshGate()
        automatic.begin(lease)
        assertTrue(automatic.deferIfActive(SESSION))

        val automaticRetirement = automatic.retire(lease)
        val strictCommit = strict.releaseAfterCadenceStabilization(lease)

        assertTrue(strictCommit.released)
        assertTrue(runBlocking { lease.commitCompletion.await().committed })
        assertTrue(automaticRetirement.replayLatestControls)
        assertEquals(1L, automaticRetirement.coalescedRefreshCount)
    }

    @Test
    fun `user submission still fails closed at stable boundary`() {
        val strict = Npv10ProducerStartupLeaseGate()
        val lease = strict.acquire(SESSION)
        val automatic = Npv10AutonomousControlRefreshGate()
        automatic.begin(lease)
        assertEquals(
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            strict.admitSubmission(SESSION, authorization = null)
        )

        val strictCommit = strict.releaseAfterCadenceStabilization(lease)
        val automaticRetirement = automatic.retire(lease)

        assertFalse(strictCommit.released)
        assertFalse(runBlocking { lease.commitCompletion.await().committed })
        assertTrue(lease.revoked.get())
        assertFalse(automaticRetirement.replayLatestControls)
    }

    @Test
    fun `wrong generation is not hidden from strict producer gate`() {
        val lease = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)

        assertFalse(gate.deferIfActive(SESSION + 1L))
        val result = gate.retire(lease)

        assertFalse(result.replayLatestControls)
        assertEquals(0L, result.coalescedRefreshCount)
    }

    @Test
    fun `stale lease cannot retire a newer startup window`() {
        val first = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val secondGate = Npv10ProducerStartupLeaseGate()
        val second = secondGate.acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(second)
        assertTrue(gate.deferIfActive(SESSION))

        assertFalse(gate.retire(first).retired)
        val current = gate.retire(second)

        assertTrue(current.retired)
        assertTrue(current.replayLatestControls)
    }

    @Test
    fun `retirement emits the replay decision exactly once`() {
        val lease = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)
        assertTrue(gate.deferIfActive(SESSION))

        val first = gate.retire(lease)
        val duplicate = gate.retire(lease)

        assertTrue(first.replayLatestControls)
        assertFalse(duplicate.retired)
        assertFalse(duplicate.replayLatestControls)
    }

    @Test
    fun `generation invalidation drops stale automatic feedback`() {
        val lease = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)
        assertTrue(gate.deferIfActive(SESSION))

        assertTrue(gate.invalidate())
        assertFalse(gate.deferIfActive(SESSION))
        assertFalse(gate.retire(lease).replayLatestControls)
    }

    @Test
    fun `concurrent feedback cannot be lost across the coalescing window`() {
        val lease = Npv10ProducerStartupLeaseGate().acquire(SESSION)
        val gate = Npv10AutonomousControlRefreshGate()
        gate.begin(lease)
        val workers = 8
        val updatesPerWorker = 250
        val start = CountDownLatch(1)
        val finished = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) {
                executor.execute {
                    start.await()
                    repeat(updatesPerWorker) {
                        assertTrue(gate.deferIfActive(SESSION))
                    }
                    finished.countDown()
                }
            }
            start.countDown()
            assertTrue(finished.await(10L, TimeUnit.SECONDS))

            val result = gate.retire(lease)

            assertTrue(result.replayLatestControls)
            assertEquals((workers * updatesPerWorker).toLong(), result.coalescedRefreshCount)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val SESSION = 91L
    }
}
