package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.sqrt

internal data class PreviewFramePacingSnapshot(
    val fps: Float,
    val smoothedFps: Float,
    val minFps: Float,
    val maxFps: Float,
    val jitterMs: Float
)

internal class PreviewFpsTracker {
    companion object {
        private const val WINDOW_NS = 1_000_000_000L
        private const val STRICT_WINDOW_NS = 850_000_000L
        private const val FPS_SMOOTHING_ALPHA = 0.28f
        private const val STRICT_FPS_SMOOTHING_ALPHA = 0.24f
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun windowNs(): Long =
        if (strictHighEndEnabled()) STRICT_WINDOW_NS else WINDOW_NS

    private fun fpsSmoothingAlpha(): Float =
        if (strictHighEndEnabled()) STRICT_FPS_SMOOTHING_ALPHA else FPS_SMOOTHING_ALPHA

    private var lastPreviewTimestampNs = 0L
    private var fpsWindowStartNs = 0L
    private var fpsWindowFrames = 0
    private var smoothedFps = 0f
    private var minObservedFps = Float.POSITIVE_INFINITY
    private var maxObservedFps = 0f
    private var frameDeltaCount = 0
    private var frameDeltaMeanNs = 0.0
    private var frameDeltaM2Ns = 0.0
    private var latestSnapshot: PreviewFramePacingSnapshot? = null

    fun recordFrame(timestampNs: Long): Float? {
        if (timestampNs <= 0L) return null
        if (lastPreviewTimestampNs > 0L && timestampNs < lastPreviewTimestampNs) {
            resetWindow(timestampNs)
            return null
        }
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = timestampNs
            fpsWindowFrames = 0
            clearFrameDeltaStats()
        }
        if (timestampNs != lastPreviewTimestampNs) {
            if (lastPreviewTimestampNs > 0L) {
                val deltaNs = timestampNs - lastPreviewTimestampNs
                if (deltaNs > 0L) {
                    updateFrameDeltaStats(deltaNs)
                }
            }
            fpsWindowFrames += 1
            lastPreviewTimestampNs = timestampNs
        }
        val elapsedNs = timestampNs - fpsWindowStartNs
        if (elapsedNs < windowNs()) return null

        val fps = fpsWindowFrames * 1_000_000_000f / elapsedNs.toFloat()
        smoothedFps = if (smoothedFps <= 0f) {
            fps
        } else {
            smoothedFps + (fps - smoothedFps) * fpsSmoothingAlpha()
        }
        minObservedFps = if (minObservedFps.isFinite()) {
            minOf(minObservedFps, fps)
        } else {
            fps
        }
        maxObservedFps = maxOf(maxObservedFps, fps)
        latestSnapshot = PreviewFramePacingSnapshot(
            fps = fps,
            smoothedFps = smoothedFps,
            minFps = minObservedFps,
            maxFps = maxObservedFps,
            jitterMs = computeJitterMs()
        )
        fpsWindowStartNs = timestampNs
        fpsWindowFrames = 0
        clearFrameDeltaStats()
        return smoothedFps
    }

    fun latestPacingSnapshot(): PreviewFramePacingSnapshot? = latestSnapshot

    private fun resetWindow(startTimestampNs: Long) {
        fpsWindowStartNs = startTimestampNs
        fpsWindowFrames = 0
        lastPreviewTimestampNs = startTimestampNs
        clearFrameDeltaStats()
    }

    private fun clearFrameDeltaStats() {
        frameDeltaCount = 0
        frameDeltaMeanNs = 0.0
        frameDeltaM2Ns = 0.0
    }

    private fun updateFrameDeltaStats(deltaNs: Long) {
        frameDeltaCount += 1
        val delta = deltaNs.toDouble()
        val meanDelta = delta - frameDeltaMeanNs
        frameDeltaMeanNs += meanDelta / frameDeltaCount.toDouble()
        val deltaAfterMean = delta - frameDeltaMeanNs
        frameDeltaM2Ns += meanDelta * deltaAfterMean
    }

    private fun computeJitterMs(): Float {
        if (frameDeltaCount < 2) return 0f
        val varianceNs = frameDeltaM2Ns / (frameDeltaCount - 1).toDouble()
        return sqrt(varianceNs).toFloat() / 1_000_000f
    }

    fun reset() {
        lastPreviewTimestampNs = 0L
        fpsWindowStartNs = 0L
        fpsWindowFrames = 0
        smoothedFps = 0f
        minObservedFps = Float.POSITIVE_INFINITY
        maxObservedFps = 0f
        latestSnapshot = null
        clearFrameDeltaStats()
    }
}
