package com.neoproc.neoproccamx.pipeline

import java.util.Locale

internal data class FramePoolSnapshot(
    val activeBuffers: Int,
    val pooledBuffers: Int
) {
    val totalBuffers: Int
        get() = activeBuffers + pooledBuffers

    val utilizationPercent: Int
        get() = if (totalBuffers <= 0) 0 else ((activeBuffers * 100f) / totalBuffers).toInt()
}

internal data class FrameDropSummary(
    val currentFps: Int,
    val processedFrames: Int,
    val skippedFrames: Int
) {
    val totalFrames: Int
        get() = processedFrames + skippedFrames

    val dropRate: Float
        get() = if (totalFrames <= 0) 0f else skippedFrames.toFloat() / totalFrames.toFloat()
}

internal object FramePipelineTelemetryPolicy {

    fun formatPoolUtilization(label: String, snapshot: FramePoolSnapshot): String {
        return if (snapshot.totalBuffers <= 0) {
            "$label idle"
        } else {
            String.format(
                Locale.US,
                "%s %d%% (%d/%d active)",
                label,
                snapshot.utilizationPercent,
                snapshot.activeBuffers,
                snapshot.totalBuffers
            )
        }
    }

    fun formatRollingDropSummary(summary: FrameDropSummary, busyDrops: Long): String {
        return String.format(
            Locale.US,
            "Drop %.1f%% | %dfps | busy=%d",
            summary.dropRate * 100f,
            summary.currentFps,
            busyDrops
        )
    }
}
