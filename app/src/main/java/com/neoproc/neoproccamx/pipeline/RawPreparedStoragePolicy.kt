package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationResult

/** Admission gate for adopting an idle physical reservation on the RAW shutter path. */
internal object RawPreparedStoragePolicy {
    fun isReusable(
        expectedMode: RawCompressionMode,
        preparedMode: RawCompressionMode,
        writerOpen: Boolean,
        preallocation: RecordingPreallocationResult
    ): Boolean =
        expectedMode == preparedMode &&
            writerOpen &&
            preallocation.plan.enabled &&
            preallocation.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
            preallocation.reservedBytes >= RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
}
