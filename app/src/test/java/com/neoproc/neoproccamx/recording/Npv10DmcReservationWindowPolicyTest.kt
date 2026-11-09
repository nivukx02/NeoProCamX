package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Npv10DmcReservationWindowPolicyTest {
    @Test
    fun compressedHistoryDoesNotRequireRawSizedHistoricalReservation() {
        assertEquals(15_025_000_000L, required(15_000_000_000L, 1000, 1000))
    }

    @Test
    fun allUncommittedSlotsRetainWorstCaseCoverage() {
        assertEquals(15_300_000_000L, required(15_000_000_000L, 1000, 1011))
    }

    @Test
    fun uncompressedFramesKeepTheirOriginalReservationBoundary() {
        assertEquals(1024L + 11L * 25_000_000L, required(1024L + 10L * 25_000_000L, 10, 10))
    }

    @Test
    fun outOfOrderPreparationsCannotExposeUnreservedPacketBytes() {
        val tail = 1024L + 10L * 8_000_000L
        val high = required(tail, 10, 12)
        assertEquals(tail + 3L * 25_000_000L, high)
        assertEquals(high, required(tail + 25_000_000L, 11, 12))
    }

    @Test
    fun staleOutOfWindowAndNegativeStateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { required(1024, 10, 9) }
        assertThrows(IllegalArgumentException::class.java) { required(1024, 10, 22) }
        assertThrows(IllegalArgumentException::class.java) { required(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { required(0, -1, 0) }
    }

    @Test
    fun overflowIsRejectedInsteadOfWrappingIntoAnApparentlyCoveredOffset() {
        assertThrows(ArithmeticException::class.java) { required(Long.MAX_VALUE, 0, 0) }
        assertThrows(ArithmeticException::class.java) {
            Npv10DmcReservationWindowPolicy.requiredEndBytes(1, 0, 1, Long.MAX_VALUE, 12)
        }
    }

    private fun required(bytes: Long, frames: Long, index: Long): Long =
        Npv10DmcReservationWindowPolicy.requiredEndBytes(bytes, frames, index, 25_000_000L, 12)
}
