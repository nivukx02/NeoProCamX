package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile

class MovRecoveryInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsCompleteTopLevelMovStructure() {
        val file = temporaryFolder.newFile("complete.mov")
        file.writeBytes(atom("ftyp", byteArrayOf(0, 0, 0, 0)) + atom("mdat", ByteArray(8)) + atom("moov", ByteArray(8)))

        RandomAccessFile(file, "r").use { input ->
            assertTrue(MovRecoveryInspector.isRecoverable(input.channel, file.length()))
        }
    }

    @Test
    fun rejectsTracklessOrTruncatedPendingOutput() {
        val trackless = temporaryFolder.newFile("trackless.mov")
        trackless.writeBytes(atom("ftyp", byteArrayOf(0, 0, 0, 0)) + atom("mdat", ByteArray(8)))
        RandomAccessFile(trackless, "r").use { input ->
            assertFalse(MovRecoveryInspector.isRecoverable(input.channel, trackless.length()))
        }

        val truncated = temporaryFolder.newFile("truncated.mov")
        truncated.writeBytes(atom("ftyp", byteArrayOf(0, 0, 0, 0)) + atom("moov", ByteArray(8)).dropLast(2))
        RandomAccessFile(truncated, "r").use { input ->
            assertFalse(MovRecoveryInspector.isRecoverable(input.channel, truncated.length()))
        }
    }

    private fun atom(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(8 + payload.size)
                output.write(type.toByteArray(Charsets.US_ASCII))
                output.write(payload)
            }
        }.toByteArray()
}
