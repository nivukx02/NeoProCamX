package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * s6-f04 NPV10 private-master integrity proofs (test-only; the master path stays gated OFF). Proves
 * write->read byte-identity per payloadFormat, the P010 word16[15:6]=code10[9:0] convention, session
 * hash / CRC tamper detection, and bit-depth honesty in the written header. Expected payload bytes
 * are derived from the REAL Npv10FramePacker.pack(...) output (the same function the writer calls),
 * never hand-invented.
 */
class Npv10MasterIntegrityRoundTripTest {

    @Test
    fun writerToReaderRoundTripIsBytewiseIdenticalForNativeP010Payload() {
        val config = p010Config()
        val (root, master) = newMaster(config)
        val frame1 = tenBitFrame(1_000_000L, 300)
        val expected = Npv10FramePacker.pack(frame1, Npv10PayloadFormat.P010_LE)
        Npv10FileWriter(master, config).use {
            it.writeFrame(frame1)
            it.writeFrame(tenBitFrame(42_666_666L, 512))
        }
        Npv10FileReader(master).use { reader ->
            val r1 = reader.readNextFrame()
            assertEquals(expected.size, r1?.payload?.size)
            assertArrayEquals(expected, r1?.payload)
        }
        root.deleteRecursively()
    }

    @Test
    fun writerToReaderRoundTripIsBytewiseIdenticalForEightBitExpandedPayload() {
        val config = eightBitConfig()
        val (root, master) = newMaster(config)
        val frame1 = eightBitFrame(10_000L)
        val expected = Npv10FramePacker.pack(frame1, Npv10PayloadFormat.YUV420_8BIT_EXPANDED)
        Npv10FileWriter(master, config).use {
            it.writeFrame(frame1)
            it.writeFrame(eightBitFrame(20_000L))
        }
        Npv10FileReader(master).use { reader ->
            val r1 = reader.readNextFrame()
            assertEquals(expected.size, r1?.payload?.size)
            assertArrayEquals(expected, r1?.payload)
        }
        root.deleteRecursively()
    }

    @Test
    fun p010PayloadStoresCode10InHighTenBitsWithZeroLowPadding() {
        val sample = 300
        val expected = Npv10FramePacker.pack(tenBitFrame(1_000_000L, sample), Npv10PayloadFormat.P010_LE)
        // Little-endian first 16-bit word == code10 << 6 (word16[15:6]=code10[9:0]); low 6 bits zero.
        val word = (expected[0].toInt() and 0xFF) or ((expected[1].toInt() and 0xFF) shl 8)
        assertEquals(sample shl 6, word)
        assertEquals(0, word and 0x3F)
    }

    @Test
    fun cleanMasterSessionHashRoundTripsBetweenWriterAndReaderAudit() {
        val config = p010Config()
        val (root, master) = newMaster(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(tenBitFrame(1_000_000L, 300))
            it.writeFrame(tenBitFrame(42_666_666L, 512))
        }
        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = writer.frameCount,
            expectedPayloadBytes = writer.payloadBytesWritten
        )
        val evidence = writer.buildDeviceEvidence(privateRoot = root, droppedFrames = 0, generatedAtEpochMs = 2L)
        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertTrue(audit.sessionHashCheckPass)
        assertEquals(64, audit.sessionHash.length)
        assertEquals(audit.sessionHash, evidence.sessionHash)
        root.deleteRecursively()
    }

    @Test
    fun tamperedPayloadByteFailsAuditWithEmptySessionHash() {
        val config = p010Config()
        val (root, master) = newMaster(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(tenBitFrame(1_000_000L, 300))
            it.writeFrame(tenBitFrame(42_666_666L, 512))
        }
        val cleanHash = writer.buildDeviceEvidence(privateRoot = root, droppedFrames = 0, generatedAtEpochMs = 2L).sessionHash
        // Flip the last payload byte (frame layout ends with the payload, after its CRC field).
        val bytes = master.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        master.writeBytes(bytes)

        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = writer.frameCount,
            expectedPayloadBytes = writer.payloadBytesWritten,
            expectedSessionHash = cleanHash
        )
        assertEquals(Npv10AuditStatus.FAIL, audit.auditStatus)
        assertFalse(audit.sessionHashCheckPass)
        assertFalse(audit.crcValidated)
        // CRC failure zeroes the recomputed hash (not a different 64-hex value).
        assertEquals("", audit.sessionHash)
        assertNotEquals(cleanHash, audit.sessionHash)
        assertTrue(audit.failureReasons.isNotEmpty())
        root.deleteRecursively()
    }

    @Test
    fun tamperedPayloadByteTripsReaderCrcGuard() {
        val config = p010Config()
        val (root, master) = newMaster(config)
        Npv10FileWriter(master, config).use {
            it.writeFrame(tenBitFrame(1_000_000L, 300))
            it.writeFrame(tenBitFrame(42_666_666L, 512))
        }
        val bytes = master.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        master.writeBytes(bytes)

        var threwOnCrc = false
        Npv10FileReader(master).use { reader ->
            try {
                while (reader.readNextFrame() != null) { /* read until the tampered frame trips CRC */ }
            } catch (e: Exception) {
                threwOnCrc = (e.message ?: "").contains("CRC", ignoreCase = true)
            }
        }
        assertTrue("tampered payload must trip the reader CRC guard", threwOnCrc)
        root.deleteRecursively()
    }

    @Test
    fun eightBitSourceIsLabeledYuv420ExpandedNeverNativeP010InWrittenHeader() {
        val config = eightBitConfig()
        val (root, master) = newMaster(config)
        Npv10FileWriter(master, config).close()
        Npv10FileReader(master).use { reader ->
            assertEquals(Npv10PayloadFormat.YUV420_8BIT_EXPANDED, reader.header.payloadFormat)
            assertNotEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
            assertTrue(reader.header.sourceBitDepth <= 8)
        }
        root.deleteRecursively()
    }

    @Test
    fun tenBitP010SourceIsLabeledNativeP010InWrittenHeader() {
        val config = p010Config()
        val (root, master) = newMaster(config)
        Npv10FileWriter(master, config).close()
        Npv10FileReader(master).use { reader ->
            assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
            assertTrue(reader.header.sourceBitDepth >= 10)
        }
        root.deleteRecursively()
    }

    private fun newMaster(config: Npv10SessionConfig): Pair<File, File> {
        val root = Files.createTempDirectory("npv10-integrity").toFile()
        val master = Npv10MasterStore(root).createMasterFile(config)
        return root to master
    }

    private fun p010Config(): Npv10SessionConfig =
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
                "sourceImageFormat" to ImageFormat.YCBCR_P010.toString()
            )
        )

    private fun eightBitConfig(): Npv10SessionConfig =
        Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
            exactSelectedResolution = true
        )

    private fun tenBitFrame(timestamp: Long, firstYSample: Int): RawYuvFrame =
        RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = tenBitPlane(firstYSample, 512, 700, 128),
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

    private fun eightBitFrame(timestamp: Long): RawYuvFrame =
        RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = byteArrayOf(1, 2, 3, 4),
            uBytes = byteArrayOf(128.toByte()),
            vBytes = byteArrayOf(128.toByte()),
            yRowStride = 2,
            yPixelStride = 1,
            uRowStride = 1,
            vRowStride = 1,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = timestamp
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
