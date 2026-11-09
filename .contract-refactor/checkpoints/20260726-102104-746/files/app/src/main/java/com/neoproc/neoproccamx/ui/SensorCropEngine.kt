package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

object SensorCropEngine {

    private const val DEFAULT_FULL_SENSOR_ASPECT: Float = 0f
    private const val STANDARD_RATIO_EPSILON = 0.0001f
    private const val STRICT_RATIO_EPSILON = 0.00005f
    @Volatile
    private var configuredFullSensorAspect: Float = DEFAULT_FULL_SENSOR_ASPECT

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun ratioEpsilon(): Float =
        if (strictHighEndEnabled()) STRICT_RATIO_EPSILON else STANDARD_RATIO_EPSILON

    data class NormalizedCrop(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun setFullSensorAspectRatio(ratio: Float?) {
        configuredFullSensorAspect =
            ratio?.takeIf { it > 0f }?.let(::normalizeLongShortRatio) ?: DEFAULT_FULL_SENSOR_ASPECT
    }

    fun fullSensorAspectRatio(): Float =
        configuredFullSensorAspect.takeIf { it > 0f } ?: 1f

    fun normalizeLongShortRatio(ratio: Float?): Float {
        val safe =
            ratio?.takeIf { it > 0f }
                ?: configuredFullSensorAspect.takeIf { it > 0f }
                ?: 1f
        return if (safe >= 1f) safe else 1f / safe
    }

    fun displayAspectForBounds(ratio: Float?, width: Int, height: Int): Float? {
        val safe = ratio?.takeIf { it > 0f } ?: return null
        if (width <= 0 || height <= 0) return null
        val portraitBounds = height > width
        return when {
            portraitBounds && safe > 1f -> 1f / safe
            !portraitBounds && safe < 1f -> 1f / safe
            else -> safe
        }
    }

    fun targetAspectRatio(selection: PreviewAspectRatio): Float? =
        selection.outputRatio()?.let(::normalizeLongShortRatio)

    fun centerCropSensorSpace(sourceRatio: Float?, targetRatio: Float?): NormalizedCrop {
        val source = normalizeLongShortRatio(sourceRatio)
        val target = normalizeLongShortRatio(targetRatio)
        if (abs(source - target) < ratioEpsilon()) {
            return NormalizedCrop(0f, 0f, 1f, 1f)
        }
        return if (target > source) {
            val targetHeight = (source / target).coerceIn(0f, 1f)
            val cropY = ((1f - targetHeight) * 0.5f).coerceIn(0f, 1f)
            NormalizedCrop(0f, cropY, 1f, (cropY + targetHeight).coerceIn(0f, 1f))
        } else {
            val targetWidth = (target / source).coerceIn(0f, 1f)
            val cropX = ((1f - targetWidth) * 0.5f).coerceIn(0f, 1f)
            NormalizedCrop(cropX, 0f, (cropX + targetWidth).coerceIn(0f, 1f), 1f)
        }
    }

    fun centerCropDisplaySpace(sourceRatio: Float?, targetRatio: Float?): NormalizedCrop {
        val source =
            sourceRatio?.takeIf { it > 0f }
                ?: configuredFullSensorAspect.takeIf { it > 0f }
                ?: targetRatio?.takeIf { it > 0f }
                ?: 1f
        val target = targetRatio?.takeIf { it > 0f } ?: source
        if (abs(source - target) < ratioEpsilon()) {
            return NormalizedCrop(0f, 0f, 1f, 1f)
        }
        return if (target < source) {
            val visibleWidth = (target / source).coerceIn(0f, 1f)
            val cropX = ((1f - visibleWidth) * 0.5f).coerceIn(0f, 1f)
            NormalizedCrop(cropX, 0f, (cropX + visibleWidth).coerceIn(0f, 1f), 1f)
        } else {
            val visibleHeight = (source / target).coerceIn(0f, 1f)
            val cropY = ((1f - visibleHeight) * 0.5f).coerceIn(0f, 1f)
            NormalizedCrop(0f, cropY, 1f, (cropY + visibleHeight).coerceIn(0f, 1f))
        }
    }
}
