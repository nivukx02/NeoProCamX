package com.neoproc.neoproccamx.recording

/**
 * Resolves the bitrate value that should be surfaced to the user / overlays
 * / telemetry. Order of truthfulness, most truthful first:
 *
 *  1. `measuredOutputBitrate` — the integrated encoder-output rate observed
 *     over a sliding window of dequeued buffers (see
 *     [EncodedOutputBitrateMeter]). This is the rate the file is *actually*
 *     gaining bytes at, regardless of what the encoder was asked to do.
 *  2. `activeOutputBitrate` — the most recent rate the encoder was *asked*
 *     to produce, after runtime hint adjustments. Honest fallback while the
 *     measurement window is still warming up (first ~2 s of recording) or
 *     when only a single output buffer has been seen so far.
 *  3. `configuredBitrate` — the originally configured target. Used in
 *     non-recording states where neither of the above is meaningful.
 */
internal object RecordingBitrateReportingPolicy {
    fun resolveVisibleBitrate(
        configuredBitrate: Int,
        activeOutputBitrate: Int,
        measuredOutputBitrate: Int = 0,
        state: GpuRecordingState
    ): Int {
        val safeConfigured = configuredBitrate.coerceAtLeast(1)
        val safeActive = activeOutputBitrate.coerceAtLeast(0)
        val safeMeasured = measuredOutputBitrate.coerceAtLeast(0)
        return when (state) {
            GpuRecordingState.Recording -> when {
                safeMeasured > 0 -> safeMeasured
                safeActive > 0 -> safeActive
                else -> safeConfigured
            }
            GpuRecordingState.Ready,
            GpuRecordingState.Starting,
            GpuRecordingState.Paused,
            GpuRecordingState.Stopping -> when {
                safeActive > 0 -> safeActive
                safeMeasured > 0 -> safeMeasured
                else -> safeConfigured
            }
            GpuRecordingState.Idle,
            GpuRecordingState.Finalizing,
            is GpuRecordingState.Error -> safeConfigured
        }
    }
}
