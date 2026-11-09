package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10LosslessStoragePressureTelemetryTest {
    @Test fun olderSnapshotsDoNotClaimPressureMeasurements() {
        for (size in 0..47) {
            assertEquals(Npv10LosslessStoragePressureTelemetry(),
                Npv10LosslessStoragePressureTelemetry.fromNative(LongArray(size) { 1L }))
        }
        assertEquals(Npv10LosslessStoragePressureTelemetry(),
            Npv10LosslessStoragePressureTelemetry.fromNative(null))
    }

    @Test fun appendedNativeFieldsMapWithoutChangingEarlierIndices() {
        val values = LongArray(48) { it.toLong() * 1_000_000L }
        values[32] = 1L
        values[33] = 1L
        val result = Npv10LosslessStoragePressureTelemetry.fromNative(values)
        assertTrue(result.enabled)
        assertTrue(result.active)
        assertEquals(34_000_000L, result.entries)
        assertEquals(35_000_000L, result.fastFrames)
        assertEquals(36_000_000L, result.pressureFrames)
        assertEquals(37_000_000L, result.cpuBackoffs)
        assertEquals(38_000_000L, result.fastEncodeNs)
        assertEquals(39_000_000L, result.pressureEncodeNs)
        assertEquals(40_000_000L, result.pressureMaxEncodeNs)
        assertEquals(41_000_000L, result.fastInputBytes)
        assertEquals(42_000_000L, result.fastStoredBytes)
        assertEquals(43_000_000L, result.pressureInputBytes)
        assertEquals(44_000_000L, result.pressureStoredBytes)
        assertEquals(45_000_000L, result.sourceIntervalNs)
        assertEquals(46_000_000L, result.failures)
        assertEquals(47_000_000L, result.cooldownFrames)
    }

    @Test fun evidenceIncludesFailuresAndEffortWithoutClaimingQualityOrGpuUtilization() {
        val measured = Npv10LosslessStoragePressureTelemetry(
            enabled = true, pressureFrames = 123L, cpuBackoffs = 2L, failures = 1L,
            pressureInputBytes = 15_000L, pressureStoredBytes = 11_000L
        )
        val evidence = Npv10WriterStageTimings(losslessStoragePressure = measured).toEvidenceMetadata()
        assertEquals("123", evidence["npv10LosslessPressureFrames"])
        assertEquals("2", evidence["npv10LosslessPressureCpuBackoffs"])
        assertEquals("1", evidence["npv10LosslessPressureFailures"])
        assertEquals("15000", evidence["npv10LosslessPressureInputBytes"])
        assertEquals("11000", evidence["npv10LosslessPressureStoredBytes"])
        assertFalse(evidence.keys.any { "qualityPass" in it || "gpuUtilization" in it })
    }

    @Test fun nativeOwnershipAndCodecContractsRemainExplicit() {
        val sourceRoot = sequenceOf(File("src/main"), File("app/src/main"))
            .first { File(it, "cpp/npv10_dmc_native.cpp").isFile }
        val native = File(sourceRoot, "cpp/npv10_dmc_native.cpp").readText()
        val codec = File(sourceRoot, "cpp/npv10_lossless_chunk_codec.cpp").readText()
        val policy = File(sourceRoot, "cpp/npv10_lossless_storage_pressure.h").readText()
        assertTrue(native.contains("losslessStoragePressure_.choose(recordWriteQueueCount_ + activeCommits_,"))
        assertTrue(native.contains("slots_.size(), maximumProcessingOverlap_)"))
        assertTrue(native.contains("maximumProcessingOverlap_ = std::max(maximumProcessingOverlap_, activeProcessors_)"))
        assertTrue(native.contains("losslessStoragePressure_.observeSourceTimestamp(timestampNs)"))
        assertTrue(native.contains("constexpr jsize kValueCount = 55;"))
        assertTrue(native.contains("output[47] = losslessStoragePressure_.cooldownFrames();"))
        assertTrue(native.contains("kSlotReuseWaitDuration = std::chrono::seconds(2)"))
        assertTrue(codec.contains("if (upstreamAcceleration < 1 || upstreamAcceleration > 65537) return result;"))
        assertTrue(policy.contains("kFastAcceleration = 1024"))
        assertTrue(policy.contains("kStorageAcceleration = 64"))
        assertTrue(policy.contains("interval < sourceIntervalNs_"))
    }

    @Test fun appendedKernelCompletionFieldsDoNotChangePressureTelemetry() {
        val previous = LongArray(48) { it.toLong() }
        previous[32] = 1L; previous[33] = 1L
        val extended = previous.copyOf(52)
        extended[48] = 1L; extended[49] = 0L; extended[50] = 9001L; extended[51] = 9001L
        assertEquals(Npv10LosslessStoragePressureTelemetry.fromNative(previous),
            Npv10LosslessStoragePressureTelemetry.fromNative(extended))
        assertTrue(Npv10KernelAioTelemetry.fromNative(extended).allSubmittedRequestsCompleted)
        val ownedExtension = extended.copyOf(54)
        ownedExtension[52] = 2250L; ownedExtension[53] = 38_000_000L
        assertEquals(Npv10LosslessStoragePressureTelemetry.fromNative(previous),
            Npv10LosslessStoragePressureTelemetry.fromNative(ownedExtension))
    }
}
