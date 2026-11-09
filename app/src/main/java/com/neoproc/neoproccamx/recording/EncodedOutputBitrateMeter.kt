package com.neoproc.neoproccamx.recording

/**
 * Encoded-output bitrate meter with separate live-window and whole-session
 * snapshots, computed from buffers dequeued from `MediaCodec`.
 *
 * Why this exists
 * ─────────────────────────────────────────────────────────────────────────
 *  - `MediaFormat.KEY_BIT_RATE` and `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE`
 *    are *requests* to the encoder. They are not contracts. The encoder may
 *    deliver less (when the scene compresses well, when running near
 *    thermal/HW caps, or in a CBR/CQ regime that decouples target from
 *    instantaneous rate) or more (during keyframes or RC overshoot).
 *  - The truthful number to surface to the user / overlays / telemetry is
 *    the rate the encoder is actually emitting on the wire, not the number
 *    we asked for.
 *
 * The live snapshot integrates over a fixed presentation-time window for
 * responsive UI telemetry. The session snapshot retains all accepted encoded
 * bytes and the complete PTS span so final quality evidence cannot be biased
 * by a high-rate trailing window.
 *
 * All mutable state is guarded by one lock. Each rate method returns an atomic
 * snapshot relative to record/reset operations.
 */
internal class EncodedOutputBitrateMeter(
    /** Width of the sliding integration window, in encoded presentation microseconds. */
    private val windowDurationUs: Long = DEFAULT_WINDOW_US
) {

    companion object {
        /**
         * Two seconds. Long enough to absorb a typical GOP (a single
         * keyframe is ~5–15× the size of a P-frame and would otherwise
         * dominate a sub-second window) yet short enough to react to real
         * scene-change and bitrate-hint transitions within ~2 s.
         */
        const val DEFAULT_WINDOW_US: Long = 2_000_000L
    }

    private data class Sample(val timestampUs: Long, val sizeBytes: Int)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private var totalBytesInWindow: Long = 0L
    private var lastTimestampUs: Long = Long.MIN_VALUE
    private var sessionFirstTimestampUs: Long = Long.MIN_VALUE
    private var sessionLastTimestampUs: Long = Long.MIN_VALUE
    private var sessionTotalBytes: Long = 0L
    private var sessionSampleCount: Long = 0L

    /**
     * Records a single encoded buffer.
     *
     *  - Samples with non-positive `sizeBytes` are ignored (codec config
     *    buffers and the EOS sentinel are zero-sized).
     *  - Out-of-order PTS is tolerated by treating the new sample as the
     *    next monotonic step (`max(prev + 1, ts)`); this avoids corrupting
     *    the window if the encoder ever rewinds, which would otherwise
     *    yield a negative span and a meaningless bitrate.
     */
    fun record(timestampUs: Long, sizeBytes: Int) {
        if (sizeBytes <= 0) return
        synchronized(lock) {
            val monotonicTs = nextMonotonicTimestamp(timestampUs)
            samples.addLast(Sample(monotonicTs, sizeBytes))
            totalBytesInWindow = saturatingAdd(totalBytesInWindow, sizeBytes.toLong())
            lastTimestampUs = monotonicTs
            if (sessionFirstTimestampUs == Long.MIN_VALUE) {
                sessionFirstTimestampUs = monotonicTs
            }
            sessionLastTimestampUs = monotonicTs
            sessionTotalBytes = saturatingAdd(sessionTotalBytes, sizeBytes.toLong())
            sessionSampleCount = saturatingAdd(sessionSampleCount, 1L)
            evictOlderThan(saturatingSubtract(monotonicTs, windowDurationUs))
        }
    }

    /**
     * Returns the integrated bitrate in bits/second for the buffered window.
     *
     * Returns `0` when there is not yet enough signal to compute a stable
     * rate (fewer than two samples, or a degenerate zero-span window). The
     * caller is expected to fall back to the requested-bitrate value in
     * that case.
     */
    fun measuredBitrateBitsPerSecond(): Int = synchronized(lock) {
        if (samples.size < 2) return@synchronized 0
        calculateBitrateBitsPerSecond(
            totalBytes = totalBytesInWindow,
            firstTimestampUs = samples.first().timestampUs,
            lastTimestampUs = samples.last().timestampUs
        )
    }

    /**
     * Returns the bitrate integrated across every accepted encoded sample
     * since construction or the most recent [reset].
     */
    fun measuredSessionBitrateBitsPerSecond(): Int = synchronized(lock) {
        if (sessionSampleCount < 2L) return@synchronized 0
        calculateBitrateBitsPerSecond(
            totalBytes = sessionTotalBytes,
            firstTimestampUs = sessionFirstTimestampUs,
            lastTimestampUs = sessionLastTimestampUs
        )
    }

    /** Number of buffered samples currently within the window. */
    fun sampleCount(): Int = synchronized(lock) { samples.size }

    /**
     * Recovery needs the duration of all N encoded samples, not just their N-1 PTS intervals.
     * Including the last frame prevents a two-second window from overstating throughput by ~2%
     * at 25 FPS and hiding a real bitrate deficit. Existing session evidence is left unchanged.
     */
    fun recoveryWindow(fps: Int): RecordingBitrateRecoveryController.OutputWindow = synchronized(lock) {
        require(fps in 1..1_000) { "Invalid encoded frame rate: $fps" }
        if (samples.size < 2) return@synchronized RecordingBitrateRecoveryController.OutputWindow.EMPTY
        val spanUs = timestampSpanUs(samples.first().timestampUs, samples.last().timestampUs)
        if (spanUs <= 0L) return@synchronized RecordingBitrateRecoveryController.OutputWindow.EMPTY
        val durationUs = saturatingAdd(spanUs, 1_000_000L / fps)
        val bps = (totalBytesInWindow.toDouble() * 8_000_000.0 / durationUs.toDouble())
            .coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
        RecordingBitrateRecoveryController.OutputWindow(
            sampleCount = samples.size,
            durationUs = durationUs,
            lastPresentationTimeUs = samples.last().timestampUs,
            bitrateBps = bps
        )
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            totalBytesInWindow = 0L
            lastTimestampUs = Long.MIN_VALUE
            sessionFirstTimestampUs = Long.MIN_VALUE
            sessionLastTimestampUs = Long.MIN_VALUE
            sessionTotalBytes = 0L
            sessionSampleCount = 0L
        }
    }

    private fun evictOlderThan(cutoffUs: Long) {
        var recalculateWindowBytes = false
        while (samples.isNotEmpty() && samples.first().timestampUs < cutoffUs) {
            val removedBytes = samples.removeFirst().sizeBytes.toLong()
            if (totalBytesInWindow == Long.MAX_VALUE) {
                recalculateWindowBytes = true
            } else {
                totalBytesInWindow = (totalBytesInWindow - removedBytes).coerceAtLeast(0L)
            }
        }
        if (recalculateWindowBytes) {
            totalBytesInWindow = samples.fold(0L) { total, sample ->
                saturatingAdd(total, sample.sizeBytes.toLong())
            }
        }
        if (samples.isEmpty()) totalBytesInWindow = 0L
    }

    private fun nextMonotonicTimestamp(timestampUs: Long): Long =
        when (lastTimestampUs) {
            Long.MIN_VALUE -> timestampUs
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> maxOf(lastTimestampUs + 1L, timestampUs)
        }

    private fun calculateBitrateBitsPerSecond(
        totalBytes: Long,
        firstTimestampUs: Long,
        lastTimestampUs: Long
    ): Int {
        val spanUs = timestampSpanUs(firstTimestampUs, lastTimestampUs)
        if (totalBytes <= 0L || spanUs <= 0L) return 0
        val bitsPerSecond = totalBytes.toDouble() * 8_000_000.0 / spanUs.toDouble()
        if (!bitsPerSecond.isFinite() || bitsPerSecond >= Int.MAX_VALUE.toDouble()) {
            return Int.MAX_VALUE
        }
        return bitsPerSecond.toLong().coerceAtLeast(0L).toInt()
    }

    private fun timestampSpanUs(firstTimestampUs: Long, lastTimestampUs: Long): Long {
        if (lastTimestampUs <= firstTimestampUs) return 0L
        return if (
            firstTimestampUs < 0L &&
            lastTimestampUs > Long.MAX_VALUE + firstTimestampUs
        ) {
            Long.MAX_VALUE
        } else {
            lastTimestampUs - firstTimestampUs
        }
    }

    private fun saturatingAdd(current: Long, increment: Long): Long =
        if (increment > 0L && current > Long.MAX_VALUE - increment) {
            Long.MAX_VALUE
        } else {
            current + increment
        }

    private fun saturatingSubtract(value: Long, decrement: Long): Long =
        if (decrement > 0L && value < Long.MIN_VALUE + decrement) {
            Long.MIN_VALUE
        } else {
            value - decrement
        }
}
