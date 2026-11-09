package com.neoproc.neoproccamx.recording

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Npv10DurabilityStageTelemetryTest {
    @Test
    fun queueDelayIsNeverReportedAsFilesystemForceDelay() {
        var now = 0L
        val telemetry = Npv10DurabilityStageTelemetry { now }
        telemetry.awaitCommit { now += 1_100_000_000L }
        telemetry.syncFile { now += 2_000_000L }
        telemetry.awaitCommit { now += 100_000_000L }
        telemetry.syncFile { now += 4_000_000L }
        val snapshot = telemetry.snapshot()
        assertEquals(2L, snapshot.commitWaitAttempts)
        assertEquals(600.0, snapshot.averageCommitWaitMs, 0.0)
        assertEquals(1100.0, snapshot.maximumCommitWaitMs, 0.0)
        assertEquals(2L, snapshot.syncAttempts)
        assertEquals(3.0, snapshot.averageSyncMs, 0.0)
        assertEquals(4.0, snapshot.maximumSyncMs, 0.0)
        assertEquals("1100.0", snapshot.toEvidenceMetadata()["npv10CheckpointMaximumCommitWaitMs"])
        assertEquals("4.0", snapshot.toEvidenceMetadata()["npv10CheckpointMaximumSyncMs"])
    }

    @Test
    fun failedCommitWaitDoesNotManufactureAnAttemptedSync() {
        var now = 0L
        val telemetry = Npv10DurabilityStageTelemetry { now }
        val expected = IOException("native writer failed")
        val failure = runCatching {
            telemetry.awaitCommit { now += 40_000_000L; throw expected }
            telemetry.syncFile { error("Must never sync an uncommitted checkpoint") }
        }.exceptionOrNull()
        assertSame(expected, failure)
        val snapshot = telemetry.snapshot()
        assertEquals(1L, snapshot.commitWaitFailures)
        assertEquals(0L, snapshot.syncAttempts)
        assertEquals(0.0, snapshot.averageSyncMs, 0.0)
        assertEquals(40.0, snapshot.maximumCommitWaitMs, 0.0)
    }

    @Test
    fun syncFailureAndNanoClockWrapRemainTruthful() {
        var now = Long.MAX_VALUE - 999_999L
        val telemetry = Npv10DurabilityStageTelemetry { now }
        val expected = IOException("physical force failed")
        val failure = runCatching {
            telemetry.syncFile { now = Long.MIN_VALUE + 1_000_000L; throw expected }
        }.exceptionOrNull()
        assertSame(expected, failure)
        val snapshot = telemetry.snapshot()
        assertEquals(1L, snapshot.syncAttempts)
        assertEquals(1L, snapshot.syncFailures)
        assertEquals(2.0, snapshot.maximumSyncMs, 0.0)
        assertEquals(0L, snapshot.commitWaitAttempts)
    }
}
