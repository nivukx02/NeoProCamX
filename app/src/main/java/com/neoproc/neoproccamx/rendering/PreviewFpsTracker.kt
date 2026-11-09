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
        // QA and diagnostics need a cadence measurement that is not dominated by one scheduler
        // wake-up or compositor fence. Runtime health keeps the responsive short window above.
        private const val STABLE_EVIDENCE_WINDOW_NS = 3_200_000_000L
        // A Camera2 generation may release a small burst of already-queued presentations while its
        // replacement graph settles. Exclude that bounded transition population from durable cadence
        // evidence; responsive runtime health monitoring above remains active from the first frame.
        private const val STABLE_EVIDENCE_WARMUP_NS = 480_000_000L
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
    private var stableWindowStartNs = 0L
    private var stableWarmupDeadlineNs = 0L
    private var stableWindowFrames = 0
    private var latestStableFps: Float? = null

    fun recordFrame(timestampNs: Long): Float? {
        if (timestampNs <= 0L) return null
        if (lastPreviewTimestampNs > 0L && timestampNs < lastPreviewTimestampNs) {
            resetWindow(timestampNs)
            return null
        }
        if (fpsWindowStartNs == 0L) {
            // The first presentation anchors the measurement window; it does not complete a frame
            // interval. Counting it made the first post-reset window report N samples across N-1
            // intervals (for example, 26 FPS for a true 25 FPS stream). This matters after every
            // mode switch, where the first fresh-mode cadence sample is the acceptance signal.
            fpsWindowStartNs = timestampNs
            stableWindowStartNs = 0L
            stableWarmupDeadlineNs = timestampNs + STABLE_EVIDENCE_WARMUP_NS
            lastPreviewTimestampNs = timestampNs
            fpsWindowFrames = 0
            stableWindowFrames = 0
            clearFrameDeltaStats()
            return null
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
        if (stableWindowStartNs == 0L) {
            if (timestampNs >= stableWarmupDeadlineNs) {
                stableWindowStartNs = timestampNs
                stableWindowFrames = 0
            }
        } else {
            stableWindowFrames += 1
            val stableElapsedNs = timestampNs - stableWindowStartNs
            if (stableElapsedNs >= STABLE_EVIDENCE_WINDOW_NS) {
                latestStableFps = stableWindowFrames * 1_000_000_000f / stableElapsedNs.toFloat()
                stableWindowStartNs = timestampNs
                stableWindowFrames = 0
            }
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

    /** Stable final-display cadence; null until one complete evidence window exists. */
    fun latestStableMeasuredFps(): Float? = latestStableFps

    private fun resetWindow(startTimestampNs: Long) {
        fpsWindowStartNs = startTimestampNs
        fpsWindowFrames = 0
        stableWindowStartNs = 0L
        stableWarmupDeadlineNs = startTimestampNs + STABLE_EVIDENCE_WARMUP_NS
        stableWindowFrames = 0
        latestStableFps = null
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
        stableWindowStartNs = 0L
        stableWarmupDeadlineNs = 0L
        stableWindowFrames = 0
        latestStableFps = null
        smoothedFps = 0f
        minObservedFps = Float.POSITIVE_INFINITY
        maxObservedFps = 0f
        latestSnapshot = null
        clearFrameDeltaStats()
    }
}
