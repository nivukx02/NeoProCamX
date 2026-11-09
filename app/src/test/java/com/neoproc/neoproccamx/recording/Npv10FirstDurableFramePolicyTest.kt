package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10FirstDurableFramePolicyTest {
    @Test
    fun `exact first sink frame at or after producer proof is accepted`() {
        val proof = proof(timestampNs = 1_040_000_000L)

        assertEquals(
            proof,
            Npv10FirstDurableFramePolicy.validate(
                proof = proof,
                minimumProducerTimestampNs = 1_000_000_000L,
                firstSinkTimestampNs = 1_040_000_000L
            )
        )
    }

    @Test
    fun `durable frame before producer boundary is rejected`() {
        val failure = runCatching {
            Npv10FirstDurableFramePolicy.validate(
                proof = proof(timestampNs = 999_999_999L),
                minimumProducerTimestampNs = 1_000_000_000L,
                firstSinkTimestampNs = 999_999_999L
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `later durable frame cannot impersonate the first sink frame`() {
        val failure = runCatching {
            Npv10FirstDurableFramePolicy.validate(
                proof = proof(timestampNs = 1_080_000_000L),
                minimumProducerTimestampNs = 1_000_000_000L,
                firstSinkTimestampNs = 1_040_000_000L
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun proof(timestampNs: Long) = Npv10FirstDurableFrameProof(
        sensorTimestampNs = timestampNs,
        frameCount = 1L,
        committedBytes = 4_096L
    )
}
