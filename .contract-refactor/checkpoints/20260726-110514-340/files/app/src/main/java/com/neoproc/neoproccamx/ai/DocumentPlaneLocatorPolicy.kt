package com.neoproc.neoproccamx.ai

import android.graphics.RectF

/**
 * AF-SD (F11) — pure helper that turns an optional segmentation foreground mask into a document-plane
 * focus point + bounds + confidence. No quad detector exists in the codebase, so v1 is a
 * centered/foreground-plane bias: if segmentation coverage is meaningful, use its coverage-weighted
 * centroid and covered extent; otherwise fall back to a centered page-plane rectangle.
 */
internal object DocumentPlaneLocatorPolicy {

    data class Plane(
        val focusX: Float,
        val focusY: Float,
        val bounds: RectF,
        val confidence: Float,
        val coverage: Float
    )

    private const val FALLBACK_HALF_W = 0.34f
    private const val FALLBACK_HALF_H = 0.30f
    private const val GRID = 16
    private const val FOREGROUND_THRESHOLD = 0.5f

    fun locate(segmentation: SegmentationData?, minCoverage: Float): Plane {
        val seg = segmentation
        if (seg != null && seg.foregroundMask.isNotEmpty() && seg.foregroundCoverage >= minCoverage) {
            var weightSum = 0f
            var cxAcc = 0f
            var cyAcc = 0f
            var minX = 1f
            var maxX = 0f
            var minY = 1f
            var maxY = 0f
            var covered = false
            for (gy in 0 until GRID) {
                val ny = (gy + 0.5f) / GRID
                for (gx in 0 until GRID) {
                    val nx = (gx + 0.5f) / GRID
                    val c = seg.sampleForegroundConfidence(nx, ny)
                    if (c > FOREGROUND_THRESHOLD) {
                        if (nx < minX) minX = nx
                        if (nx > maxX) maxX = nx
                        if (ny < minY) minY = ny
                        if (ny > maxY) maxY = ny
                        covered = true
                    }
                    cxAcc += nx * c
                    cyAcc += ny * c
                    weightSum += c
                }
            }
            if (weightSum > 1e-4f && covered && maxX >= minX && maxY >= minY) {
                val fx = (cxAcc / weightSum).coerceIn(0f, 1f)
                val fy = (cyAcc / weightSum).coerceIn(0f, 1f)
                val bounds = RectF().apply { left = minX; top = minY; right = maxX; bottom = maxY }
                val conf = (0.55f + seg.foregroundCoverage * 0.4f).coerceIn(0f, 0.95f)
                return Plane(fx, fy, bounds, conf, seg.foregroundCoverage)
            }
        }
        // Centered page-plane fallback.
        val bounds = RectF().apply {
            left = 0.5f - FALLBACK_HALF_W
            top = 0.5f - FALLBACK_HALF_H
            right = 0.5f + FALLBACK_HALF_W
            bottom = 0.5f + FALLBACK_HALF_H
        }
        return Plane(0.5f, 0.5f, bounds, 0.5f, seg?.foregroundCoverage ?: 0f)
    }
}
