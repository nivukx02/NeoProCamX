package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Npv10VerifiedMasterPublicationTest {
    @Test
    fun `exact audited lineage publishes a readable marker`() {
        val master = createMaster()

        val marker = Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.PASS),
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
            syncDirectory = { true }
        )

        assertTrue(marker.isFile)
        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
        assertEquals(
            Npv10TerminalCadenceBoundary(
                timestampNs = 1_000_000_000L,
                excludedMissingFrames = 0L
            ),
            Npv10VerifiedMasterPublication.terminalCadenceBoundary(master)
        )
    }

    @Test
    fun `marker directory fsync failure removes publication marker`() {
        val master = createMaster()
        var syncCalls = 0

        assertThrows(IllegalStateException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.PASS),
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
                syncDirectory = { ++syncCalls != 2 }
            )
        }

        assertTrue(syncCalls == 3)
        assertFalse(Npv10VerifiedMasterPublication.markerFor(master).exists())
        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `marker fsync failure quarantines publication when deletion fails`() {
        val master = createMaster()
        var syncCalls = 0

        assertThrows(IllegalStateException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.PASS),
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
                syncDirectory = { ++syncCalls != 2 },
                deleteMarker = { false }
            )
        }

        assertTrue(syncCalls == 3)
        assertFalse(Npv10VerifiedMasterPublication.markerFor(master).exists())
        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `failed quality report directory fsync prevents publication`() {
        val master = createMaster()

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.PASS),
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
                syncDirectory = { false }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.markerFor(master).exists())
    }

    @Test
    fun `absent external lab proof does not block exact internal master publication`() {
        val master = createMaster()

        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.NOT_PROVEN),
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
            syncDirectory = { true }
        )

        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `failed external quality claim remains honest without erasing exact internal master`() {
        val master = createMaster()

        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.FAIL),
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.FAIL,
            syncDirectory = { true }
        )

        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `missing quality evidence report cannot publish`() {
        val master = createMaster()
        val missingReport = File(master.parentFile, "missing.quality-certificate.txt")

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = missingReport,
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
                syncDirectory = { true }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `unavailable quality report cannot block exact internal master publication`() {
        val master = createMaster()

        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = null,
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
            syncDirectory = { true }
        )

        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
        assertFalse(Npv10VerifiedMasterPublication.isQualityEvidenceReportIntact(master))
    }

    @Test
    fun `unavailable quality report can never claim pass`() {
        val master = createMaster()

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = null,
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
                syncDirectory = { true }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `marker readback rejects unavailable quality report claiming pass`() {
        val master = createMaster()
        val marker = Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = null,
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
            syncDirectory = { true }
        )
        marker.writeText(
            marker.readText().replace(
                "qualityEvidenceStatus=NOT_PROVEN",
                "qualityEvidenceStatus=PASS"
            )
        )

        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.requireVerifiedForConversion(master)
        }
    }

    @Test
    fun `quality evidence report from another master cannot publish`() {
        val master = createMaster()
        val otherMaster = File(master.parentFile, "different-master.npv10").apply {
            writeBytes(ByteArray(64) { it.toByte() })
        }
        val mismatchedReport = createQualityCertificate(
            otherMaster,
            RecordingQualityEvidenceStatus.NOT_PROVEN
        )

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = mismatchedReport,
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
                syncDirectory = { true }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `quality evidence status mismatch cannot publish`() {
        val master = createMaster()
        val report = createQualityCertificate(master, RecordingQualityEvidenceStatus.FAIL)

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = exactLedger(),
                qualityCertificate = report,
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
                syncDirectory = { true }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `invalid capture lineage can never publish`() {
        val master = createMaster()
        val invalid = exactLedger().copy(missingCadenceFrames = 1L)

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master),
                captureLedger = invalid,
                qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.PASS),
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
                syncDirectory = { true }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.markerFor(master).exists())
    }

    @Test
    fun `failed structural or elite audit can never publish`() {
        val master = createMaster()
        val report = createQualityCertificate(master, RecordingQualityEvidenceStatus.NOT_PROVEN)
        val rejectedEvidence = listOf(
            passingEvidence(master).copy(auditStatus = Npv10AuditStatus.FAIL),
            passingEvidence(master).copy(eliteVideoQualityAuditStatus = EliteQualityAuditStatus.FAIL)
        )

        rejectedEvidence.forEach { evidence ->
            assertThrows(IllegalArgumentException::class.java) {
                Npv10VerifiedMasterPublication.publish(
                    master = master,
                    evidence = evidence,
                    captureLedger = exactLedger(),
                    qualityCertificate = report,
                    qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
                    syncDirectory = { true }
                )
            }
            assertFalse(Npv10VerifiedMasterPublication.markerFor(master).exists())
        }
    }

    @Test
    fun `slow storage evidence cannot hide an otherwise exact durable master`() {
        val master = createMaster()
        val report = createQualityCertificate(master, RecordingQualityEvidenceStatus.NOT_PROVEN)

        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master).copy(writeLatencyCheckPass = false),
            captureLedger = exactLedger(),
            qualityCertificate = report,
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
            syncDirectory = { true }
        )

        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `failed marker readback removes the publication`() {
        val master = createMaster()
        val report = createQualityCertificate(master, RecordingQualityEvidenceStatus.NOT_PROVEN)

        assertThrows(IllegalStateException::class.java) {
            Npv10VerifiedMasterPublication.publish(
                master = master,
                evidence = passingEvidence(master).copy(crcCheckPass = false),
                captureLedger = exactLedger(),
                qualityCertificate = report,
                qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
                syncDirectory = { true }
            )
        }

        assertFalse(Npv10VerifiedMasterPublication.markerFor(master).exists())
        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `master mutation invalidates an existing publication marker`() {
        val master = createMaster()
        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.PASS),
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.PASS,
            syncDirectory = { true }
        )
        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))

        assertTrue(master.setLastModified(master.lastModified() + 2_000L))

        assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
    }

    @Test
    fun `quality evidence report mutation is reported without invalidating internal master`() {
        val master = createMaster()
        val certificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.NOT_PROVEN)
        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = certificate,
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
            syncDirectory = { true }
        )
        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
        assertTrue(Npv10VerifiedMasterPublication.isQualityEvidenceReportIntact(master))

        certificate.appendText("tampered=true\n")

        assertTrue(Npv10VerifiedMasterPublication.isVerified(master))
        assertFalse(Npv10VerifiedMasterPublication.isQualityEvidenceReportIntact(master))
    }

    @Test
    fun `conversion admission rejects every unpublished master`() {
        val master = createMaster()

        assertThrows(IllegalArgumentException::class.java) {
            Npv10VerifiedMasterPublication.requireVerifiedForConversion(master)
        }

        Npv10VerifiedMasterPublication.publish(
            master = master,
            evidence = passingEvidence(master),
            captureLedger = exactLedger(),
            qualityCertificate = createQualityCertificate(master, RecordingQualityEvidenceStatus.NOT_PROVEN),
            qualityEvidenceStatus = RecordingQualityEvidenceStatus.NOT_PROVEN,
            syncDirectory = { true }
        )
        Npv10VerifiedMasterPublication.requireVerifiedForConversion(master)
    }

    private fun createMaster(): File =
        Files.createTempDirectory("npv10-verified-publication")
            .resolve("strict-master.npv10")
            .toFile()
            .apply { writeBytes(ByteArray(64) { it.toByte() }) }

    private fun createQualityCertificate(
        master: File,
        status: RecordingQualityEvidenceStatus
    ): File {
        val directory = File(master.parentFile, "recording_quality_certificates").apply { mkdirs() }
        return File(directory, "${master.nameWithoutExtension}.quality-certificate.txt").apply {
            writeText(
                "qualityEvidenceStatus=${status.name}\n" +
                    "recordingId=${master.nameWithoutExtension}\n" +
                    "recordedFileName=${master.name}\n" +
                    "strict10DeviceVerified=${(status == RecordingQualityEvidenceStatus.PASS)}\n"
            )
        }
    }

    private fun exactLedger(): Npv10CaptureLedgerSnapshot = Npv10CaptureLedgerSnapshot(
        ingressFrames = 1L,
        acceptedFrames = 1L,
        committedFrames = 1L,
        ingressTimestampHash = 91L,
        acceptedTimestampHash = 91L,
        committedTimestampHash = 91L,
        firstIngressTimestampNs = 1_000_000_000L,
        lastIngressTimestampNs = 1_000_000_000L,
        firstCommittedTimestampNs = 1_000_000_000L,
        lastCommittedTimestampNs = 1_000_000_000L,
        invalidTimestamps = 0L,
        nonMonotonicIngress = 0L,
        missingCadenceFrames = 0L,
        terminalCadenceBoundaryTimestampNs = 1_000_000_000L,
        terminalCadenceExcludedMissingFrames = 0L,
        committedPayloadBytes = 128L
    )

    private fun passingEvidence(master: File): Npv10DeviceEvidence = Npv10DeviceEvidence(
        auditStatus = Npv10AuditStatus.PASS,
        qualityTier = Npv10QualityTier.ELITE_NATIVE_P010,
        eliteVideoQualityAuditStatus = EliteQualityAuditStatus.PASS,
        eliteVideoQualityTier = EliteVideoQualityTier.ELITE_NPV10_NATIVE_P010,
        eliteNormalizedHdrVersion = "OFF",
        eliteNormalizedLogVersion = "OFF",
        eliteTruthfulNative10Bit = true,
        eliteMinimumBitrateBps = 1,
        selectedProfile = "NPV10",
        activeTransport = "P010",
        transfer = "SDR",
        primaries = "BT2020",
        matrix = "BT2020_NCL",
        encodedBitDepth = 10,
        internalMathBitDepth = 16,
        dynamicMetadataWritten = false,
        npv10MasterAvailable = true,
        selectedResolution = "2x2",
        captureResolution = "2x2",
        sourceFormat = "ycbcr_p010",
        androidImageFormat = "YCBCR_P010",
        sourceBitDepth = 10,
        storageBitDepth = 10,
        payloadFormat = Npv10PayloadFormat.P010_LE,
        nativeTenBitCapture = true,
        exactSelectedResolution = true,
        firstFrameWidth = 2,
        firstFrameHeight = 2,
        firstFrameBitDepth = 10,
        firstFrameTimestampNs = 1_000_000_000L,
        frameCount = 1L,
        droppedFrames = 0,
        dropRate = 0.0,
        payloadBytes = 128L,
        fileBytes = master.length(),
        averageWriteTimeMs = 1.0,
        maxWriteTimeMs = 1.0,
        maxSafeWriteTimeMs = 40.0,
        maxTimestampJitterNs = 0L,
        averageTimestampJitterNs = 0L,
        expectedPayloadBytesPerFrame = 128L,
        expectedPayloadBytesTotal = 128L,
        payloadByteCountExact = true,
        frameHeaderBytes = 24,
        auditedFrameRecordBytes = 152L,
        expectedFrameRecordBytes = 152L,
        frameRecordByteCountPass = true,
        sourceBitDepthPolicy = "NATIVE_10_BIT",
        sourceBitDepthEvidence = "P010",
        sourceBitDepthCheckPass = true,
        storageBitDepthPolicy = "STORAGE_DEPTH_EQUALS_PAYLOAD_CONTRACT",
        storageBitDepthCheckPass = true,
        sessionHash = "a".repeat(64),
        postRecordSessionHash = "a".repeat(64),
        sessionHashCheckPass = true,
        postRecordAuditStatus = Npv10AuditStatus.PASS,
        droppedFrameCheckPass = true,
        timestampCheckPass = true,
        crcCheckPass = true,
        byteCountCheckPass = true,
        writeLatencyCheckPass = true,
        privateMasterWriterPolicy = "STRICT_TEST",
        masterPath = master.absolutePath,
        privateMaster = true,
        noMediaPresent = true,
        generatedAtEpochMs = 1L
    )
}
