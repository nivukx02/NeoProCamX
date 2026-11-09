package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal object PreviewContentBoundsResolver {
    private const val MIN_BOUNDS_EDGE = 1e-4f
    private const val STRICT_MIN_BOUNDS_EDGE = 1e-3f
    private const val MIN_TARGET_ASPECT = 1e-4f
    private const val STRICT_MIN_TARGET_ASPECT = 0.2f
    private const val STRICT_MAX_TARGET_ASPECT = 5f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun safeBoundsEdge(edge: Float): Float =
        edge.coerceAtLeast(
            if (strictHighEndEnabled()) STRICT_MIN_BOUNDS_EDGE else MIN_BOUNDS_EDGE
        )

    private fun safeTargetAspect(targetAspect: Float): Float {
        val minAspect = if (strictHighEndEnabled()) STRICT_MIN_TARGET_ASPECT else MIN_TARGET_ASPECT
        return if (strictHighEndEnabled()) {
            targetAspect.coerceIn(minAspect, STRICT_MAX_TARGET_ASPECT)
        } else {
            targetAspect.coerceAtLeast(minAspect)
        }
    }

    fun resolve(
        viewWidth: Float,
        viewHeight: Float,
        targetAspect: Float,
        fillViewport: Boolean
    ): PreviewTransformMath.RectBounds {
        val safeWidth = safeBoundsEdge(viewWidth)
        val safeHeight = safeBoundsEdge(viewHeight)
        val safeAspect = safeTargetAspect(targetAspect)
        val bounds = if (fillViewport) {
            PreviewTransformMath.fillBounds(safeWidth, safeHeight, safeAspect)
        } else {
            PreviewTransformMath.fitBounds(safeWidth, safeHeight, safeAspect)
        }
        return PreviewVerticalFramingPolicy.apply(
            bounds = bounds,
            viewHeight = safeHeight,
            targetAspect = safeAspect,
            fillViewport = fillViewport
        )
    }
}
