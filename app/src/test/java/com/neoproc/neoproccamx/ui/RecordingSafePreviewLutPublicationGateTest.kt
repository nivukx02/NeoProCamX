package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSafePreviewLutPublicationGateTest {
    @Test
    fun `blocked claim preserves publication for the stop boundary`() {
        val gate = RecordingSafePreviewLutPublicationGate()
        gate.deferLatest(1L, floatArrayOf(0.1f, 0.2f) to 2)

        assertNull(gate.claimLatestIfSafe(blocked = true, latestSequence = 1L))
        assertTrue(gate.hasDeferredPublication())
        assertEquals(
            2,
            gate.claimLatestIfSafe(blocked = false, latestSequence = 1L)?.copyForApply()?.second
        )
        assertFalse(gate.hasDeferredPublication())
    }

    @Test
    fun `newest publication replaces older work including a disable`() {
        val gate = RecordingSafePreviewLutPublicationGate()
        gate.deferLatest(1L, floatArrayOf(0.1f) to 2)
        gate.deferLatest(2L, null)

        val claimed = gate.claimLatestIfSafe(blocked = false, latestSequence = 2L)
        assertTrue(claimed != null)
        assertNull(claimed?.copyForApply())
        assertNull(gate.claimLatestIfSafe(blocked = false, latestSequence = 2L))
    }

    @Test
    fun `deferred data is immutable across resolver and apply ownership`() {
        val source = floatArrayOf(0.1f, 0.2f, 0.3f)
        val gate = RecordingSafePreviewLutPublicationGate()
        gate.deferLatest(1L, source to 3)
        source[0] = 9f

        val publication = requireNotNull(
            gate.claimLatestIfSafe(blocked = false, latestSequence = 1L)
        )
        val firstApply = requireNotNull(publication.copyForApply())
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), firstApply.first, 0f)
        firstApply.first[1] = 8f
        val secondApply = requireNotNull(publication.copyForApply())
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), secondApply.first, 0f)
    }

    @Test
    fun `older deferred result cannot replay after a newer unresolved request`() {
        val gate = RecordingSafePreviewLutPublicationGate()
        gate.deferLatest(7L, floatArrayOf(0.1f) to 2)

        assertNull(gate.claimLatestIfSafe(blocked = false, latestSequence = 8L))
        assertFalse(gate.hasDeferredPublication())
    }

    @Test
    fun `closed gate rejects and clears late asynchronous publication`() {
        val gate = RecordingSafePreviewLutPublicationGate()
        gate.deferLatest(1L, floatArrayOf(0.1f) to 2)
        gate.close()

        assertFalse(gate.hasDeferredPublication())
        assertFalse(gate.deferLatest(2L, floatArrayOf(0.2f) to 2))
        assertNull(gate.claimLatestIfSafe(blocked = false, latestSequence = 2L))
    }
}
