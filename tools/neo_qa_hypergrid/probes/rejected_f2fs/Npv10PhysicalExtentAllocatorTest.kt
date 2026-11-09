// Reproduction test for the rejected experiment, not a production test-set member.
package com.neoproc.neoproccamx.recording

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10PhysicalExtentAllocatorTest {
    @Test fun physicalSuccessRequiresAllocationAndRestoration() {
        val report = Npv10PhysicalExtentAllocator.decodeNativeResult(intArrayOf(0, 0, 0, 1))
        assertTrue(report.materialized)
        assertEquals(0, report.capabilityErrno)
    }

    @Test fun unsupportedCapabilityRemainsTruthfulAndPreservesPosixRoute() {
        for (error in listOf(1, 13, 22, 25, 38, 95)) {
            val report = Npv10PhysicalExtentAllocator.decodeNativeResult(intArrayOf(0, error, 0, 0))
            assertFalse(report.materialized)
            assertEquals(error, report.capabilityErrno)
        }
    }

    @Test fun allocationErrorCannotBeReportedAsPhysicalSuccess() {
        for (error in listOf(5, 28, 30, 122)) {
            assertThrows(IOException::class.java) {
                Npv10PhysicalExtentAllocator.decodeNativeResult(intArrayOf(error, 0, 0, 1))
            }
        }
    }

    @Test fun releaseErrorIsFatalEvenAfterSuccessfulAllocation() {
        assertThrows(IOException::class.java) {
            Npv10PhysicalExtentAllocator.decodeNativeResult(intArrayOf(0, 0, 5, 1))
        }
    }

    @Test fun incompleteMalformedAndContradictoryNativeEvidenceIsRejected() {
        val malformed = listOf(intArrayOf(), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0, 1, 0),
            intArrayOf(-1, 0, 0, 0), intArrayOf(0, -1, 0, 0), intArrayOf(0, 0, -1, 0),
            intArrayOf(0, 0, 0, -1), intArrayOf(0, 0, 0, 2), intArrayOf(0, 0, 0, 0),
            intArrayOf(0, 13, 0, 1))
        for (values in malformed) assertThrows(IOException::class.java) {
            Npv10PhysicalExtentAllocator.decodeNativeResult(values)
        }
    }
}
