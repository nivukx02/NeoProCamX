package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPreallocationPolicyTest {
    @Test
    fun processedReservesEightGiBWhenSpaceAllows() {
        val plan = RecordingPreallocationPolicy.processed(12L * GIB)

        assertTrue(plan.enabled)
        assertEquals(8L * GIB, plan.reservationBytes)
        assertEquals(2L * GIB, plan.safetyReserveBytes)
        assertEquals("target-reserved", plan.reason)
    }

    @Test
    fun rawReservationIsHardCappedAtTenGiB() {
        val plan = RecordingPreallocationPolicy.raw(30L * GIB)

        assertTrue(plan.enabled)
        assertEquals(10L * GIB, plan.reservationBytes)
        assertEquals(3L * GIB, plan.safetyReserveBytes)
    }

    @Test
    fun reservationShrinksInsteadOfConsumingSafetySpace() {
        val plan = RecordingPreallocationPolicy.processed(7L * GIB)

        assertTrue(plan.enabled)
        assertEquals(5L * GIB, plan.reservationBytes)
        assertEquals("space-bounded", plan.reason)
    }

    @Test
    fun reservationDisablesWhenOnlySafetySpaceRemains() {
        val plan = RecordingPreallocationPolicy.raw(2L * GIB)

        assertFalse(plan.enabled)
        assertEquals(0L, plan.reservationBytes)
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
