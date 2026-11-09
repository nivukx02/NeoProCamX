package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class Npv10FileRecoveryTest {
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
