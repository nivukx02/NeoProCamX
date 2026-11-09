package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10Niv99TimingTelemetryTest {
    @Test
    fun `bounded histogram reports processed persisted and percentile evidence`() {
        val telemetry = Npv10Niv99TimingTelemetry()
        listOf(0L, 250_000L, 500_000L, 1_000_000L).forEach {
            telemetry.recordProcessed(it)
            telemetry.recordPersisted()
        }

        val snapshot = telemetry.snapshot()
        assertEquals(4L, snapshot.processedFrames)
        assertEquals(4L, snapshot.persistedFrames)
        assertEquals(0L, snapshot.failedFrames)
        assertEquals(0.4375, snapshot.averageMs, 0.0001)
        assertEquals(0.5, snapshot.p50Ms, 0.0001)
        assertEquals(1.25, snapshot.p95Ms, 0.0001)
        assertEquals(1.25, snapshot.p99Ms, 0.0001)
        assertEquals(1.0, snapshot.maximumMs, 0.0001)
        assertTrue(snapshot.provesEveryPersistedFrame(expectedFrames = 4L))
    }

    @Test
    fun `proof fails for missing failed or unpersisted frames and reset clears state`() {
        val telemetry = Npv10Niv99TimingTelemetry()
        telemetry.recordProcessed(500_000L)
        assertFalse(telemetry.snapshot().provesEveryPersistedFrame(expectedFrames = 1L))
        telemetry.recordPersisted()
        telemetry.recordFailure()
        assertFalse(telemetry.snapshot().provesEveryPersistedFrame(expectedFrames = 1L))

        telemetry.reset()
        val reset = telemetry.snapshot()
        assertEquals(0L, reset.processedFrames)
        assertEquals(0L, reset.persistedFrames)
        assertEquals(0L, reset.failedFrames)
        assertEquals(0.0, reset.maximumMs, 0.0)
        assertFalse(reset.provesEveryPersistedFrame(expectedFrames = 0L))
    }
}
