package com.neoproc.neoproccamx.recording

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/** Performs bounded top-level atom validation before a process-death MOV is made gallery-visible. */
internal object MovRecoveryInspector {
    data class PendingInspection(
        val recoverable: Boolean,
        val descriptorSizeBytes: Long,
        val reportedSizeBytes: Long
    ) {
        val sizeMetadataStale: Boolean get() = descriptorSizeBytes != reportedSizeBytes
    }

    /**
     * IS_PENDING rows can report zero or the old reservation size until MediaProvider scans them.
     * That column is diagnostic metadata, never permission to discard a file. Validate the opened
     * descriptor instead, and retain on an unstable size or an I/O failure. The caller owns a fresh,
     * read-only descriptor; no source bytes, reservation, or timestamps are changed here.
     */
    fun inspectPending(channel: SeekableByteChannel, reportedSizeBytes: Long): PendingInspection {
        val descriptorSizeBytes = channel.size()
        if (descriptorSizeBytes < 0L) throw IOException("Pending MOV descriptor size is unavailable")
        val recoverable = isRecoverable(channel, descriptorSizeBytes)
        if (channel.size() != descriptorSizeBytes) {
            throw IOException("Pending MOV size changed during validation; retain for a later recovery")
        }
        return PendingInspection(recoverable, descriptorSizeBytes, reportedSizeBytes)
    }

    fun isRecoverable(channel: SeekableByteChannel, fileSize: Long): Boolean {
        if (fileSize < MIN_MOV_BYTES) return false
        var offset = 0L
        var atomCount = 0
        var hasFileType = false
        var hasMovie = false
        var hasMediaData = false
        val header = ByteBuffer.allocate(EXTENDED_HEADER_BYTES.toInt()).order(ByteOrder.BIG_ENDIAN)

        while (offset < fileSize && atomCount++ < MAX_TOP_LEVEL_ATOMS) {
            val remaining = fileSize - offset
            if (remaining < BASIC_HEADER_BYTES) return false
            if (!readAt(channel, offset, header, BASIC_HEADER_BYTES)) return false
            val size32 = header.int.toLong() and UINT32_MASK
            val type = header.int
            val headerBytes: Long
            val atomSize = when (size32) {
                0L -> {
                    headerBytes = BASIC_HEADER_BYTES
                    remaining
                }
                1L -> {
                    if (remaining < EXTENDED_HEADER_BYTES ||
                        !readAt(channel, offset, header, EXTENDED_HEADER_BYTES)
                    ) return false
                    header.position(BASIC_HEADER_BYTES.toInt())
                    headerBytes = EXTENDED_HEADER_BYTES
                    header.long
                }
                else -> {
                    headerBytes = BASIC_HEADER_BYTES
                    size32
                }
            }
            if (atomSize < headerBytes || atomSize > remaining) return false
            when (type) {
                FTYP -> hasFileType = true
                MOOV -> hasMovie = atomSize > headerBytes
                MDAT -> hasMediaData = atomSize > headerBytes
            }
            offset += atomSize
        }
        return offset == fileSize && hasFileType && hasMovie && hasMediaData
    }

    private fun readAt(
        channel: SeekableByteChannel,
        offset: Long,
        buffer: ByteBuffer,
        byteCount: Long
    ): Boolean {
        buffer.clear()
        buffer.limit(byteCount.toInt())
        channel.position(offset)
        var zeroProgressReads = 0
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) return false
            if (count == 0) {
                if (++zeroProgressReads >= MAX_ZERO_PROGRESS_READS) {
                    // A stalled provider is not evidence of corrupt media. Throw so startup
                    // recovery retains the row instead of deleting it or spinning indefinitely.
                    throw IOException("Pending MOV validation made no read progress")
                }
            } else {
                zeroProgressReads = 0
            }
        }
        buffer.flip()
        return true
    }

    private fun fourCc(value: String): Int =
        (value[0].code shl 24) or (value[1].code shl 16) or
            (value[2].code shl 8) or value[3].code

    private const val BASIC_HEADER_BYTES = 8L
    private const val EXTENDED_HEADER_BYTES = 16L
    private const val MIN_MOV_BYTES = 32L
    private const val MAX_TOP_LEVEL_ATOMS = 4_096
    private const val MAX_ZERO_PROGRESS_READS = 3
    private const val UINT32_MASK = 0xFFFF_FFFFL
    private val FTYP = fourCc("ftyp")
    private val MOOV = fourCc("moov")
    private val MDAT = fourCc("mdat")
}
