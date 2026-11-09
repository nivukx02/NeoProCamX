package com.neoproc.neoproccamx.recording

internal object RecordingVideoCadencePolicy {
    fun resolvePresentationTimeUs(
        candidateOutputPtsUs: Long,
        previousOutputPtsUs: Long,
        frameRate: Int,
        constantCadence: Boolean
    ): Long {
        val frameStepUs = (1_000_000L / frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
        if (previousOutputPtsUs < 0L) return candidateOutputPtsUs.coerceAtLeast(0L)
        val nextCadencePtsUs = previousOutputPtsUs + frameStepUs
        if (constantCadence) {
            return nextCadencePtsUs
        }
        // Carry the REAL (sensor-derived) timestamp forward only when it represents an actual stall
        // beyond nominal cadence. Tiny producer jitter must still advance by one frame step; otherwise
        // MOV/MP4 timescale rounding can produce non-monotonic DTS in external players.
        return if (candidateOutputPtsUs > nextCadencePtsUs) {
            candidateOutputPtsUs
        } else {
            nextCadencePtsUs
        }
    }
}
