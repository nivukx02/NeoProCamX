package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.max

internal data class FrameRealtimeAnalysisPlan(
    val aiFrameStride: Long,
    val aiProxyLongEdge: Int
) {
    fun shouldAnalyze(frameSequence: Long): Boolean =
        frameSequence <= 1L || frameSequence % aiFrameStride == 0L
}

internal object FrameRealtimeAnalysisPolicy {
    fun resolve(
        previewFps: Float,
        recording: Boolean,
        adaptiveStrideMultiplier: Long
    ): FrameRealtimeAnalysisPlan {
        val fps = previewFps.coerceAtLeast(1f)
        val multiplier = adaptiveStrideMultiplier.coerceAtLeast(1L)
        val strictHighEndRecording =
            recording && HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val baseStride = when {
            strictHighEndRecording && fps < 24f -> 14L
            strictHighEndRecording && fps < 28f -> 12L
            strictHighEndRecording -> 10L
            recording -> 12L
            fps <= 24f -> 4L
            fps <= 30f -> 3L
            fps <= 48f -> 2L
            else -> 2L
        }
        val proxyLongEdge = when {
            strictHighEndRecording -> 480
            recording -> 480
            fps <= 24f -> 480
            fps <= 30f -> 560
            else -> 720
        }
        return FrameRealtimeAnalysisPlan(
            aiFrameStride = max(1L, baseStride * multiplier),
            aiProxyLongEdge = proxyLongEdge
        )
    }
}
