package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderInputReadOwnershipRingTest {
    @Test
    fun `two accepted reads coexist and retire in FIFO order`() {
        val ring = EncoderInputReadOwnershipRing()
        val first = requireNotNull(ring.reserve(frame(1L)))
        assertTrue(ring.bindTransport(first, vulkanToken = 101L, completionSerial = 41L))
        assertTrue(ring.commitAccepted(first, eglReceipt = 201L, submittedPresentationTimeNs = 1_000L))

        val second = requireNotNull(ring.reserve(frame(2L)))
        assertTrue(ring.bindTransport(second, vulkanToken = 102L, completionSerial = 42L))
        assertTrue(ring.commitAccepted(second, eglReceipt = 202L, submittedPresentationTimeNs = 2_000L))

        assertEquals(2, ring.outstandingCount)
        assertEquals(2, ring.committedCount)
        assertFalse(ring.hasCapacity())
        assertSame(first, ring.oldestCommitted())

        signalAndAcknowledge(ring, second)
        assertFalse(ring.retire(second))
        assertSame(first, ring.oldestCommitted())

        signalAndAcknowledge(ring, first)
        assertTrue(ring.retire(first))
        assertSame(second, ring.oldestCommitted())
        assertTrue(ring.retire(second))
        assertEquals(0, ring.outstandingCount)
        assertTrue(ring.hasCapacity())
    }

    @Test
    fun `pre-accept failure cancels reservation without leaking capacity`() {
        val ring = EncoderInputReadOwnershipRing()
        val reserved = requireNotNull(ring.reserve(frame(7L)))
        assertTrue(ring.bindTransport(reserved, vulkanToken = 700L, completionSerial = 70L))

        assertEquals(1, ring.outstandingCount)
        assertTrue(ring.cancelReservation(reserved))
        assertEquals(0, ring.outstandingCount)
        assertNull(ring.oldestCommitted())
        assertFalse(ring.cancelReservation(reserved))
    }

    @Test
    fun `retirement requires fence receipt and GPU completion proofs`() {
        val ring = EncoderInputReadOwnershipRing()
        val slot = requireNotNull(ring.reserve(frame(9L)))
        assertTrue(ring.bindTransport(slot, vulkanToken = 901L, completionSerial = 91L))
        assertTrue(ring.commitAccepted(slot, eglReceipt = 902L, submittedPresentationTimeNs = 9_000L))

        assertFalse(ring.retire(slot))
        assertFalse(ring.markReceiptAcknowledged(slot))
        assertTrue(ring.markFenceSignaled(slot))
        assertTrue(ring.markGpuCompletionRecorded(slot))
        assertFalse(ring.retire(slot))
        assertTrue(ring.markReceiptAcknowledged(slot))
        assertTrue(ring.retire(slot))
    }

    @Test
    fun `stale slots and duplicate frame reservations are rejected`() {
        val ring = EncoderInputReadOwnershipRing()
        val identity = frame(12L)
        val slot = requireNotNull(ring.reserve(identity))

        assertNull(ring.reserve(identity))
        assertFalse(ring.bindTransport(slot, vulkanToken = 0L, completionSerial = 1L))
        assertTrue(ring.bindTransport(slot, vulkanToken = 120L, completionSerial = 12L))
        assertFalse(ring.bindTransport(slot, vulkanToken = 121L, completionSerial = 13L))
        assertTrue(ring.cancelReservation(slot))
        assertFalse(ring.commitAccepted(slot, eglReceipt = 220L, submittedPresentationTimeNs = 12_000L))
    }

    @Test
    fun `sequence rollover waits for a fully drained ring`() {
        val ring = EncoderInputReadOwnershipRing(
            initialReservationSequence = Long.MAX_VALUE
        )
        val last = requireNotNull(ring.reserve(frame(20L)))
        assertEquals(Long.MAX_VALUE, last.reservationSequence)
        assertNull(ring.reserve(frame(21L)))

        assertTrue(ring.cancelReservation(last))
        val wrapped = requireNotNull(ring.reserve(frame(22L)))
        assertEquals(1L, wrapped.reservationSequence)
    }

    @Test
    fun `quarantine retains committed ownership and blocks reuse`() {
        val ring = EncoderInputReadOwnershipRing()
        val slot = requireNotNull(ring.reserve(frame(30L)))
        assertTrue(ring.bindTransport(slot, vulkanToken = 301L, completionSerial = 31L))
        assertTrue(ring.isReadyForAcceptance(slot))
        assertTrue(ring.commitAccepted(slot, eglReceipt = 401L, submittedPresentationTimeNs = 30_000L))

        assertEquals("exact Vulkan acknowledgement failed", ring.quarantine(
            "exact Vulkan acknowledgement failed"
        ))
        assertTrue(ring.isQuarantined)
        assertEquals(1, ring.outstandingCount)
        assertFalse(ring.hasCapacity())
        signalAndAcknowledge(ring, slot)
        assertFalse(ring.retire(slot))
        assertNull(ring.reserve(frame(31L)))
    }

    private fun signalAndAcknowledge(
        ring: EncoderInputReadOwnershipRing,
        slot: EncoderInputReadOwnershipRing.Slot
    ) {
        assertTrue(ring.markFenceSignaled(slot))
        assertTrue(ring.markGpuCompletionRecorded(slot))
        assertTrue(ring.markReceiptAcknowledged(slot))
    }

    private fun frame(timestamp: Long) = RawDemosaicFrameIdentity(
        presentationEpoch = 1L,
        sensorTimestampNs = timestamp,
        width = 4096,
        height = 3072
    )
}
