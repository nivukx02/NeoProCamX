package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrRecordingStartupGateTest {
    @Test
    fun `same session requires an exact pair newer than the pre-switch baseline`() {
        val gate = SensorLinearNdrRecordingStartupGate()
        assertTrue(gate.publishExactPair(sessionGeneration = 7L, sensorTimestampNs = 100L))
        val transaction = requireNotNull(gate.begin(baselineSessionGeneration = 7L))

        assertNull(gate.tryAcquire(transaction, expectedSessionGeneration = 7L))
        assertFalse(gate.publishExactPair(sessionGeneration = 7L, sensorTimestampNs = 100L))
        assertNull(gate.tryAcquire(transaction, expectedSessionGeneration = 7L))

        assertTrue(gate.publishExactPair(sessionGeneration = 7L, sensorTimestampNs = 101L))
        val readiness = gate.tryAcquire(transaction, expectedSessionGeneration = 7L)
        assertNotNull(readiness)
        assertTrue(gate.consume(requireNotNull(readiness), currentSessionGeneration = 7L))
        assertFalse(gate.consume(readiness, currentSessionGeneration = 7L))
    }

    @Test
    fun `rebuilt session rejects the old graph and accepts its first exact pair`() {
        val gate = SensorLinearNdrRecordingStartupGate()
        assertTrue(gate.publishExactPair(sessionGeneration = 11L, sensorTimestampNs = 900L))
        val transaction = requireNotNull(gate.begin(baselineSessionGeneration = 11L))

        assertNull(gate.tryAcquire(transaction, expectedSessionGeneration = 12L))
        assertFalse(gate.publishExactPair(sessionGeneration = 10L, sensorTimestampNs = 2_000L))
        assertNull(gate.tryAcquire(transaction, expectedSessionGeneration = 12L))

        assertTrue(gate.publishExactPair(sessionGeneration = 12L, sensorTimestampNs = 1_000L))
        val readiness = requireNotNull(
            gate.tryAcquire(transaction, expectedSessionGeneration = 12L)
        )
        assertFalse(gate.consume(readiness, currentSessionGeneration = 13L))
        assertTrue(gate.consume(readiness, currentSessionGeneration = 12L))
    }

    @Test
    fun `superseded transaction cannot acquire or consume the new transaction proof`() {
        val gate = SensorLinearNdrRecordingStartupGate()
        val retired = requireNotNull(gate.begin(baselineSessionGeneration = 3L))
        val current = requireNotNull(gate.begin(baselineSessionGeneration = 3L))
        assertTrue(gate.publishExactPair(sessionGeneration = 3L, sensorTimestampNs = 50L))

        assertNull(gate.tryAcquire(retired, expectedSessionGeneration = 3L))
        val readiness = requireNotNull(gate.tryAcquire(current, expectedSessionGeneration = 3L))
        assertFalse(gate.cancel(retired))
        assertTrue(gate.consume(readiness, currentSessionGeneration = 3L))
    }

    @Test
    fun `cancellation is fail closed and invalid proofs never open readiness`() {
        val gate = SensorLinearNdrRecordingStartupGate()
        assertFalse(gate.publishExactPair(sessionGeneration = -1L, sensorTimestampNs = 10L))
        assertFalse(gate.publishExactPair(sessionGeneration = 1L, sensorTimestampNs = 0L))
        val transaction = requireNotNull(gate.begin(baselineSessionGeneration = 1L))
        assertTrue(gate.cancel(transaction))
        assertTrue(gate.publishExactPair(sessionGeneration = 1L, sensorTimestampNs = 20L))
        assertNull(gate.tryAcquire(transaction, expectedSessionGeneration = 1L))
    }

    @Test
    fun `exact pair must be at or after the replacement repeating request first frame`() {
        val gate = SensorLinearNdrRecordingStartupGate()
        val transaction = requireNotNull(gate.begin(baselineSessionGeneration = 21L))
        assertTrue(gate.publishExactPair(sessionGeneration = 21L, sensorTimestampNs = 1_000L))

        assertNull(
            gate.tryAcquire(
                transaction = transaction,
                expectedSessionGeneration = 21L,
                minimumSensorTimestampNs = 1_001L
            )
        )
        assertTrue(gate.publishExactPair(sessionGeneration = 21L, sensorTimestampNs = 1_001L))
        assertNotNull(
            gate.tryAcquire(
                transaction = transaction,
                expectedSessionGeneration = 21L,
                minimumSensorTimestampNs = 1_001L
            )
        )
    }
}
