package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Npv10BacklogEvidencePolicyTest {
    @Test
    fun `uses completed compressed bytes and native service time rather than enqueue time`() {
        val stages = Npv10WriterStageTimings(
            asynchronouslyPersistedFrames = 100,
            asynchronouslyPersistedRecordBytes = 1_000_000_000,
            asynchronousRecordWriterEnabled = true,
            averageAsynchronousRecordWriteMs = 20.0,
            averageDirectCommitMs = 0.1,
            directMappedPayloadBytes = 2_000_000_000
        )
        assertEquals(500_000_000L, Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(stages))
    }

    @Test
    fun `does not substitute estimated raw bytes or Java enqueue speed for missing native evidence`() {
        assertNull(Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(null))
        assertNull(Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(Npv10WriterStageTimings(
            frames = 100, averageFileWriteMs = 0.1, directMappedPayloadBytes = 2_000_000_000
        )))
        assertNull(Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(Npv10WriterStageTimings(
            asynchronousRecordWriterEnabled = true, asynchronouslyPersistedFrames = 100,
            averageAsynchronousRecordWriteMs = 20.0
        )))
    }

    @Test
    fun `zero negative nonfinite and overflowing measurements remain unknown`() {
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE)) {
            assertNull(Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(Npv10WriterStageTimings(
                asynchronousRecordWriterEnabled = true, asynchronouslyPersistedFrames = 1,
                asynchronouslyPersistedRecordBytes = Long.MAX_VALUE,
                averageAsynchronousRecordWriteMs = duration
            )))
        }
        assertNull(Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(Npv10WriterStageTimings(
            asynchronousRecordWriterEnabled = true, asynchronouslyPersistedFrames = Long.MAX_VALUE,
            asynchronouslyPersistedRecordBytes = Long.MAX_VALUE,
            averageAsynchronousRecordWriteMs = Double.MAX_VALUE
        )))
    }

    @Test
    fun `native range bounds and completed bytes survive evidence serialization`() {
        val evidence = Npv10WriterStageTimings(
            maximumIncrementalWritebackRequestBytes = 16L * 1024 * 1024,
            maximumIncrementalWritebackPendingBytes = 50_000_000,
            asynchronouslyPersistedRecordBytes = 21_000_000,
            incrementalWritebackTargetBytes = 16L * 1024 * 1024
        ).toEvidenceMetadata()
        assertEquals("16777216", evidence["npv10DmcMaxIncrementalWritebackRequestBytes"])
        assertEquals("50000000", evidence["npv10DmcMaxIncrementalWritebackPendingBytes"])
        assertEquals("21000000", evidence["npv10DmcAsyncPersistedRecordBytes"])
        assertEquals("16777216", evidence["npv10DmcIncrementalWritebackTargetBytes"])
    }
}
