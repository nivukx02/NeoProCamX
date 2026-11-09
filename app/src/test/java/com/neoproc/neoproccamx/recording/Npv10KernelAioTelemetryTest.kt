package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10KernelAioTelemetryTest {
    @Test fun missingAndLegacyTelemetryCannotProveCompletion() {
        for (input in listOf(null, LongArray(0), LongArray(48))) {
            val telemetry = Npv10KernelAioTelemetry.fromNative(input)
            assertFalse(telemetry.enabled)
            assertFalse(telemetry.allSubmittedRequestsCompleted)
        }
    }

    @Test fun readsAppendedNativeFieldsWithoutChangingLegacyIndices() {
        val values = LongArray(52) { it.toLong() }
        values[48] = 1L; values[49] = 0L; values[50] = 8101L; values[51] = 8101L
        val before = values.copyOf()
        val telemetry = Npv10KernelAioTelemetry.fromNative(values)
        assertTrue(telemetry.enabled)
        assertEquals(8101L, telemetry.submittedRequests)
        assertTrue(telemetry.allSubmittedRequestsCompleted)
        assertTrue(before.contentEquals(values))
    }

    @Test fun missingExtraNegativeOrFailedCompletionsCannotPass() {
        for (telemetry in listOf(
            Npv10KernelAioTelemetry(true, 0, 10, 9),
            Npv10KernelAioTelemetry(true, 0, 10, 11),
            Npv10KernelAioTelemetry(true, 0, -1, -1),
            Npv10KernelAioTelemetry(true, 0, 0, 0),
            Npv10KernelAioTelemetry(true, 5, 10, 10),
            Npv10KernelAioTelemetry(false, 0, 10, 10)
        )) assertFalse(telemetry.allSubmittedRequestsCompleted)
    }

    @Test fun partialNativeTailDoesNotProveCompletion() {
        val values = LongArray(51)
        values[48] = 1L; values[50] = 99L
        assertFalse(Npv10KernelAioTelemetry.fromNative(values).allSubmittedRequestsCompleted)
    }

    @Test fun metadataPreservesMeasuredCountsAndBoundedOwnership() {
        val metadata = Npv10KernelAioTelemetry(true, 0, 100, 100).toMetadata()
        assertEquals("100", metadata["npv10DmcKernelAioSubmittedRequests"])
        assertEquals("100", metadata["npv10DmcKernelAioCompletedRequests"])
        assertEquals("4", metadata["npv10DmcKernelAioMaximumRequests"])
        assertEquals("SINGLE_STAGING_RANGE_COMPLETE_BEFORE_RECORD_RETURN",
            metadata["npv10DmcKernelAioOwnership"])
        assertEquals("true", metadata["npv10DmcKernelAioAllSubmittedCompleted"])
        assertFalse(metadata.keys.any { it.contains("fps", ignoreCase = true) })
    }

    @Test fun finalWriterEvidenceContainsActualKernelCompletionCounters() {
        val evidence = Npv10WriterStageTimings(
            kernelAio = Npv10KernelAioTelemetry(true, 0, 123, 123)
        ).toEvidenceMetadata()
        assertEquals("123", evidence["npv10DmcKernelAioSubmittedRequests"])
        assertEquals("123", evidence["npv10DmcKernelAioCompletedRequests"])
    }

    @Test fun ownedPacketTelemetryIsAppendedAndCannotInventMissingCompletions() {
        val values = LongArray(54)
        values[48] = 1L; values[50] = 16L; values[51] = 15L
        values[52] = 3L; values[53] = 19_000_001L
        val telemetry = Npv10KernelAioTelemetry.fromNative(values)
        assertFalse(telemetry.allSubmittedRequestsCompleted)
        val metadata = Npv10WriterStageTimings(kernelAio = telemetry).toEvidenceMetadata()
        assertEquals("3", metadata["npv10DmcOwnedRecordWrites"])
        assertEquals("19000001", metadata["npv10DmcOwnedPayloadBytes"])
        assertEquals("SINGLE_OWNED_PACKET_COMPLETE_BEFORE_RECORD_RETURN",
            metadata["npv10DmcKernelAioOwnership"])
        assertEquals("4", metadata["npv10DmcKernelAioMaximumRequests"])
        assertEquals(0L, Npv10KernelAioTelemetry.fromNative(LongArray(52)).ownedRecordWrites)
    }

    @Test fun immutableStreamCounterIsAppendOnlyAndNeverAcknowledgesPendingRequests() {
        val values = LongArray(55)
        values[48] = 1L; values[50] = 17L; values[51] = 16L
        values[52] = 3L; values[53] = 39_000_001L; values[54] = 2L
        val before = values.copyOf()
        val telemetry = Npv10KernelAioTelemetry.fromNative(values)
        assertEquals(2L, telemetry.immutableStreamRecordWrites)
        assertEquals(3L, telemetry.ownedRecordWrites)
        assertEquals("2", telemetry.toMetadata()["npv10DmcImmutableStreamRecordWrites"])
        assertFalse(telemetry.allSubmittedRequestsCompleted)
        assertTrue(before.contentEquals(values))
        assertEquals(0L, Npv10KernelAioTelemetry.fromNative(LongArray(54)).immutableStreamRecordWrites)
    }
}
