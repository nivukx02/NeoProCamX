package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class Npv10IntegrityValidatorTest {

    @Test
    fun writerRejectsNativeP010SessionWhenRuntimeFrameIsEightBit() {
        val output = Files.createTempFile("npv10-p010-mismatch", ".npv10").toFile()
        val config = nativeP010Config()

        var rejected = false
        Npv10FileWriter(output, config).use { writer ->
            try {
                writer.writeFrame(frame(bitDepth = 8))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
        }

        assertTrue(rejected)
    }

    @Test
    fun validatorBuildsPrivateDeviceEvidenceReportFromRuntimeWriterStats() {
        val privateRoot = Files.createTempDirectory("npv10-private-evidence").toFile()
        val config = nativeP010Config()
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(frame(bitDepth = 10))
        }

        val evidence = writer.buildDeviceEvidence(
            privateRoot = privateRoot,
            droppedFrames = 0,
            generatedAtEpochMs = 2L
        )
        val sidecar = Npv10IntegrityValidator.writeDeviceEvidence(evidence)

        assertEquals(Npv10AuditStatus.PASS, evidence.auditStatus)
        assertTrue(evidence.privateMaster)
        assertTrue(evidence.noMediaPresent)
        assertTrue(evidence.nativeTenBitCapture)
        assertEquals("3840x2160", evidence.selectedResolution)
        assertEquals("2x2", evidence.captureResolution)
        assertEquals(10, evidence.firstFrameBitDepth)
        assertEquals(1L, evidence.frameCount)
        assertEquals(0, evidence.droppedFrames)
        assertTrue(sidecar.isFile)
        assertTrue(sidecar.readText().contains("auditStatus=PASS"))
        assertTrue(sidecar.readText().contains("nativeTenBitCapture=true"))
        assertTrue(sidecar.readText().contains("sourceBitDepthPolicy=NATIVE_YCBCR_P010_SOURCE_10BIT_OR_HIGHER"))
        assertTrue(sidecar.readText().contains("sourceBitDepthCheckPass=true"))
        assertTrue(sidecar.readText().contains("storageBitDepthCheckPass=true"))
        assertTrue(sidecar.readText().contains("frameRecordByteCountPass=true"))
        assertTrue(sidecar.readText().contains("privateMasterWriterPolicy=APP_PRIVATE_NPV10_MASTER_WRITER_V3_DIRECT_P010_POST_RECORD_HASHED_CLOSE_FLUSH"))
    }

    @Test
    fun writerRejectsTruncatedPrivateMasterPlaneBeforePacking() {
        val output = Files.createTempFile("npv10-truncated-plane", ".npv10").toFile()
        val config = nativeP010Config()

        var rejected = false
        Npv10FileWriter(output, config).use { writer ->
            try {
                writer.writeFrame(frame(bitDepth = 10).copy(yBytes = byteArrayOf(1, 2)))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
        }

        assertTrue(rejected)
    }

    @Test
    fun validatorAddsEliteVideoQualityCertificateToPrivateEvidenceReport() {
        val privateRoot = Files.createTempDirectory("npv10-elite-certificate").toFile()
        val config = nativeP010Config().copy(
            colorProfile = "N-Log",
            exactSelectedResolution = true,
            featureMetadata = mapOf(
                "selectedResolution" to "2x2",
                "captureResolution" to "2x2",
                "sourceImageFormat" to "54"
            )
        )
        val master = Npv10MasterStore(privateRoot).createMasterFile(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(frame(bitDepth = 10))
        }

        val evidence = writer.buildDeviceEvidence(
            privateRoot = privateRoot,
            droppedFrames = 0,
            generatedAtEpochMs = 2L
        )
        val sidecar = Npv10IntegrityValidator.writeDeviceEvidence(evidence)

        assertEquals(EliteQualityAuditStatus.PASS, evidence.eliteVideoQualityAuditStatus)
        assertEquals(EliteVideoQualityTier.ELITE_NPV10_NATIVE_P010, evidence.eliteVideoQualityTier)
        assertEquals("N-Log Base", evidence.eliteNormalizedLogVersion)
        assertTrue(sidecar.readText().contains("eliteVideoQualityAuditStatus=PASS"))
        assertTrue(sidecar.readText().contains("eliteVideoQualityTier=ELITE_NPV10_NATIVE_P010"))
        assertTrue(sidecar.readText().contains("eliteNormalizedLogVersion=N-Log Base"))
        assertTrue(sidecar.readText().contains("eliteTruthfulNative10Bit=true"))
        assertTrue(sidecar.readText().contains("selectedProfile=N-Log Base"))
        assertTrue(sidecar.readText().contains("activeTransport=NPV10_PRIVATE_MASTER"))
        assertTrue(sidecar.readText().contains("transfer=N-Log"))
        assertTrue(sidecar.readText().contains("primaries=BT.2020"))
        assertTrue(sidecar.readText().contains("matrix=BT.2020-NCL"))
        assertTrue(sidecar.readText().contains("encodedBitDepth=10"))
        assertTrue(sidecar.readText().contains("internalMathBitDepth=10"))
        assertTrue(sidecar.readText().contains("dynamicMetadataWritten=false"))
        assertTrue(sidecar.readText().contains("npv10MasterAvailable=true"))
    }

    @Test
    fun movCertificateCarriesNdrCarrierOnlyAndPrivateMasterEvidence() {
        val output = Files.createTempFile("npv10-ndr-carrier", ".mov").toFile()
        writeRealV210Mov(output, sampleCount = 1)

        val audit = Npv10IntegrityValidator.auditMovFile(
            output = output,
            frameCount = 1,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            movSelectedProfile = "NDR10+",
            movSelectedColorSpace = "Rec.2020-NDR10+",
            movActiveTransport = Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10,
            proprietaryProfileAuthority = SovereignVideoProfilePolicy.AUTHORITY_NDR,
            bt2100TransportCarrierOnly = true,
            bt2100CarrierMode = SovereignVideoProfilePolicy.CARRIER_BT2100_PQ,
            vendorDynamicRangeProfileUsed = false,
            vendorDynamicMetadataWritten = false,
            dolbyVisionOrHdr10PlusSubstitutionBlocked = true,
            npv10PrivateMasterUnmodified = true,
            rawMonitorDestructiveToMaster = false,
            previewRecordingNpv10ParityPolicy = SovereignVideoProfilePolicy.PARITY_LOCK,
            masterEnhancementDestructive = false
        )
        val text = audit.conversionCertificate

        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertTrue(text.contains("proprietaryProfileAuthority=NEO_NDR_PROPRIETARY"))
        assertTrue(text.contains("bt2100TransportCarrierOnly=true"))
        assertTrue(text.contains("bt2100CarrierMode=BT.2100_PQ_ANDROID_CARRIER_ONLY"))
        assertTrue(text.contains("vendorDynamicRangeProfileUsed=false"))
        assertTrue(text.contains("vendorDynamicMetadataWritten=false"))
        assertTrue(text.contains("dolbyVisionOrHdr10PlusSubstitutionBlocked=true"))
        assertTrue(text.contains("npv10PrivateMasterUnmodified=true"))
        assertTrue(text.contains("masterEnhancementDestructive=false"))
        assertTrue(text.contains("previewRecordingNpv10ParityPolicy=SELECTED_PROFILE_TRANSFER_MATRIX_BIT_DEPTH_LOCKED"))
    }

    @Test
    fun movAuditRejectsAtomAsciiWithoutRealBoxesAndDoesNotSlurpWholeFile() {
        // Regression: the ASCII markers scattered at arbitrary offsets (which the old substring scan
        // wrongly certified PASS) must now FAIL the real box walk — a corrupt/fabricated MOV can never
        // be blessed as a valid master.
        val fake = Files.createTempFile("npv10-fake-atoms", ".mov").toFile()
        RandomAccessFile(fake, "rw").use { file ->
            file.write("ftyp".toByteArray(Charsets.US_ASCII))
            file.seek(2_500_000L)
            file.write("mdat".toByteArray(Charsets.US_ASCII))
            file.seek(5_000_000L)
            file.write("moov".toByteArray(Charsets.US_ASCII))
            file.seek(7_500_000L)
            file.write("v210".toByteArray(Charsets.US_ASCII))
        }
        val fakeAudit = Npv10IntegrityValidator.auditMovFile(
            output = fake,
            frameCount = 1,
            payloadFormat = Npv10PayloadFormat.P010_LE
        )
        assertEquals(Npv10AuditStatus.FAIL, fakeAudit.auditStatus)
        assertFalse(fakeAudit.validQuickTimeAtoms)

        val real = Files.createTempFile("npv10-real-atoms", ".mov").toFile()
        writeRealV210Mov(real, sampleCount = 1)
        val realAudit = Npv10IntegrityValidator.auditMovFile(
            output = real,
            frameCount = 1,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            inputMasterHashUnchanged = true,
            masterEnhancementDestructive = false,
            npv10PrivateMasterUnmodified = true
        )
        assertEquals(Npv10AuditStatus.PASS, realAudit.auditStatus)
        assertTrue(realAudit.validQuickTimeAtoms)

        val auditBody = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt")
            .readText()
            .substringAfter("fun auditMovFile(")
            .substringBefore("return Npv10MovConversionAudit(")
        assertFalse(auditBody.contains("readBytes()"))
        assertTrue(auditBody.contains("auditQuickTimeStructure(output"))
    }

    @Test
    fun validatorFailsEvidenceForMasterOutsidePrivatePersistentStore() {
        val privateRoot = Files.createTempDirectory("npv10-private-root").toFile()
        val publicLikeRoot = Files.createTempDirectory("npv10-public-root").toFile()
        val output = publicLikeRoot.resolve("public-master.npv10")
        val config = nativeP010Config()
        val writer = Npv10FileWriter(output, config)
        writer.use {
            it.writeFrame(frame(bitDepth = 10))
        }

        val evidence = writer.buildDeviceEvidence(
            privateRoot = privateRoot,
            droppedFrames = 0,
            generatedAtEpochMs = 2L
        )

        assertEquals(Npv10AuditStatus.FAIL, evidence.auditStatus)
        assertFalse(evidence.privateMaster)
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
            exactSelectedResolution = false,
            featureMetadata = mapOf(
                "selectedResolution" to "3840x2160",
                "captureResolution" to "2x2",
                "sourceImageFormat" to "54"
            )
        )

    private fun frame(bitDepth: Int): RawYuvFrame =
        RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = if (bitDepth >= 10) tenBitPlane(16, 32, 64, 128) else byteArrayOf(1, 2, 3, 4),
            uBytes = if (bitDepth >= 10) tenBitPlane(512) else byteArrayOf(128.toByte()),
            vBytes = if (bitDepth >= 10) tenBitPlane(512) else byteArrayOf(128.toByte()),
            yRowStride = if (bitDepth >= 10) 4 else 2,
            yPixelStride = if (bitDepth >= 10) 2 else 1,
            uRowStride = if (bitDepth >= 10) 2 else 1,
            vRowStride = if (bitDepth >= 10) 2 else 1,
            uPixelStride = if (bitDepth >= 10) 2 else 1,
            vPixelStride = if (bitDepth >= 10) 2 else 1,
            timestamp = 123_000L,
            bitDepth = bitDepth
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

    /**
     * Writes a genuine single-track v210 QuickTime container using the app's own writer so the real
     * atom-box walk in auditMovFile can PASS it. One sample per call keeps stsz sample_count == frameCount.
     */
    private fun writeRealV210Mov(output: File, sampleCount: Int) {
        val writer = QuickTimeMovFileWriter(
            outputPath = output.absolutePath,
            containerLabel = "NPV10 Test Master"
        )
        val track = writer.addVideoTrack(
            QuickTimeVideoTrackConfig(
                mime = "video/v210",
                width = 2,
                height = 2,
                fps = 24,
                codecConfig = ByteArray(0),
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
            )
        )
        repeat(sampleCount) { index ->
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = ByteArray(128),
                    presentationTimeUs = index * 41_666L,
                    flags = 1
                )
            )
        }
        writer.close()
    }
}
