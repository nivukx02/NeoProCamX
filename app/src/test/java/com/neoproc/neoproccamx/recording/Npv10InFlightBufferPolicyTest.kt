package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10InFlightBufferPolicyTest {
    @Test fun `full sensor queue and reader recovery cover sustained thermal jitter`() {
        val pending = Npv10InFlightBufferPolicy.pendingFrames(4096, 3072)
        val framePeriodNanos = 1_000_000_000L / 25L
        val leaseCoverageNanos = pending * framePeriodNanos
        val admissionRecoveryNanos = Npv10InFlightBufferPolicy.admissionWaitNanos(25)
        val measuredThermalPackingTailNanos = 494_001_094L

        assertEquals(Npv10InFlightBufferPolicy.MAX_PENDING_FRAMES, pending)
        assertEquals(480_000_000L, leaseCoverageNanos)
        assertEquals(160_000_000L, admissionRecoveryNanos)
        assertTrue(leaseCoverageNanos + admissionRecoveryNanos > measuredThermalPackingTailNanos)
        assertTrue(
            leaseCoverageNanos + admissionRecoveryNanos - measuredThermalPackingTailNanos >=
                framePeriodNanos * 3L
        )
        assertTrue(
            Npv10InFlightBufferPolicy.ordinaryResidentBufferBytes(4096, 3072) <=
                Npv10InFlightBufferPolicy.MAX_DIRECT_BUFFER_BYTES
        )
        assertEquals(18, Npv10InFlightBufferPolicy.readerMaxImages(4096, 3072))
    }

    @Test fun `full sensor packet pool plus canonical fallbacks stays inside direct buffer budget`() {
        val pending = Npv10InFlightBufferPolicy.pendingFrames(4096, 3072)
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(4096, 3072)
        val packetBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        val boundedBytes = packetBytes * pending +
            decodedBytes * Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS
        val unboundedThreeFallbackBytes = packetBytes * pending + decodedBytes * 3L

        assertEquals(2, Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS)
        assertEquals(342L * 1024L * 1024L, boundedBytes)
        assertEquals(378L * 1024L * 1024L, unboundedThreeFallbackBytes)
        assertEquals(
            boundedBytes,
            Npv10InFlightBufferPolicy.ordinaryResidentBufferBytes(4096, 3072)
        )
        assertTrue(boundedBytes <= Npv10InFlightBufferPolicy.MAX_DIRECT_BUFFER_BYTES)
        assertTrue(unboundedThreeFallbackBytes > Npv10InFlightBufferPolicy.MAX_DIRECT_BUFFER_BYTES)
    }

    @Test fun `smaller streams retain maximum burst absorption`() {
        assertEquals(
            Npv10InFlightBufferPolicy.MAX_PENDING_FRAMES,
            Npv10InFlightBufferPolicy.pendingFrames(1920, 1080)
        )
    }

    @Test fun `full sensor NIV99 canonical and packet pools stay inside direct buffer budget`() {
        val pending = Npv10InFlightBufferPolicy.niv99PendingFrames(4096, 3072)
        val residentBytes = Npv10InFlightBufferPolicy.niv99ResidentBufferBytes(4096, 3072)

        assertEquals(4, Npv10InFlightBufferPolicy.NIV99_CANONICAL_BUFFER_COUNT)
        assertEquals(4, pending)
        assertEquals(234L * 1024L * 1024L, residentBytes)
        assertTrue(
            Npv10InFlightBufferPolicy.NIV99_MAX_DIRECT_BUFFER_BYTES <
                Npv10InFlightBufferPolicy.MAX_DIRECT_BUFFER_BYTES
        )
        assertTrue(residentBytes <= Npv10InFlightBufferPolicy.NIV99_MAX_DIRECT_BUFFER_BYTES)
    }

    @Test fun `admission wait absorbs jitter but expires inside reserved reader capacity`() {
        val fps = 25
        val framePeriodNanos = 1_000_000_000L / fps
        val waitNanos = Npv10InFlightBufferPolicy.admissionWaitNanos(fps)

        assertEquals(160_000_000L, waitNanos)
        assertEquals(framePeriodNanos * 4L, waitNanos)
        assertTrue(
            waitNanos <
                framePeriodNanos * (Npv10InFlightBufferPolicy.READER_RESERVED_IMAGES - 1L)
        )
    }

    @Test fun `admission wait follows frame rate and remains strictly bounded`() {
        assertTrue(
            Npv10InFlightBufferPolicy.admissionWaitNanos(60) <
                Npv10InFlightBufferPolicy.admissionWaitNanos(25)
        )
        assertTrue(Npv10InFlightBufferPolicy.admissionWaitNanos(1) <= 200_000_000L)
    }
}
