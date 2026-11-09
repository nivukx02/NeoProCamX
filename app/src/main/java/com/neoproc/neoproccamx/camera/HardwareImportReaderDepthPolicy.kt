package com.neoproc.neoproccamx.camera

internal data class HardwareImportReaderDepthPlan(
    val maxImages: Int,
    val estimatedFrameBytes: Long,
    val estimatedReaderBytes: Long
)

/** Bounds camera-owned GPU import buffers by bytes instead of an unsafe fixed frame count. */
internal object HardwareImportReaderDepthPolicy {
    /**
     * Neo Thermal Stall Vault V4. A current-candidate five-minute device run at Android thermal
     * status 4 measured an otherwise lossless 1.759 s producer stall after 274 s. The V3 880 ms
     * vault reached its twenty-one-frame live high-water mark, so Camera2 could no longer publish
     * every P010 buffer even though the app dropped and rejected zero frames.
     *
     * Devices with at least 10 GiB RAM admit fifty-five 4096x3072 P010 images. This remains within
     * Android's 64-buffer BufferQueue ceiling after the vendor's nine producer-side buffers. After
     * the two Camera2/native reservation slots, fifty-three exact frames protect 2.120 s at 25 fps. Smaller
     * devices retain the proven V3 864 MiB / twenty-four-image bound. This changes ownership depth
     * only: pixels, bit depth, resolution, bitrate, colour processing, codec, and timestamps remain
     * governed by their existing contracts.
     */
    const val BASELINE_MAX_READER_BYTES = 864L * 1024L * 1024L
    const val LARGE_MEMORY_MAX_READER_BYTES = 1_980L * 1024L * 1024L
    const val LARGE_MEMORY_DEVICE_THRESHOLD_BYTES = 10L * 1024L * 1024L * 1024L
    const val MAX_READER_BYTES = LARGE_MEMORY_MAX_READER_BYTES
    private const val MIN_READER_IMAGES = 2

    fun resolve(
        size: CameraStreamSize,
        p010: Boolean,
        strictHighEnd: Boolean,
        deviceTotalMemoryBytes: Long = 0L
    ): HardwareImportReaderDepthPlan {
        val estimatedFrameBytes = size.area * if (p010) 3L else 2L
        val largeMemoryP010Vault = strictHighEnd && p010 &&
            deviceTotalMemoryBytes >= LARGE_MEMORY_DEVICE_THRESHOLD_BYTES
        val readerByteBudget = if (largeMemoryP010Vault) {
            LARGE_MEMORY_MAX_READER_BYTES
        } else {
            BASELINE_MAX_READER_BYTES
        }
        val maximumImages = when {
            largeMemoryP010Vault ->
                CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT_P010
            strictHighEnd && p010 -> 24
            strictHighEnd -> CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT
            else -> CameraControllerConfig.ReaderDepth.MAX_HARDWARE_IMPORT
        }
        val maxImages = (readerByteBudget / estimatedFrameBytes.coerceAtLeast(1L))
            .toInt()
            .coerceIn(MIN_READER_IMAGES, maximumImages)
        return HardwareImportReaderDepthPlan(
            maxImages = maxImages,
            estimatedFrameBytes = estimatedFrameBytes,
            estimatedReaderBytes = estimatedFrameBytes * maxImages
        )
    }
}
