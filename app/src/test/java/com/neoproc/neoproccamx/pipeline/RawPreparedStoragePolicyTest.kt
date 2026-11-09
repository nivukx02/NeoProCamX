package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreparedStoragePolicyTest {
    private val plan = RecordingPreallocationPolicy.Plan(
        enabled = true,
        requestedBytes = 10L * 1024L * 1024L * 1024L,
        reservationBytes = 10L * 1024L * 1024L * 1024L,
        availableBytes = 100L * 1024L * 1024L * 1024L,
        safetyReserveBytes = 20L * 1024L * 1024L * 1024L,
        reason = "test"
    )
    private val physical = RecordingPreallocationResult(
        plan = plan,
        method = RecordingPreallocationMethod.POSIX_FALLOCATE,
        reservedBytes = plan.reservationBytes
    )

    @Test
    fun `matching open physical reservation is reusable`() {
        assertTrue(
            RawPreparedStoragePolicy.isReusable(
                expectedMode = RawCompressionMode.LOSSLESS_REALTIME,
                preparedMode = RawCompressionMode.LOSSLESS_REALTIME,
                writerOpen = true,
                preallocation = physical
            )
        )
    }

    @Test
    fun `mode mismatch closed writer and sparse reservation are rejected`() {
        assertFalse(
            RawPreparedStoragePolicy.isReusable(
                RawCompressionMode.LOSSLESS_REALTIME,
                RawCompressionMode.HIGH_COMPRESSION,
                true,
                physical
            )
        )
        assertFalse(
            RawPreparedStoragePolicy.isReusable(
                RawCompressionMode.LOSSLESS_REALTIME,
                RawCompressionMode.LOSSLESS_REALTIME,
                false,
                physical
            )
        )
        assertFalse(
            RawPreparedStoragePolicy.isReusable(
                RawCompressionMode.LOSSLESS_REALTIME,
                RawCompressionMode.LOSSLESS_REALTIME,
                true,
                physical.copy(method = RecordingPreallocationMethod.NONE, reservedBytes = 0L)
            )
        )
    }
}
