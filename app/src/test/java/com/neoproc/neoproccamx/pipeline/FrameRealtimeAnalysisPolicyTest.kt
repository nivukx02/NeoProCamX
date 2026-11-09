package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRealtimeAnalysisPolicyTest {

    @Test
    fun recording_usesLightLiveAnalysisSoEncoderKeepsFrameBudget() {
        val plan = FrameRealtimeAnalysisPolicy.resolve(
            previewFps = 60f,
            recording = true,
            adaptiveStrideMultiplier = 1L
        )

        assertEquals(10L, plan.aiFrameStride)
        assertEquals(480, plan.aiProxyLongEdge)
        assertTrue(plan.shouldAnalyze(1L))
        assertFalse(plan.shouldAnalyze(3L))
        assertTrue(plan.shouldAnalyze(10L))
    }

    @Test
    fun adaptiveLoad_expandsRecordingCadenceOnTheLiveOnlyAnalysisPath() {
        val plan = FrameRealtimeAnalysisPolicy.resolve(
            previewFps = 30f,
            recording = true,
            adaptiveStrideMultiplier = 3L
        )

        assertEquals(30L, plan.aiFrameStride)
        assertEquals(480, plan.aiProxyLongEdge)
    }

    @Test
    fun strictRecordingLowFps_usesRecoveryProxyAndCadence() {
        val plan = FrameRealtimeAnalysisPolicy.resolve(
            previewFps = 18f,
            recording = true,
            adaptiveStrideMultiplier = 2L
        )

        assertEquals(28L, plan.aiFrameStride)
        assertEquals(480, plan.aiProxyLongEdge)
    }

    @Test
    fun nonStrictRecording_keepsAnalysisFedWithoutStarvingTheEncoder() {
        // CP90: when strict high-end mode is off the legacy recording branch used a 60-frame
        // stride and a 256px proxy — at 60fps that is one analysis per second on a postage-stamp
        // frame, which starves AE/AWB/segmentation during recording. Cadence is now bounded to a
        // ~5/sec floor with a usable proxy, while adaptiveStrideMultiplier remains the load lever.
        val previous = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            val plan = FrameRealtimeAnalysisPolicy.resolve(
                previewFps = 60f,
                recording = true,
                adaptiveStrideMultiplier = 1L
            )

            assertEquals(12L, plan.aiFrameStride)
            assertEquals(480, plan.aiProxyLongEdge)
            assertTrue(plan.shouldAnalyze(1L))
            assertFalse(plan.shouldAnalyze(6L))
            assertTrue(plan.shouldAnalyze(12L))

            // The load-adaptive multiplier still backs the cadence off under thermal/CPU pressure
            // without collapsing the proxy resolution.
            val backedOff = FrameRealtimeAnalysisPolicy.resolve(
                previewFps = 60f,
                recording = true,
                adaptiveStrideMultiplier = 3L
            )
            assertEquals(36L, backedOff.aiFrameStride)
            assertEquals(480, backedOff.aiProxyLongEdge)
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previous
        }
    }

    @Test
    fun preview_keepsAiResponsiveWithoutFullResolutionBitmapWork() {
        val plan = FrameRealtimeAnalysisPolicy.resolve(
            previewFps = 60f,
            recording = false,
            adaptiveStrideMultiplier = 1L
        )

        assertEquals(2L, plan.aiFrameStride)
        assertEquals(720, plan.aiProxyLongEdge)
    }
}
