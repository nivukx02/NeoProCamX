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
        assertEquals(committed, output.length())
        FileInputStream(output).use { input ->
            assertNotNull(NprawFrameCodec.readFrame(input))
            assertNotNull(NprawFrameCodec.readFrame(input))
            assertNull(NprawFrameCodec.readFrame(input))
        }
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
