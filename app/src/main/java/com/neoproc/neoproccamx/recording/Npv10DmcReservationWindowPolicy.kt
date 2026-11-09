package com.neoproc.neoproccamx.recording

/** Reserves worst-case future packets after the actual committed tail, not past raw-sized frames. */
internal object Npv10DmcReservationWindowPolicy {
    fun requiredEndBytes(
        committedBytes: Long,
        committedFrames: Long,
        frameIndex: Long,
        maximumFrameRecordBytes: Long,
        slotCount: Int
    ): Long {
        require(committedBytes >= 0L && committedFrames >= 0L)
        require(maximumFrameRecordBytes > 0L && slotCount > 0)
        require(frameIndex >= committedFrames) { "DMC reservation cannot admit a retired frame" }
        val outstandingOrdinal = frameIndex - committedFrames
        require(outstandingOrdinal < slotCount.toLong()) {
            "DMC reservation exceeds its bounded in-flight frame window"
        }
        return Math.addExact(
            committedBytes,
            Math.multiplyExact(outstandingOrdinal + 1L, maximumFrameRecordBytes)
        )
    }
}
