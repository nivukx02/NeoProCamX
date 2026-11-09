package com.neoproc.neoproccamx.pipeline

internal data class StrictHighEndDiagnosticsPlan(
    val sceneStatsStride: Long,
    val peakingStride: Long,
    val zebraStride: Long,
    val statsLongEdge: Int,
    val maskLongEdge: Int
)

internal object StrictHighEndDiagnosticsPolicy {
    fun resolve(previewFps: Float, recording: Boolean): StrictHighEndDiagnosticsPlan {
        val fps = previewFps.coerceAtLeast(1f)
        return when {
            recording && fps < 24f -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 12L,
                peakingStride = 12L,
                zebraStride = 12L,
                statsLongEdge = 160,
                maskLongEdge = 192
            )

            recording && fps < 25f -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 10L,
                peakingStride = 10L,
                zebraStride = 10L,
                statsLongEdge = 160,
                maskLongEdge = 192
            )

            recording -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 8L,
                peakingStride = 8L,
                zebraStride = 8L,
                statsLongEdge = 160,
                maskLongEdge = 192
            )

            fps < 24f -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 4L,
                peakingStride = 2L,
                zebraStride = 4L,
                statsLongEdge = 256,
                maskLongEdge = 288
            )

            fps <= 30f -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 2L,
                // LAG FIX: both masks track the picture frame-for-frame at nominal ≤30fps preview.
                peakingStride = 1L,
                zebraStride = 1L,
                statsLongEdge = 320,
                maskLongEdge = 384
            )

            fps <= 60f -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 2L,
                // LAG FIX: halve mask lag at high frame rate (peaking every frame, zebra every other).
                peakingStride = 1L,
                zebraStride = 2L,
                statsLongEdge = 288,
                maskLongEdge = 320
            )

            else -> StrictHighEndDiagnosticsPlan(
                sceneStatsStride = 3L,
                peakingStride = 2L,
                zebraStride = 3L,
                statsLongEdge = 256,
                maskLongEdge = 288
            )
        }
    }
}
