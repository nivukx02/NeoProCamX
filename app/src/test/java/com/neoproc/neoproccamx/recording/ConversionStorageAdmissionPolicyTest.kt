package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConversionStorageAdmissionPolicyTest {
    @Test fun admitsOnlyWhenOutputHeadroomAndSystemReserveFit() {
        val gib = 1024L * 1024L * 1024L
        val admitted = ConversionStorageAdmissionPolicy.evaluate(
            availableBytes = 4L * gib,
            expectedOutputBytes = gib
        )
        assertTrue(admitted.admitted)
        assertTrue(admitted.requiredBytes > admitted.expectedOutputBytes)

        val rejected = ConversionStorageAdmissionPolicy.evaluate(
            availableBytes = gib,
            expectedOutputBytes = 900L * 1024L * 1024L
        )
        assertFalse(rejected.admitted)
    }

    @Test fun lowStorageFailsBeforePublicationWithMeasuredValues() {
        val failure = runCatching {
            ConversionStorageAdmissionPolicy.requireAdmission(
                availableBytes = 32L * 1024L * 1024L,
                expectedOutputBytes = 64L * 1024L * 1024L,
                label = "fixture"
            )
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("available="))
        assertTrue(failure?.message.orEmpty().contains("required="))
    }

    @Test fun overflowCannotWrapIntoFalseAdmission() {
        val decision = ConversionStorageAdmissionPolicy.evaluate(Long.MAX_VALUE, Long.MAX_VALUE)
        assertFalse(decision.admitted)
        assertTrue(decision.requiredBytes == Long.MAX_VALUE)
    }
}
