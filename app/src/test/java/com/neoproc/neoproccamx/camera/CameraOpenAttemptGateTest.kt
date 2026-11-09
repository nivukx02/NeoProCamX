package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpenAttemptGateTest {
    @Test
    fun `close invalidates callbacks and scheduled retries from the prior lifecycle`() {
        val gate = CameraOpenAttemptGate()
        val generation = gate.beginOpenLifecycle()
        val attempt = requireNotNull(gate.newAttempt(generation))

        gate.invalidateLifecycle()

        assertFalse(gate.isCurrent(generation))
        assertFalse(gate.isCurrent(attempt))
        assertFalse(gate.claimPermit(attempt))
        assertFalse(gate.newAttempt(generation) != null)
    }

    @Test
    fun `stale callback cannot release permit owned by newer same-camera open`() {
        val gate = CameraOpenAttemptGate()
        val firstGeneration = gate.beginOpenLifecycle()
        val first = requireNotNull(gate.newAttempt(firstGeneration))
        assertTrue(gate.claimPermit(first))
        assertTrue(gate.releasePermit(first))

        val secondGeneration = gate.beginOpenLifecycle()
        val second = requireNotNull(gate.newAttempt(secondGeneration))
        assertTrue(gate.claimPermit(second))

        assertFalse(gate.releasePermit(first))
        assertTrue(gate.releasePermit(second))
    }

    @Test
    fun `one attempt releases its permit at most once`() {
        val gate = CameraOpenAttemptGate()
        val attempt = gate.newAttempt(gate.beginOpenLifecycle())
        assertNotNull(attempt)
        assertTrue(gate.claimPermit(requireNotNull(attempt)))
        assertTrue(gate.releasePermit(attempt))
        assertFalse(gate.releasePermit(attempt))
    }

    @Test
    fun `close timeout can release orphan without allowing late callback double release`() {
        val gate = CameraOpenAttemptGate()
        val attempt = requireNotNull(gate.newAttempt(gate.beginOpenLifecycle()))
        assertTrue(gate.claimPermit(attempt))

        gate.invalidateLifecycle()

        assertTrue(gate.releaseOrphanedPermit())
        assertFalse(gate.releasePermit(attempt))
        assertFalse(gate.releaseOrphanedPermit())
    }
}
