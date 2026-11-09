package com.neoproc.neoproccamx.recording

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

enum class Npv10RecoveryStatus {
    CLEAN,
    TRUNCATED_TO_VALID_PREFIX,
    INVALID_HEADER
}

data class Npv10RecoveryResult(
    val status: Npv10RecoveryStatus,
    val originalBytes: Long,
    val recoveredBytes: Long,
    val recoveredFrames: Long,
    val failure: String? = null
)

/** Recovers a preallocated or interrupted NPV10 master to its last CRC-valid frame boundary. */
object Npv10FileRecovery {
    fun looksLikeUntrimmedReservation(fileBytes: Long): Boolean =
        fileBytes in RecordingPreallocationPolicy.MIN_RESERVATION_BYTES..RecordingPreallocationPolicy.HARD_MAX_BYTES &&
            fileBytes % RecordingPreallocationPolicy.BLOCK_BYTES == 0L

    fun recover(file: File): Npv10RecoveryResult {
        val originalBytes = file.length()
        var lastValidBoundary = 0L
        var recoveredFrames = 0L
        var trailingFailure: Throwable? = null
        try {
            Npv10FileReader(file).use { reader ->
                lastValidBoundary = reader.bytePosition
                while (true) {
                    val frame = try {
                        reader.readNextFrame()
                    } catch (t: Throwable) {
                        trailingFailure = t
                        break
                    } ?: break
                    recoveredFrames += 1L
                    lastValidBoundary = reader.bytePosition
                }
            }
        } catch (headerFailure: Throwable) {
            return Npv10RecoveryResult(
                status = Npv10RecoveryStatus.INVALID_HEADER,
                originalBytes = originalBytes,
                recoveredBytes = originalBytes,
                recoveredFrames = 0L,
                failure = headerFailure.message
            )
        }

        val needsTruncate = trailingFailure != null &&
            lastValidBoundary > 0L &&
            originalBytes > lastValidBoundary
        if (needsTruncate) {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.truncate(lastValidBoundary)
                channel.force(true)
            }
        }
        return Npv10RecoveryResult(
            status = if (needsTruncate) {
                Npv10RecoveryStatus.TRUNCATED_TO_VALID_PREFIX
            } else {
                Npv10RecoveryStatus.CLEAN
            },
            originalBytes = originalBytes,
            recoveredBytes = if (needsTruncate) lastValidBoundary else originalBytes,
            recoveredFrames = recoveredFrames,
            failure = trailingFailure?.message
        )
    }
}
