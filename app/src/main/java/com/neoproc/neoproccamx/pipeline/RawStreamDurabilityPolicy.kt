package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationResult

internal data class RawStreamDurabilityDecision(
    val publicationSafe: Boolean,
    val reason: String
)

/** Fail-closed publication gate for the final NPRAW stream. */
internal object RawStreamDurabilityPolicy {
    fun evaluate(
        writerPresent: Boolean,
        physicalReservationRequired: Boolean,
        preallocation: RecordingPreallocationResult?,
        persistedFrames: Int,
        bytesWritten: Long,
        writeOps: Long,
        droppedWrites: Long,
        applicationDroppedFrames: Int,
        queuedWrites: Int,
        activeWrites: Int
    ): RawStreamDurabilityDecision {
        val failure = when {
            !writerPresent || preallocation == null -> "NPRAW writer was unavailable at publication"
            physicalReservationRequired &&
                preallocation.method != RecordingPreallocationMethod.POSIX_FALLOCATE ->
                "NPRAW physical reservation was not proven"
            persistedFrames <= 0 -> "NPRAW contains no persisted frames"
            bytesWritten <= 0L -> "NPRAW contains no committed payload bytes"
            writeOps != persistedFrames.toLong() ->
                "NPRAW frame/write mismatch frames=$persistedFrames writes=$writeOps"
            droppedWrites != 0L -> "NPRAW storage writer reported $droppedWrites failed writes"
            applicationDroppedFrames != 0 ->
                "NPRAW capture reported $applicationDroppedFrames dropped frames"
            queuedWrites != 0 || activeWrites != 0 ->
                "NPRAW writer ownership remained queued=$queuedWrites active=$activeWrites"
            !preallocation.synced ->
                "NPRAW committed length/fsync was not proven: ${preallocation.failure.orEmpty()}"
            preallocation.committedBytes != bytesWritten ->
                "NPRAW committed length mismatch committed=${preallocation.committedBytes} written=$bytesWritten"
            else -> null
        }
        return RawStreamDurabilityDecision(
            publicationSafe = failure == null,
            reason = failure ?: "NPRAW committed length and fsync proven"
        )
    }
}
