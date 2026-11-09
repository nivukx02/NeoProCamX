package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

/**
 * CameraSensorInfo — Sensor geometry and capability queries.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * EXTRACTED FROM CameraController — Phase 2 God-Class Decomposition
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Provides stateless queries for sensor size, megapixel count, and aspect ratio.
 * All methods accept explicit parameters rather than reading instance state.
 *
 * Thread safety: Stateless. All methods are safe to call from any thread.
 */
object CameraSensorInfo {

    /**
     * Resolve the full sensor size from available sources.
     * Priority: pixelArraySize > maxRawSize > maxYuvSize > maxPhotoSize > fallback.
     * Always returns landscape orientation (width ≥ height).
     */
    fun fullSensorSize(
        pixelArraySize: Size?,
        maxRawSize: Size?,
        maxYuvSize: Size?,
        maxPhotoSize: Size?
    ): Size {
        val candidates = buildList {
            pixelArraySize?.let { add(it) }
            maxRawSize?.let { add(it) }
            maxYuvSize?.let { add(it) }
        }
            .map { if (it.width >= it.height) it else Size(it.height, it.width) }
        if (candidates.isNotEmpty()) {
            return candidates.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: candidates.first()
        }
        val fallback = maxPhotoSize ?: Size(4000, 3000)
        return if (fallback.width >= fallback.height) fallback else Size(fallback.height, fallback.width)
    }

    /**
     * Calculate full sensor megapixel count.
     */
    fun fullSensorMegapixels(sensorSize: Size): Float =
        (sensorSize.width.toLong() * sensorSize.height.toLong()) / 1_000_000f

    /**
     * Calculate the sensor's native aspect ratio (always ≥ 1.0).
     */
    fun sensorBaseAspectRatio(sensorSize: Size): Float {
        val longEdge = maxOf(sensorSize.width, sensorSize.height).coerceAtLeast(1)
        val shortEdge = minOf(sensorSize.width, sensorSize.height).coerceAtLeast(1)
        return longEdge.toFloat() / shortEdge.toFloat()
    }

    /**
     * Get the hardware level label for a given hardware level constant.
     */
    fun hardwareLevelLabel(level: Int): String =
        when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            else -> "UNKNOWN"
        }

    /**
     * Compute the area of a size in pixels.
     */
    fun sizeArea(size: Size): Long = size.width.toLong() * size.height.toLong()

    /**
     * Return the largest size by area from a vararg of nullable sizes.
     */
    fun largestByArea(vararg sizes: Size?): Size? =
        sizes.filterNotNull().maxByOrNull(::sizeArea)
}
