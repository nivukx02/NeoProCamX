package com.neoproc.neoproccamx.rendering

internal object PreviewVerticalFramingPolicy {
    private const val FOUR_THREE_ASPECT = 4f / 3f
    private const val THREE_FOUR_ASPECT = 3f / 4f
    private const val ASPECT_EPSILON = 0.01f
    private const val UPWARD_BIAS_FRACTION = 0.22f

    fun apply(
        bounds: PreviewTransformMath.RectBounds,
        viewHeight: Float,
        targetAspect: Float,
        fillViewport: Boolean
    ): PreviewTransformMath.RectBounds {
        if (fillViewport || !isFourThreeAspect(targetAspect)) return bounds

        val verticalSlack = (viewHeight - bounds.height).coerceAtLeast(0f)
        if (verticalSlack <= 0f) return bounds

        val requestedOffset = verticalSlack * UPWARD_BIAS_FRACTION
        val shiftedTop = (bounds.top - requestedOffset).coerceAtLeast(0f)
        val appliedOffset = bounds.top - shiftedTop
        return bounds.copy(
            top = shiftedTop,
            bottom = bounds.bottom - appliedOffset
        )
    }

    private fun isFourThreeAspect(aspect: Float): Boolean =
        kotlin.math.abs(aspect - FOUR_THREE_ASPECT) <= ASPECT_EPSILON ||
            kotlin.math.abs(aspect - THREE_FOUR_ASPECT) <= ASPECT_EPSILON
}
