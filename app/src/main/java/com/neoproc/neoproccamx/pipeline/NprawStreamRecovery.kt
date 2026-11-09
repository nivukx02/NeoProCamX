package com.neoproc.neoproccamx.pipeline

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile

enum class NprawRecoveryStatus {
    CLEAN,
    TRUNCATE_REQUIRED,
    INVALID_EMPTY_STREAM
}

data class NprawRecoveryResult(
    val status: NprawRecoveryStatus,
    val originalBytes: Long,
    val committedBytes: Long,
    val recoveredFrames: Int,
    val failure: String? = null,
    val firstSensorTimestampNs: Long = 0L,
    val lastSensorTimestampNs: Long = 0L
) {
    val shouldTruncate: Boolean
        get() = status == NprawRecoveryStatus.TRUNCATE_REQUIRED && originalBytes > committedBytes
}

/** Finds the last complete, CRC-valid frame in a preallocated or interrupted NPRAW stream. */
object NprawStreamRecovery {
    fun scan(input: InputStream, originalBytes: Long): NprawRecoveryResult {
        val counting = CountingInputStream(input)
        var committedBytes = 0L
        var frames = 0
        var firstTimestampNs = 0L
        var lastTimestampNs = 0L
        var failure: Throwable? = null
        while (true) {
            val encoded = try {
                NprawFrameCodec.readRawFrameBytes(counting)
            } catch (t: Throwable) {
                failure = t
                break
            } ?: break
            try {
                val timestampNs = NprawFrameCodec.validatedEncodedSensorTimestampNs(encoded)
                check(timestampNs > 0L && (frames == 0 || timestampNs > lastTimestampNs)) {
                    "NPRAW recovery encountered a non-monotonic sensor timestamp"
                }
                if (frames == 0) firstTimestampNs = timestampNs
                lastTimestampNs = timestampNs
            } catch (t: Throwable) {
                failure = t
                break
            }
            frames += 1
            committedBytes = counting.bytesRead
        }
        val hasTrailingReservationOrDamage = failure != null && originalBytes > committedBytes
        val status = when {
            hasTrailingReservationOrDamage -> NprawRecoveryStatus.TRUNCATE_REQUIRED
            frames == 0 && originalBytes > 0L -> NprawRecoveryStatus.INVALID_EMPTY_STREAM
            else -> NprawRecoveryStatus.CLEAN
        }
        return NprawRecoveryResult(
            status = status,
            originalBytes = originalBytes,
            committedBytes = committedBytes,
            recoveredFrames = frames,
            failure = failure?.message,
            firstSensorTimestampNs = firstTimestampNs,
            lastSensorTimestampNs = lastTimestampNs
        )
    }

    fun recover(file: File): NprawRecoveryResult {
        val originalBytes = file.length()
        val result = FileInputStream(file).buffered().use { input -> scan(input, originalBytes) }
        if (result.shouldTruncate) {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(result.committedBytes)
                output.fd.sync()
            }
        }
        return result
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int = super.read().also { value ->
            if (value >= 0) bytesRead += 1L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) bytesRead += count.toLong()
            }

        override fun skip(byteCount: Long): Long = super.skip(byteCount).also { skipped ->
            if (skipped > 0L) bytesRead += skipped
        }
    }
}
