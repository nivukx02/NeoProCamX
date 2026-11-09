package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class Npv10QualityGateTest {

    @Test
    fun preflightClassifiesNativeP010AsEliteWhenPrivateStorageIsReady() {
        val privateRoot = Files.createTempDirectory("npv10-quality-private").toFile()
        Npv10MasterStore(privateRoot).createMasterFile(nativeP010Config())

        val report = Npv10QualityGate.preflight(
            config = nativeP010Config(),
            privateRoot = privateRoot,
            availableBytes = 64L * 1024L * 1024L
        )

        assertEquals(Npv10QualityTier.ELITE_NATIVE_P010, report.qualityTier)
        assertEquals(Npv10AuditStatus.PASS, report.auditStatus)
        val storedBytesPerFrame = Npv10ContainerPolicy.estimatedStoredBytesPerFrame(
            width = 2,
            height = 2,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            packetCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        assertEquals(storedBytesPerFrame, report.bytesPerFrame)
        assertEquals(storedBytesPerFrame * 24L, report.bytesPerSecond)
        assertTrue(report.noMediaPresent)
    }

    @Test
    fun preflightFailsUnsafeWhenDiskBudgetCannotHoldShortBurst() {
        val privateRoot = Files.createTempDirectory("npv10-quality-low-space").toFile()
        Npv10MasterStore(privateRoot).createMasterFile(nativeP010Config())

        val report = Npv10QualityGate.preflight(
            config = nativeP010Config(),
            privateRoot = privateRoot,
            availableBytes = 1L
        )

        assertEquals(Npv10QualityTier.FAILED_UNSAFE, report.qualityTier)
        assertEquals(Npv10AuditStatus.FAIL, report.auditStatus)
        assertTrue(report.failureReasons.any { reason -> reason.contains("free space") })
    }

    @Test
    fun rawSensorPackedMastersUseFlagshipBudgetAndLosslessBypassRecovery() {
        val privateRoot = Files.createTempDirectory("npv10-quality-raw12").toFile()
        val config = raw12Config()
        Npv10MasterStore(privateRoot).createMasterFile(config)

        val report = Npv10QualityGate.preflight(
            config = config,
            privateRoot = privateRoot,
            availableBytes = Long.MAX_VALUE
        )

        assertEquals(Npv10AuditStatus.PASS, report.auditStatus)
        assertEquals(Npv10QualityTier.ELITE_RAW12_SENSOR, report.qualityTier)
        assertEquals(6.0, report.frameProcessingBudgetMs, 0.0001)
        assertEquals("LOSSLESS_PACKING_BYPASS", report.processingSpikeFallback)
        assertTrue(report.rawSensorRequired)
        assertTrue(report.failureReasons.isEmpty())
    }

    @Test
    fun malformedRaw12ConfigIsFailedUnsafeAndNeverClassifiedAsYuvFallback() {
        val malformedRaw = nativeP010Config().copy(
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            storageBitDepth = 12,
            payloadFormat = Npv10PayloadFormat.RAW12_NPV10_PACKED,
            rawSensorCapture = false
        )

        assertEquals(Npv10QualityTier.FAILED_UNSAFE, Npv10QualityGate.tierForConfig(malformedRaw))
    }

    @Test
    fun planarTenBitConfigIsClassifiedAsTenBitNotEightBitExpanded() {
        val planarTenBit = nativeP010Config().copy(
            sourceFormat = "YUV420P10",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.YUV420P10_LE
        )

        assertEquals(Npv10QualityTier.ELITE_PLANAR_YUV420P10, Npv10QualityGate.tierForConfig(planarTenBit))
    }

    @Test
    fun postRecordAuditVerifiesFrameCountPayloadBytesAndSessionHashForCertificate() {
        val privateRoot = Files.createTempDirectory("npv10-quality-certificate").toFile()
        val config = nativeP010Config()
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(frame(timestamp = 1_000_000L))
            it.writeFrame(frame(timestamp = 42_666_666L))
        }

        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = writer.frameCount,
            expectedPayloadBytes = writer.payloadBytesWritten
        )
        val evidence = writer.buildDeviceEvidence(
            privateRoot = privateRoot,
            droppedFrames = 0,
            generatedAtEpochMs = 2L
        )

        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertEquals(2L, audit.frameCount)
        assertEquals(writer.payloadBytesWritten, audit.payloadBytes)
        assertEquals(writer.payloadBytesWritten, audit.expectedPayloadBytes)
        assertEquals(audit.sessionHash, evidence.sessionHash)
        assertEquals(audit.sessionHash, evidence.postRecordSessionHash)
        assertTrue(audit.sessionHashCheckPass)
        assertTrue(audit.frameRecordByteCountValid)
        assertEquals(Npv10QualityTier.ELITE_NATIVE_P010, evidence.qualityTier)
        assertEquals(Npv10AuditStatus.PASS, evidence.postRecordAuditStatus)
        assertEquals(0.0, evidence.dropRate, 0.0001)
        assertTrue(evidence.sessionHash.length == 64)
        assertTrue(evidence.postRecordSessionHash.length == 64)
        assertTrue(evidence.sessionHashCheckPass)
        assertTrue(evidence.payloadByteCountExact)
        assertTrue(evidence.frameRecordByteCountPass)
        assertTrue(evidence.sourceBitDepthCheckPass)
        assertTrue(evidence.storageBitDepthCheckPass)
        assertTrue(evidence.droppedFrameCheckPass)
        assertTrue(evidence.timestampCheckPass)
        assertTrue(evidence.crcCheckPass)
        assertTrue(evidence.byteCountCheckPass)
        assertTrue(evidence.writeLatencyCheckPass)
        assertTrue(evidence.toEvidenceText().contains("qualityTier=ELITE_NATIVE_P010"))
        assertTrue(evidence.toEvidenceText().contains("postRecordAuditStatus=PASS"))
        assertTrue(evidence.toEvidenceText().contains("sessionHash="))
        assertTrue(evidence.toEvidenceText().contains("postRecordSessionHash="))
        assertTrue(evidence.toEvidenceText().contains("sessionHashCheckPass=true"))
        assertTrue(evidence.toEvidenceText().contains("payloadByteCountExact=true"))
        assertTrue(evidence.toEvidenceText().contains("sourceBitDepthPolicy=NATIVE_YCBCR_P010_SOURCE_10BIT_OR_HIGHER"))
        assertTrue(evidence.toEvidenceText().contains("droppedFrameCheckPass=true"))
        assertTrue(evidence.toEvidenceText().contains("crcCheckPass=true"))
        assertTrue(evidence.toEvidenceText().contains("timestampCheckPass=true"))
        assertTrue(evidence.toEvidenceText().contains("byteCountCheckPass=true"))
        assertTrue(evidence.toEvidenceText().contains("writeLatencyCheckPass=true"))
    }

    @Test
    fun writeLatencyAllowsBoundedAsyncSpikeOnlyWhenAverageAndDropsAreSafe() {
        assertEquals(25.0, Npv10QualityGate.maxSafeWriteMs(fps = 30), 0.0001)
        assertEquals(150.0, Npv10QualityGate.maxSafeWriteSpikeMs(fps = 30), 0.0001)

        assertTrue(
            Npv10QualityGate.writeLatencyWithinRealtimeBudget(
                averageWriteTimeMs = 17.2,
                maxWriteTimeMs = 135.1,
                fps = 30,
                droppedFrames = 0
            )
        )
        assertFalse(
            Npv10QualityGate.writeLatencyWithinRealtimeBudget(
                averageWriteTimeMs = 26.0,
                maxWriteTimeMs = 135.1,
                fps = 30,
                droppedFrames = 0
            )
        )
        assertFalse(
            Npv10QualityGate.writeLatencyWithinRealtimeBudget(
                averageWriteTimeMs = 17.2,
                maxWriteTimeMs = 150.1,
                fps = 30,
                droppedFrames = 0
            )
        )
        assertFalse(
            Npv10QualityGate.writeLatencyWithinRealtimeBudget(
                averageWriteTimeMs = 17.2,
                maxWriteTimeMs = 135.1,
                fps = 30,
                droppedFrames = 1
            )
        )
    }

    @Test
    fun postRecordAuditFailsWhenExpectedFrameCountDoesNotMatch() {
        val privateRoot = Files.createTempDirectory("npv10-quality-audit-fail").toFile()
        val config = nativeP010Config()
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        Npv10FileWriter(master, config).use { writer ->
            writer.writeFrame(frame(timestamp = 1_000_000L))
        }

        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = 2L,
            expectedPayloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(2, 2) * 2L
        )

        assertEquals(Npv10AuditStatus.FAIL, audit.auditStatus)
        assertTrue(audit.failureReasons.any { reason -> reason.contains("frame count") })
    }

    @Test
    fun postRecordAuditFailsZeroFrameMasterClearly() {
        val privateRoot = Files.createTempDirectory("npv10-zero-frame").toFile()
        val config = nativeP010Config()
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        Npv10FileWriter(master, config).close()

        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = 0L,
            expectedPayloadBytes = 0L
        )

        assertEquals(Npv10AuditStatus.FAIL, audit.auditStatus)
        assertEquals(0L, audit.frameCount)
        assertFalse(audit.crcValidated)
        assertTrue(audit.byteCountValid)
        assertTrue(audit.failureReasons.any { reason -> reason.contains("frame count is zero") })
    }

    @Test
    fun postRecordAuditFailsDuplicateTimestampsAsNonMonotonic() {
        val privateRoot = Files.createTempDirectory("npv10-duplicate-timestamp").toFile()
        val config = nativeP010Config()
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        Npv10FileWriter(master, config).use { writer ->
            writer.writeFrame(frame(timestamp = 1_000_000L))
            writer.writeFrame(frame(timestamp = 2_000_000L))
        }
        // The production writer rejects duplicates before commit. Corrupt a valid second record
        // directly so this adversarial test continues to exercise the independent post-record gate.
        val secondFrameStart = Npv10FileReader(master).use { reader ->
            requireNotNull(reader.readNextFrame())
            reader.bytePosition
        }
        RandomAccessFile(master, "rw").use { file ->
            file.seek(secondFrameStart + Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size)
            file.writeLong(1_000_000L)
        }

        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = 2L,
            expectedPayloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(2, 2) * 2L
        )

        assertEquals(Npv10AuditStatus.FAIL, audit.auditStatus)
        assertFalse(audit.monotonicTimestamps)
        assertFalse(audit.timestampContinuityValid)
        assertTrue(audit.failureReasons.any { reason -> reason.contains("strictly monotonic") })
    }

    @Test
    fun postRecordAuditRejectsStructurallyValidMasterWithNonPositiveTimestamp() {
        listOf(0L, -1L).forEach { invalidTimestampNs ->
            val privateRoot = Files.createTempDirectory("npv10-invalid-audit-time").toFile()
            val config = nativeP010Config()
            val master = Npv10MasterStore(privateRoot).createMasterFile(config)
            val writer = Npv10FileWriter(master, config)
            writer.use {
                it.writeFrame(frame(timestamp = 1_000_000L))
            }
            val frameStart = Npv10FileReader(master).use { it.bytePosition }
            RandomAccessFile(master, "rw").use { file ->
                file.seek(frameStart + Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size)
                file.writeLong(invalidTimestampNs)
            }

            val audit = Npv10QualityGate.auditMasterFile(
                masterFile = master,
                expectedFrameCount = writer.frameCount,
                expectedPayloadBytes = writer.payloadBytesWritten
            )

            assertEquals(Npv10AuditStatus.FAIL, audit.auditStatus)
            assertFalse(audit.timestampContinuityValid)
            assertTrue(
                audit.failureReasons.any { reason -> reason.contains("timestamp must be positive") }
            )
        }
    }

    @Test
    fun deviceEvidenceFailsWhenAnyDroppedFrameIsReported() {
        val privateRoot = Files.createTempDirectory("npv10-dropped-frame-evidence").toFile()
        val config = nativeP010Config()
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(frame(timestamp = 1_000_000L))
            it.writeFrame(frame(timestamp = 42_666_666L))
        }

        val evidence = writer.buildDeviceEvidence(
            privateRoot = privateRoot,
            droppedFrames = 1,
            generatedAtEpochMs = 2L
        )

        assertEquals(Npv10AuditStatus.FAIL, evidence.auditStatus)
        assertFalse(evidence.droppedFrameCheckPass)
        assertTrue(evidence.toEvidenceText().contains("droppedFrameCheckPass=false"))
    }

    private fun nativeP010Config(): Npv10SessionConfig =
        Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            exactSelectedResolution = true,
            featureMetadata = mapOf(
                "selectedResolution" to "2x2",
                "captureResolution" to "2x2",
                "sourceImageFormat" to "54"
            )
        )

    private fun raw12Config(): Npv10SessionConfig =
        Npv10SessionConfig(
            width = 4096,
            height = 2160,
            fps = 60,
            mode = "RAW",
            colorProfile = "N-RAW",
            startedAtEpochMs = 1L,
            sourceFormat = "RAW_SENSOR",
            sourceBitDepth = 12,
            storageBitDepth = 12,
            payloadFormat = Npv10PayloadFormat.RAW12_NPV10_PACKED,
            rawSensorCapture = true,
            gpuDemosaicEnabled = true,
            darkFrameCalibrationEnabled = true,
            zeroCopyNativeAcquisition = true,
            featureMetadata = mapOf(
                "selectedResolution" to "4096x2160",
                "captureResolution" to "4096x2160",
                "sourceImageFormat" to ImageFormat.RAW_SENSOR.toString()
            )
        )

    private fun frame(timestamp: Long): RawYuvFrame =
        RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = tenBitPlane(16, 32, 64, 128),
            uBytes = tenBitPlane(512),
            vBytes = tenBitPlane(512),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            timestamp = timestamp,
            bitDepth = 10
        )

    private fun tenBitPlane(vararg samples: Int): ByteArray {
        val output = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(0, 1023)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0x03).toByte()
        }
        return output
    }
}
