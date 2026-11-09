package com.neoproc.neoproccamx.rendering

/** Actual native preview-swapchain precision reported by the Vulkan bridge. */
internal enum class VulkanPreviewSurfacePrecision {
    UNKNOWN,
    EIGHT_BIT,
    PACKED_10_BIT,
    FP16
}

internal data class VulkanPreviewSurfaceStatus(
    val bound: Boolean,
    val format: String,
    val precision: VulkanPreviewSurfacePrecision
) {
    val strictTenBitOrBetterProven: Boolean
        get() = bound && (
            precision == VulkanPreviewSurfacePrecision.PACKED_10_BIT ||
                precision == VulkanPreviewSurfacePrecision.FP16
            )

    companion object {
        val UNBOUND = VulkanPreviewSurfaceStatus(
            bound = false,
            format = "UNDEFINED",
            precision = VulkanPreviewSurfacePrecision.UNKNOWN
        )
    }
}

/**
 * Parses native truth, not the requested preference. The Vulkan implementation may legally fall
 * back from a requested 10-bit SDR swapchain to RGBA8; strict preview admission must reject that.
 */
internal object VulkanPreviewSurfaceStatusParser {
    fun parse(summary: String): VulkanPreviewSurfaceStatus {
        val tokens = summary
            .split(' ')
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0 || separator == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separator) to token.substring(separator + 1)
                }
            }
            .toMap()
        if (tokens["preview"] != "on") return VulkanPreviewSurfaceStatus.UNBOUND
        val format = tokens["previewFmt"].orEmpty()
        val precision = when (format) {
            "A2B10G10R10", "A2R10G10B10" -> VulkanPreviewSurfacePrecision.PACKED_10_BIT
            "RGBA16F" -> VulkanPreviewSurfacePrecision.FP16
            "RGBA8", "BGRA8" -> VulkanPreviewSurfacePrecision.EIGHT_BIT
            else -> VulkanPreviewSurfacePrecision.UNKNOWN
        }
        return VulkanPreviewSurfaceStatus(
            bound = true,
            format = format.ifBlank { "UNKNOWN" },
            precision = precision
        )
    }
}
