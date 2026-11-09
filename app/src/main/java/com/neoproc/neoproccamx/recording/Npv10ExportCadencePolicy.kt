package com.neoproc.neoproccamx.recording

/**
 * NPV10 is a constant-frame-rate master: its header FPS is the recording timeline authority.
 * Camera sensor timestamps are retained in the master for diagnostics, but must not leak a vendor
 * variable AE cadence into the derived MOV and contradict the selected/header frame rate.
 */
internal object Npv10ExportCadencePolicy {
    private const val MICROS_PER_SECOND = 1_000_000L

    /**
     * Nominal duration of the first CFR sample. Fractional-microsecond frame periods must not be
     * multiplied by callers because doing so accumulates truncation drift; use [presentationTimeUs]
     * or [endPresentationTimeUs] for absolute timeline positions.
     */
    fun frameDurationUs(fps: Int): Long = presentationTimeUs(frameIndex = 1L, fps = fps)

    /**
     * `floor(frameIndex * 1_000_000 / fps)` without overflowing the intermediate multiplication.
     * Splitting the index into whole seconds and the residual frame numerator preserves exact CFR
     * boundaries for rates such as 24, 30, and 60 fps instead of accumulating a floored duration.
     */
    fun presentationTimeUs(frameIndex: Long, fps: Int): Long {
        require(frameIndex >= 0L) { "Frame index must be non-negative: $frameIndex" }
        require(fps > 0) { "Frame rate must be positive: $fps" }
        val fpsLong = fps.toLong()
        val wholeSecondsUs = Math.multiplyExact(frameIndex / fpsLong, MICROS_PER_SECOND)
        val residualUs = Math.multiplyExact(frameIndex % fpsLong, MICROS_PER_SECOND) / fpsLong
        return Math.addExact(wholeSecondsUs, residualUs)
    }

    /** Exact exclusive end of a CFR track containing [frameCount] samples. */
    fun endPresentationTimeUs(frameCount: Long, fps: Int): Long =
        presentationTimeUs(frameIndex = frameCount, fps = fps)
}

/** One source-frame authority shared by every NPV10 conversion lane. */
internal object Npv10ExportFrameCountPolicy {
    fun requireExactMatch(
        expectedFrameCount: Int,
        sourceFrameCount: Long,
        submittedFrameCount: Long,
        finalizedFrameCount: Long
    ) {
        require(expectedFrameCount > 0) {
            "Expected NPV10 frame count must be positive: $expectedFrameCount"
        }
        val expected = expectedFrameCount.toLong()
        check(sourceFrameCount == expected) {
            "NPV10 source frame mismatch expected=$expected source=$sourceFrameCount"
        }
        check(submittedFrameCount == expected) {
            "NPV10 submitted frame mismatch expected=$expected submitted=$submittedFrameCount"
        }
        check(finalizedFrameCount == expected) {
            "NPV10 finalized frame mismatch expected=$expected finalized=$finalizedFrameCount"
        }
    }
}
