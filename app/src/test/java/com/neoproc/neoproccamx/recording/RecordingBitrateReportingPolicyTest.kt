package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBitrateReportingPolicyTest {

    @Test
    fun visibleBitrate_usesEncoderCappedBitrateWhileRecording() {
        val visible = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 1_200_000_000,
            activeOutputBitrate = 160_000_000,
            state = GpuRecordingState.Recording
        )

        assertEquals(160_000_000, visible)
    }

    @Test
    fun visibleBitrate_preservesConfiguredBitrateWhileIdle() {
        val visible = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 1_200_000_000,
            activeOutputBitrate = 160_000_000,
            state = GpuRecordingState.Idle
        )

        assertEquals(1_200_000_000, visible)
    }

    /**
     * Truthfulness order during Recording: prefer the **measured** encoder
     * output bitrate over the requested target. The user sees the actual
     * file growth rate, not the number we asked the encoder for.
     */
    @Test
    fun visibleBitrate_prefersMeasuredOverActiveWhileRecording() {
        val visible = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 1_200_000_000,
            activeOutputBitrate = 160_000_000,
            measuredOutputBitrate = 142_500_000,
            state = GpuRecordingState.Recording
        )

        assertEquals(
            "Measured bitrate must take precedence over the requested target",
            142_500_000, visible
        )
    }

    @Test
    fun visibleBitrate_fallsBackToActiveWhenMeasurementWindowIsWarmingUp() {
        val visible = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 1_200_000_000,
            activeOutputBitrate = 160_000_000,
            measuredOutputBitrate = 0, // <2 buffers dequeued yet
            state = GpuRecordingState.Recording
        )

        assertEquals(160_000_000, visible)
    }

    @Test
    fun visibleBitrate_fallsBackToConfiguredWhenNeitherMeasuredNorActiveAreAvailable() {
        val visible = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 200_000_000,
            activeOutputBitrate = 0,
            measuredOutputBitrate = 0,
            state = GpuRecordingState.Recording
        )

        assertEquals(200_000_000, visible)
    }

    @Test
    fun visibleBitrate_stoppingStatePrefersActiveButHonorsMeasuredFallback() {
        // Stopping state: encoder is no longer accepting input; the active
        // (last-hinted) target is the most representative number while the
        // pipeline drains. Measured is the fallback if no active is set.
        val withActive = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 1_200_000_000,
            activeOutputBitrate = 160_000_000,
            measuredOutputBitrate = 142_500_000,
            state = GpuRecordingState.Stopping
        )
        assertEquals(160_000_000, withActive)

        val noActive = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 1_200_000_000,
            activeOutputBitrate = 0,
            measuredOutputBitrate = 142_500_000,
            state = GpuRecordingState.Stopping
        )
        assertEquals(142_500_000, noActive)
    }

    @Test
    fun visibleBitrate_errorStateAlwaysReportsConfigured() {
        val visible = RecordingBitrateReportingPolicy.resolveVisibleBitrate(
            configuredBitrate = 200_000_000,
            activeOutputBitrate = 160_000_000,
            measuredOutputBitrate = 142_500_000,
            state = GpuRecordingState.Error("oops")
        )
        assertEquals(200_000_000, visible)
    }
}
