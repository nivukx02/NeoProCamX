package com.neoproc.neoproccamx.camera

/**
 * Selects the stable, full active-array RAW source for the project-owned NDR pipeline.
 *
 * NDR is developed after Camera2 in linear sensor space, so an encoder/preview aspect-ratio request
 * must never resize the RAW producer itself. On affected Qualcomm/Vivo providers an advertised
 * cropped RAW size can configure successfully and then abort CHI asynchronously at sustained video
 * cadence. Keeping the normal-pixel-mode active-array RAW stream and cropping only in the downstream
 * GPU stage both preserves more sensor information and avoids that provider failure.
 *
 * [activeArrayRawSize] intentionally excludes maximum-resolution pixel-mode-only dimensions. This
 * policy therefore does not silently engage a slow high-resolution sensor mode.
 */
internal object SensorLinearNdrRawSourceSizePolicy {
    fun resolve(
        activeArrayRawSize: CameraStreamSize?,
        supportedRawSizes: List<CameraStreamSize>,
        fallback: CameraStreamSize
    ): CameraStreamSize {
        val supported = supportedRawSizes
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { normalizedKey(it) }

        activeArrayRawSize
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { preferred ->
                supported.firstOrNull { sameDimensions(it, preferred) }?.let { return it }
            }

        return supported
            .maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })
            ?: fallback
    }

    private fun sameDimensions(first: CameraStreamSize, second: CameraStreamSize): Boolean =
        (first.width == second.width && first.height == second.height) ||
            (first.width == second.height && first.height == second.width)

    private fun normalizedKey(size: CameraStreamSize): String =
        "${maxOf(size.width, size.height)}x${minOf(size.width, size.height)}"
}
