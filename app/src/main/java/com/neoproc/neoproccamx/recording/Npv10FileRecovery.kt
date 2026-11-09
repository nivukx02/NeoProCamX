package com.neoproc.neoproccamx.recording

import java.io.File
import java.nio.ByteBuffer
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
    /**
     * Physical NPV10 reservations are profile-sized and may grow for the complete recording. They
     * are therefore intentionally not capped by the generic 10 GiB RAW reservation policy.
     */
    fun looksLikeUntrimmedReservation(fileBytes: Long): Boolean =
        fileBytes >= RecordingPreallocationPolicy.MIN_RESERVATION_BYTES &&
            fileBytes % RecordingPreallocationPolicy.BLOCK_BYTES == 0L

    fun recover(file: File): Npv10RecoveryResult {
        val originalBytes = file.length()
        var lastValidBoundary = 0L
        var recoveredFrames = 0L
        var trailingFailure: Throwable? = null
        val reader = try {
            Npv10FileReader(file)
        } catch (headerFailure: IllegalArgumentException) {
            return invalidHeader(originalBytes, headerFailure)
        }
        reader.use {
            lastValidBoundary = reader.bytePosition
            val storedPayloadBytes = try {
                Npv10ContainerPolicy.estimatedStoredBytesPerFrame(
                    width = reader.header.width,
                    height = reader.header.height,
                    payloadFormat = reader.header.payloadFormat,
                    packetCodec = reader.header.packetCodec
                ).also { bytes ->
                    require(bytes in 1L..Int.MAX_VALUE.toLong()) {
                        "NPV10 recovery packet size is invalid: $bytes"
                    }
                }
            } catch (headerFailure: IllegalArgumentException) {
                return invalidHeader(originalBytes, headerFailure)
            }
            // Reuse one direct packet buffer. Recovery validates the stored CRC without decoding
            // each frame or allocating two frame-sized heap arrays on every iteration.
            val storedPacket = ByteBuffer.allocateDirect(storedPayloadBytes.toInt())
            while (true) {
                val hasFrame = try {
                    reader.readNextStoredFrameInto(storedPacket) != null
                } catch (corruption: IllegalArgumentException) {
                    trailingFailure = corruption
                    false
                }
                if (!hasFrame) break
                recoveredFrames += 1L
                lastValidBoundary = reader.bytePosition
            }
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

    private fun invalidHeader(originalBytes: Long, failure: IllegalArgumentException) =
        Npv10RecoveryResult(
            status = Npv10RecoveryStatus.INVALID_HEADER,
            originalBytes = originalBytes,
            recoveredBytes = originalBytes,
            recoveredFrames = 0L,
            failure = failure.message
        )
}
