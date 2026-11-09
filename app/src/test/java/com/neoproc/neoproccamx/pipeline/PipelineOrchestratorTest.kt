package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineOrchestratorTest {

    @Test
    fun sensorTimestampMapsToPresentationPtsUsingSlidingLatency() {
        val clock = SensorPresentationClock(initialLatencyNs = 5_000_000L)

        assertEquals(105_000_000L, clock.presentationTimeFor(100_000_000L, nowNs = 105_000_000L))
        clock.recordGpuCompletion(sensorTimestampNs = 100_000_000L, completionNs = 108_000_000L)

        val next = clock.presentationTimeFor(133_333_333L, nowNs = 140_000_000L)

        assertTrue(next > 138_333_333L)
        assertTrue(clock.telemetrySignature().contains("sensor-to-display-pts=ewma"))
        assertTrue(clock.telemetrySignature().contains("presentation=monotonic-safe"))
    }

    @Test
    fun sensorPresentationClockRejectsFutureCameraClockBeforeEncoderSurfacePacing() {
        val clock = SensorPresentationClock(initialLatencyNs = 5_000_000L)

        val nowNs = 1_000_000_000L
        val futureSensorTimestampNs = nowNs + 2_000_000_000L
        val pts = clock.presentationTimeFor(futureSensorTimestampNs, nowNs = nowNs)

        assertTrue(pts <= nowNs + 33_333_333L)
    }

    @Test
    fun sensorPresentationClockKeepsPtsStrictlyMonotonicAfterClockFallback() {
        val clock = SensorPresentationClock(initialLatencyNs = 0L)

        val first = clock.presentationTimeFor(0L, nowNs = 2_000_000_000L)
        val second = clock.presentationTimeFor(0L, nowNs = 1_999_000_000L)

        assertEquals(first + 1L, second)
    }

    @Test
    fun dualGainDriftOverOneMicrosecondRequiresOpticalFlowWarp() {
        val aligned = PipelineOrchestrator.resolveDualGainAlignment(
            longExposureTimestampNs = 10_000_000L,
            shortExposureTimestampNs = 10_000_500L
        )
        val drifted = PipelineOrchestrator.resolveDualGainAlignment(
            longExposureTimestampNs = 10_000_000L,
            shortExposureTimestampNs = 10_001_500L
        )

        assertFalse(aligned.requiresOpticalFlowWarp)
        assertTrue(drifted.requiresOpticalFlowWarp)
        assertEquals(1_500L, drifted.driftNs)
        assertTrue(drifted.shaderDirective.contains("warp_reconstruct.comp"))
    }

    @Test
    fun backpressureTuningShedsHeavyStagesBeforeDroppingCorePreview() {
        val stable = PipelineOrchestrator.resolveBackpressureTuning(
            droppedFrames = 0L,
            rejectedFrames = 0L,
            averageQueueLatencyNs = 500_000L
        )
        val overloaded = PipelineOrchestrator.resolveBackpressureTuning(
            droppedFrames = 12L,
            rejectedFrames = 4L,
            averageQueueLatencyNs = 4_500_000L
        )

        assertEquals(5, stable.deconvolutionIterations)
        assertEquals(12, stable.temporalHistoryFrames)
        assertTrue(stable.wavefrontEnabled)
        assertTrue(overloaded.deconvolutionIterations < stable.deconvolutionIterations)
        assertTrue(overloaded.temporalHistoryFrames < stable.temporalHistoryFrames)
        assertTrue(overloaded.previewMustContinue)
        assertTrue(overloaded.qualityDowngradeApplied)
    }

    @Test
    fun strictBackpressureKeepsFullQualityAndFlagsAdmissionFailure() {
        val strict = PipelineOrchestrator.resolveBackpressureTuning(
            droppedFrames = 12L,
            rejectedFrames = 4L,
            averageQueueLatencyNs = 4_500_000L,
            strictNoQualityDowngrade = true
        )

        assertEquals(5, strict.deconvolutionIterations)
        assertEquals(12, strict.temporalHistoryFrames)
        assertTrue(strict.wavefrontEnabled)
        assertFalse(strict.qualityDowngradeApplied)
        assertTrue(strict.strictAdmissionFailure)
        assertEquals("strict-high-end-backpressure-refuse-quality-downgrade", strict.reason)
    }
}
