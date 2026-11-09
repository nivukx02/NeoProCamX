package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RawCompressionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files

class NprawStreamRecoveryTest {
    @Test
    fun zeroReservationTailIsRemovedAtLastCrcValidFrame() {
        val output = Files.createTempFile("npraw-recovery", ".npraw").toFile()
        val first = encodedFrame(1L)
        val second = encodedFrame(2L)
        output.outputStream().use { stream ->
            stream.write(first)
            stream.write(second)
        }
        val committed = output.length()
        RandomAccessFile(output, "rw").use { file ->
            file.setLength(committed + 1024L * 1024L)
        }

        val result = NprawStreamRecovery.recover(output)

        assertEquals(NprawRecoveryStatus.TRUNCATE_REQUIRED, result.status)
        assertEquals(2, result.recoveredFrames)
        assertEquals(1L, result.firstSensorTimestampNs)
        assertEquals(2L, result.lastSensorTimestampNs)
        assertEquals(committed, output.length())
        FileInputStream(output).use { input ->
            assertNotNull(NprawFrameCodec.readFrame(input))
            assertNotNull(NprawFrameCodec.readFrame(input))
            assertNull(NprawFrameCodec.readFrame(input))
        }
    }

    @Test
    fun recoveryKeepsOnlyTheStrictlyMonotonicSourcePrefix() {
        val bytes = encodedFrame(40_000_000L) + encodedFrame(80_000_000L) + encodedFrame(60_000_000L)
        val result = bytes.inputStream().use { NprawStreamRecovery.scan(it, bytes.size.toLong()) }
        assertEquals(NprawRecoveryStatus.TRUNCATE_REQUIRED, result.status)
        assertEquals(2, result.recoveredFrames)
        assertEquals(40_000_000L, result.firstSensorTimestampNs)
        assertEquals(80_000_000L, result.lastSensorTimestampNs)
        assertNotNull(result.failure)
    }

    @Test
    fun recoveryDoesNotExtendTheTimelineWithACorruptFinalPacket() {
        val corrupt = encodedFrame(120_000_000L).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val bytes = encodedFrame(40_000_000L) + encodedFrame(80_000_000L) + corrupt
        val result = bytes.inputStream().use { NprawStreamRecovery.scan(it, bytes.size.toLong()) }
        assertEquals(2, result.recoveredFrames)
        assertEquals(80_000_000L, result.lastSensorTimestampNs)
        assertNotNull(result.failure)
    }

    private fun encodedFrame(timestamp: Long): ByteArray = NprawFrameCodec.encodeFrame(
        mode = RawCompressionMode.HIGH_COMPRESSION,
        rawSamples = intArrayOf(64, 128, 256, 512),
        width = 2,
        height = 2,
        rowStride = 4,
        pixelStride = 2,
        timestamp = timestamp,
        originalSize = 8,
        sensorWhiteLevel = 4095,
        preserveNativeDepth = false
    ).bytes
}
