package com.neoproc.neoproccamx.recording

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

class PendingMovDescriptorRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun zeroDatabaseSizeCannotDiscardCompletePendingMov() = withCompleteMov { channel, bytes ->
        // Reproduces the old decision before exercising the production descriptor-authority path.
        assertFalse(MovRecoveryInspector.isRecoverable(channel, 0L))
        val result = MovRecoveryInspector.inspectPending(channel, 0L)
        assertTrue(result.recoverable)
        assertTrue(result.sizeMetadataStale)
        assertEquals(bytes.size.toLong(), result.descriptorSizeBytes)
    }

    @Test fun staleReservationSizeCannotDiscardCompletePendingMov() = withCompleteMov { channel, bytes ->
        val result = MovRecoveryInspector.inspectPending(channel, 10L * 1024 * 1024 * 1024)
        assertTrue(result.recoverable)
        assertTrue(result.sizeMetadataStale)
        assertEquals(bytes.size.toLong(), result.descriptorSizeBytes)
    }

    @Test fun truncatedDatabaseSizeCannotDiscardCompletePendingMov() = withCompleteMov { channel, _ ->
        assertTrue(MovRecoveryInspector.inspectPending(channel, 32L).recoverable)
    }

    @Test fun unknownDatabaseSizeStillUsesReadableDescriptor() = withCompleteMov { channel, _ ->
        assertTrue(MovRecoveryInspector.inspectPending(channel, -1L).recoverable)
    }

    @Test fun matchingSizeIsReportedWithoutAFalseMismatch() = withCompleteMov { channel, bytes ->
        val result = MovRecoveryInspector.inspectPending(channel, bytes.size.toLong())
        assertTrue(result.recoverable)
        assertFalse(result.sizeMetadataStale)
    }

    @Test fun inspectionPreservesEveryByte() = withCompleteMov { channel, bytes ->
        MovRecoveryInspector.inspectPending(channel, 0L)
        channel.position(0L)
        val after = ByteBuffer.allocate(bytes.size)
        while (after.hasRemaining()) check(channel.read(after) > 0)
        assertArrayEquals(bytes, after.array())
    }

    @Test fun actuallyTruncatedDescriptorIsNeverPublished() {
        val file = temporaryFolder.newFile()
        file.writeBytes(completeMov().dropLast(2).toByteArray())
        RandomAccessFile(file, "r").use {
            assertFalse(MovRecoveryInspector.inspectPending(it.channel, 0L).recoverable)
        }
    }

    @Test fun emptyDescriptorIsNeverPublishedEvenWithNonzeroMetadata() {
        RandomAccessFile(temporaryFolder.newFile(), "r").use {
            assertFalse(MovRecoveryInspector.inspectPending(it.channel, 10_000L).recoverable)
        }
    }

    @Test fun growingDescriptorIsInconclusiveNotDeletionEvidence() = withCompleteMov { channel, _ ->
        val wrapped = object : SeekableByteChannel by channel {
            var sizeCalls = 0
            override fun size(): Long = channel.size() + if (sizeCalls++ == 0) 0 else 1
        }
        assertThrows(IOException::class.java) { MovRecoveryInspector.inspectPending(wrapped, 0L) }
    }

    @Test fun failedStatIsInconclusiveNotDeletionEvidence() = withCompleteMov { channel, _ ->
        val wrapped = object : SeekableByteChannel by channel {
            override fun size(): Long = throw IOException("injected descriptor stat failure")
        }
        assertThrows(IOException::class.java) { MovRecoveryInspector.inspectPending(wrapped, 0L) }
    }

    @Test fun nonProgressingProviderIsBoundedAndRetained() = withCompleteMov { channel, _ ->
        var reads = 0
        val wrapped = object : SeekableByteChannel by channel {
            override fun read(dst: ByteBuffer): Int { reads++; return 0 }
        }
        assertThrows(IOException::class.java) { MovRecoveryInspector.inspectPending(wrapped, 0L) }
        assertEquals(3, reads)
    }

    @Test fun briefZeroReadFollowedByProgressStillRecovers() = withCompleteMov { channel, _ ->
        var initialRead = true
        val wrapped = object : SeekableByteChannel by channel {
            override fun read(dst: ByteBuffer): Int {
                if (initialRead) { initialRead = false; return 0 }
                return channel.read(dst)
            }
        }
        assertTrue(MovRecoveryInspector.inspectPending(wrapped, 0L).recoverable)
    }

    private fun withCompleteMov(test: (SeekableByteChannel, ByteArray) -> Unit) {
        val bytes = completeMov()
        val file = temporaryFolder.newFile()
        file.writeBytes(bytes)
        RandomAccessFile(file, "r").use { test(it.channel, bytes) }
    }

    private fun completeMov(): ByteArray =
        atom("ftyp", ByteArray(4)) + atom("mdat", ByteArray(12) { (it * 7 + 1).toByte() }) +
            atom("moov", ByteArray(8))

    private fun atom(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(8 + payload.size)
                output.write(type.toByteArray(Charsets.US_ASCII))
                output.write(payload)
            }
        }.toByteArray()
}
