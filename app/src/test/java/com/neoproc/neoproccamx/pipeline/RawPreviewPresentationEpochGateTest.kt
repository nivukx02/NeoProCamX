package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewPresentationEpochGateTest {
    @Test
    fun `begin disarms the previous profile immediately`() {
        val gate = RawPreviewPresentationEpochGate()
        val first = gate.begin("NDR10+")
        assertTrue(gate.commit(first, sessionGeneration = 11L))
        assertTrue(gate.accepts(first, sessionGeneration = 11L))

        val second = gate.begin("NIVUKX")

        assertNotEquals(first, second)
        assertFalse(gate.accepts(first, sessionGeneration = 11L))
        assertFalse(gate.accepts(second, sessionGeneration = 11L))
    }

    @Test
    fun `stale asynchronous configuration cannot commit a newer epoch`() {
        val gate = RawPreviewPresentationEpochGate()
        val stale = gate.begin("RAW")
        val current = gate.begin("NDR10+")

        assertFalse(gate.commit(stale, sessionGeneration = 20L))
        assertTrue(gate.commit(current, sessionGeneration = 21L))
        assertFalse(gate.accepts(current, sessionGeneration = 20L))
        assertTrue(gate.accepts(current, sessionGeneration = 21L))
    }

    @Test
    fun `profile identity alone never bypasses session generation lineage`() {
        val gate = RawPreviewPresentationEpochGate()
        val epoch = gate.begin("NIVUKX")
        assertTrue(gate.commit(epoch, sessionGeneration = 31L))

        assertFalse(gate.accepts(epoch, sessionGeneration = 30L))
        assertFalse(gate.accepts(epoch + 1L, sessionGeneration = 31L))
        assertTrue(gate.acceptsSession(sessionGeneration = 31L))
    }

    @Test
    fun `duplicate configured callback is idempotent for the active session`() {
        val gate = RawPreviewPresentationEpochGate()
        val epoch = gate.begin("NDR10+")

        assertEquals(
            RawPreviewPresentationEpochGate.CommitOutcome.COMMITTED,
            gate.commitOutcome(epoch, sessionGeneration = 41L)
        )
        assertEquals(
            RawPreviewPresentationEpochGate.CommitOutcome.UNCHANGED,
            gate.commitOutcome(epoch, sessionGeneration = 41L)
        )
        assertTrue(gate.accepts(epoch, sessionGeneration = 41L))
    }
}
