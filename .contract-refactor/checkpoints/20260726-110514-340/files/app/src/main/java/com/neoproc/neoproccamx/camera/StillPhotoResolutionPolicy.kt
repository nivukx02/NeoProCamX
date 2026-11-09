package com.neoproc.neoproccamx.camera

/**
 * Keeps ordinary Camera2 outputs separate from ultra-high-resolution outputs. Sizes from
 * SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION are not valid in a default pixel-mode
 * session, even when an ImageReader can be constructed with those dimensions.
 */
internal data class StillPhotoResolutionPlan(
    val normalSessionSizes: List<CameraStreamSize>,
    val maximumResolutionSessionSizes: List<CameraStreamSize>,
    val maximumPixelModeConcurrentSizes: List<CameraStreamSize>,
    val allAdvertisedSizes: List<CameraStreamSize>
)

internal object StillPhotoResolutionPolicy {
    fun resolve(
        standardMapSizes: List<CameraStreamSize>,
        maximumResolutionMapSizes: List<CameraStreamSize>
    ): StillPhotoResolutionPlan {
        val standard = sortedDistinct(standardMapSizes)
        val maximum = sortedDistinct(maximumResolutionMapSizes)
        val normal = standard.ifEmpty { maximum }
        val maximumSession = maximum.ifEmpty { normal }

        // A RAW maximum-pixel-mode recording cannot be interrupted for a dedicated still session.
        // Prefer dimensions explicitly exposed by both maps. If the maps have no exact overlap,
        // stay within the normal-map area ceiling while selecting from the maximum map. This keeps
        // the JPEG legal for maximum pixel mode without attaching an unbounded remosaic surface to
        // the live video graph.
        val shared = maximum.filter { candidate ->
            standard.any { it.sameDimensions(candidate) }
        }
        val normalAreaCeiling = normal.maxOfOrNull { it.area } ?: Long.MAX_VALUE
        val boundedMaximum = maximum.filter { it.area <= normalAreaCeiling }
        val maximumConcurrent = when {
            shared.isNotEmpty() -> shared
            boundedMaximum.isNotEmpty() -> boundedMaximum
            maximum.isNotEmpty() -> listOf(maximum.minBy { it.area })
            else -> normal
        }

        return StillPhotoResolutionPlan(
            normalSessionSizes = normal,
            maximumResolutionSessionSizes = maximumSession,
            maximumPixelModeConcurrentSizes = sortedDistinct(maximumConcurrent),
            allAdvertisedSizes = sortedDistinct(maximum + standard)
        )
    }

    private fun sortedDistinct(sizes: List<CameraStreamSize>): List<CameraStreamSize> =
        sizes
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { size ->
                "${minOf(size.width, size.height)}x${maxOf(size.width, size.height)}"
            }
            .sortedWith(
                compareByDescending<CameraStreamSize> { it.area }
                    .thenByDescending { it.width }
                    .thenByDescending { it.height }
            )

    private fun CameraStreamSize.sameDimensions(other: CameraStreamSize): Boolean =
        (width == other.width && height == other.height) ||
            (width == other.height && height == other.width)
}
