package com.neoproc.neoproccamx.ui

internal object VulkanPreviewDisplayAspectPolicy {
    fun resolve(
        viewWidth: Int,
        viewHeight: Int,
        resolvedPreviewAspect: Float?
    ): Float {
        val resolved = resolvedPreviewAspect?.takeIf { it.isFinite() && it > 0f }
        if (resolved != null) return resolved
        return if (viewWidth > 0 && viewHeight > 0) {
            viewWidth.toFloat() / viewHeight.toFloat()
        } else {
            1f
        }
    }
}
