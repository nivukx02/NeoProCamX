package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class Npv10FileRecoveryTest {
    @Test
    fun reservationSignatureIncludesTenGibAndRollingExtentsBeyondInitialExtent() {
        val profile = fullSensorReservationProfile()
        val rollingExtent = profile.initialCoverageBytes + profile.rollingExtensionBytes * 3L

        assertEquals(RecordingPreallocationPolicy.HARD_MAX_BYTES, profile.initialCoverageBytes)
        assertTrue(rollingExtent > RecordingPreallocationPolicy.HARD_MAX_BYTES)
        assertTrue(Npv10FileRecovery.looksLikeUntrimmedReservation(profile.initialCoverageBytes))
        assertTrue(Npv10FileRecovery.looksLikeUntrimmedReservation(rollingExtent))
        assertFalse(
            Npv10FileRecovery.looksLikeUntrimmedReservation(
                RecordingPreallocationPolicy.MIN_RESERVATION_BYTES - 1L
            )
        )
        assertFalse(Npv10FileRecovery.looksLikeUntrimmedReservation(rollingExtent + 1L))
    }

    @Test
    fun boundedReservationTailIsTruncatedToLastCrcValidFrame() {
        val output = Files.createTempFile("npv10-large-sparse-recovery", ".npv10").toFile()
        val config = testConfig(startedAtEpochMs = 3L)
        Npv10FileWriter(output, config, availableStorageBytes = 0L).use { writer ->
            writer.writeFrame(frame(30_000L))
        }
        val committedLength = output.length()
        // Keep filesystem mutation bounded. The pure signature test above covers >10 GiB extents;
        // recovery boundary behavior is identical for the minimum block-aligned reservation.
        val sparseReservationBytes = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
        RandomAccessFile(output, "rw").use { file -> file.setLength(sparseReservationBytes) }

        val result = Npv10FileRecovery.recover(output)

        assertEquals(Npv10RecoveryStatus.TRUNCATED_TO_VALID_PREFIX, result.status)
        assertEquals(sparseReservationBytes, result.originalBytes)
        assertEquals(committedLength, result.recoveredBytes)
        assertEquals(committedLength, output.length())
        assertEquals(1L, result.recoveredFrames)
    }

    @Test
    fun preallocatedTailIsTruncatedToLastCrcValidFrame() {
        val output = Files.createTempFile("npv10-recovery", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "SDR",
            startedAtEpochMs = 1L
        )
        Npv10FileWriter(output, config, availableStorageBytes = 0L).use { writer ->
            writer.writeFrame(frame(10_000L))
        }
        val committedLength = output.length()
        RandomAccessFile(output, "rw").use { file ->
            file.setLength(committedLength + 1024L * 1024L)
        }

        val result = Npv10FileRecovery.recover(output)

        assertEquals(Npv10RecoveryStatus.TRUNCATED_TO_VALID_PREFIX, result.status)
        assertEquals(1L, result.recoveredFrames)
        assertEquals(committedLength, output.length())
        Npv10FileReader(output).use { reader ->
            assertNotNull(reader.readNextFrame())
            assertNull(reader.readNextFrame())
        }
    }

    @Test
    fun cleanMasterIsLeftByteIdentical() {
        val output = Files.createTempFile("npv10-clean", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "SDR",
            startedAtEpochMs = 2L
        )
        Npv10FileWriter(output, config, availableStorageBytes = 0L).use { writer ->
            writer.writeFrame(frame(20_000L))
        }
        val before = output.readBytes()

        val result = Npv10FileRecovery.recover(output)

        assertEquals(Npv10RecoveryStatus.CLEAN, result.status)
        assertEquals(1L, result.recoveredFrames)
        assertEquals(before.toList(), output.readBytes().toList())
    }

    @Test
    fun finalizedBlockAlignedMasterIsCleanAndLeftByteIdentical() {
        val output = Files.createTempFile("npv10-clean-aligned", ".npv10").toFile()
        val baseConfig = testConfig(startedAtEpochMs = 4L).copy(
            featureMetadata = mapOf("test.padding" to "")
        )
        Npv10FileWriter(output, baseConfig, availableStorageBytes = 0L).use { writer ->
            writer.writeFrame(frame(40_000L))
        }
        val paddingBytes = (
            RecordingPreallocationPolicy.BLOCK_BYTES -
                output.length() % RecordingPreallocationPolicy.BLOCK_BYTES
            ) % RecordingPreallocationPolicy.BLOCK_BYTES
        val alignedConfig = baseConfig.copy(
            featureMetadata = mapOf("test.padding" to "x".repeat(paddingBytes.toInt()))
        )
        Npv10FileWriter(output, alignedConfig, availableStorageBytes = 0L).use { writer ->
            writer.writeFrame(frame(40_000L))
        }
        assertEquals(0L, output.length() % RecordingPreallocationPolicy.BLOCK_BYTES)
        val before = output.readBytes()

        val result = Npv10FileRecovery.recover(output)

        assertEquals(Npv10RecoveryStatus.CLEAN, result.status)
        assertEquals(1L, result.recoveredFrames)
        assertEquals(before.toList(), output.readBytes().toList())
    }

    private fun fullSensorReservationProfile(): Npv10PhysicalReservationProfile =
        Npv10PhysicalReservationPolicy.profile(
            width = 4096,
            height = 3072,
            fps = 25,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            packetCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
        )

    private fun testConfig(startedAtEpochMs: Long): Npv10SessionConfig = Npv10SessionConfig(
        width = 2,
        height = 2,
        fps = 24,
        mode = "PRO",
        colorProfile = "SDR",
        startedAtEpochMs = startedAtEpochMs
    )

    private fun frame(timestamp: Long): RawYuvFrame = RawYuvFrame(
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
}
