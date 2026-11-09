package com.neoproc.neoproccamx.ui

internal object VulkanPreviewDisplayAspectPolicy {
    private const val FOUR_THREE_ASPECT = 4f / 3f
    private const val THREE_FOUR_ASPECT = 3f / 4f
    private const val ASPECT_EPSILON = 0.01f

    private fun isFourThreeAspect(aspect: Float): Boolean =
        kotlin.math.abs(aspect - FOUR_THREE_ASPECT) <= ASPECT_EPSILON ||
            kotlin.math.abs(aspect - THREE_FOUR_ASPECT) <= ASPECT_EPSILON

    fun resolve(
        viewWidth: Int,
        viewHeight: Int,
        resolvedPreviewAspect: Float?
    ): Float {
        val resolved = resolvedPreviewAspect?.takeIf { it.isFinite() && it > 0f }
        if (resolved != null && isFourThreeAspect(resolved)) {
            return resolved
        }
        return if (viewWidth > 0 && viewHeight > 0) {
            viewWidth.toFloat() / viewHeight.toFloat()
        } else {
            1f
        }
    }
}
