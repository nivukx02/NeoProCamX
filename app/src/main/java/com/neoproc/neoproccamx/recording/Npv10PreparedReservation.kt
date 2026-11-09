package com.neoproc.neoproccamx.recording

import android.system.Os
import java.io.File
import java.io.RandomAccessFile

/** A physically allocated NPV10 extent prepared before the shutter hot path. */
data class Npv10PreparedReservation(
    val file: File,
    val preallocationResult: RecordingPreallocationResult
) {
    fun isUsable(): Boolean =
        file.isFile &&
            preallocationResult.plan.enabled &&
            preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
            preallocationResult.reservedBytes >= RecordingPreallocationPolicy.MIN_RESERVATION_BYTES &&
            file.length() >= preallocationResult.reservedBytes
}

object Npv10PreparedReservationFactory {
    fun create(file: File, availableStorageBytes: Long): Npv10PreparedReservation {
        file.parentFile?.mkdirs()
        val plan = RecordingPreallocationPolicy.processed(availableStorageBytes)
        val result = if (!plan.enabled) {
            RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                failure = plan.reason
            )
        } else {
            try {
                RandomAccessFile(file, "rw").use { target ->
                    target.setLength(0L)
                    Os.posix_fallocate(target.fd, 0L, plan.reservationBytes)
                    target.fd.sync()
                }
                RecordingPreallocationResult(
                    plan = plan,
                    method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                    reservedBytes = plan.reservationBytes
                )
            } catch (failure: Throwable) {
                RecordingPreallocationResult(
                    plan = plan,
                    method = RecordingPreallocationMethod.NONE,
                    reservedBytes = 0L,
                    failure = failure.message
                )
            }
        }
        return Npv10PreparedReservation(file, result).also { reservation ->
            if (!reservation.isUsable()) runCatching { file.delete() }
        }
    }
}
