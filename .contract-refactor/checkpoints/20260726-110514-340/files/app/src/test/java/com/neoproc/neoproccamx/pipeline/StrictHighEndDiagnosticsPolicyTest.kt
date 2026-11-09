package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class StrictHighEndDiagnosticsPolicyTest {

    @Test
    fun thirtyFpsPreview_keepsPeakingResponsiveWithoutFullFrequencyStats() {
        val plan = StrictHighEndDiagnosticsPolicy.resolve(
            previewFps = 30f,
            recording = false
        )

        assertEquals(2L, plan.sceneStatsStride)
        // LAG FIX: peaking + zebra both recompute every captured frame at nominal ≤30fps preview.
        assertEquals(1L, plan.peakingStride)
        assertEquals(1L, plan.zebraStride)
        assertEquals(320, plan.statsLongEdge)
        assertEquals(384, plan.maskLongEdge)
    }

    @Test
    fun sixtyFpsPreview_tradesCadenceForSmootherLivePerformance() {
        val plan = StrictHighEndDiagnosticsPolicy.resolve(
            previewFps = 60f,
            recording = false
        )

        assertEquals(2L, plan.sceneStatsStride)
        // LAG FIX: peaking every frame, zebra every other frame at 60fps (halved mask lag).
        assertEquals(1L, plan.peakingStride)
        assertEquals(2L, plan.zebraStride)
        assertEquals(288, plan.statsLongEdge)
        assertEquals(320, plan.maskLongEdge)
    }

    @Test
    fun recordingPreview_keepsDiagnosticsSparseForRealtimeCapture() {
        val plan = StrictHighEndDiagnosticsPolicy.resolve(
            previewFps = 30f,
            recording = true
        )

        assertEquals(8L, plan.sceneStatsStride)
        assertEquals(8L, plan.peakingStride)
        assertEquals(8L, plan.zebraStride)
        assertEquals(160, plan.statsLongEdge)
        assertEquals(192, plan.maskLongEdge)
    }

    @Test
    fun lowFpsRecordingPreview_usesRecoveryMeterCadence() {
        val plan = StrictHighEndDiagnosticsPolicy.resolve(
            previewFps = 18f,
            recording = true
        )

        assertEquals(12L, plan.sceneStatsStride)
        assertEquals(12L, plan.peakingStride)
        assertEquals(12L, plan.zebraStride)
        assertEquals(160, plan.statsLongEdge)
        assertEquals(192, plan.maskLongEdge)
    }

    @Test
    fun lowFpsIdlePreview_avoidsPerFrameStrictMeters() {
        val plan = StrictHighEndDiagnosticsPolicy.resolve(
            previewFps = 22f,
            recording = false
        )

        assertEquals(4L, plan.sceneStatsStride)
        assertEquals(2L, plan.peakingStride)
        assertEquals(4L, plan.zebraStride)
        assertEquals(256, plan.statsLongEdge)
        assertEquals(288, plan.maskLongEdge)
    }
}
