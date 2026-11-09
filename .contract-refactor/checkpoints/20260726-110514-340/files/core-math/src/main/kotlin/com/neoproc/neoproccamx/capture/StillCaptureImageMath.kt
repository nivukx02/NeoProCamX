package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class NormalizedSubjectBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

data class ImageCropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

object StillCaptureImageMath {
    private const val SENSOR_ASPECT_EPSILON = 0.0001f
    private const val CROPPED_ASPECT_EPSILON = 0.01f
    private const val STRICT_SENSOR_ASPECT_EPSILON = 0.00005f
    private const val STRICT_CROPPED_ASPECT_EPSILON = 0.005f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun sensorAspectEpsilon(): Float =
        if (strictHighEndEnabled()) STRICT_SENSOR_ASPECT_EPSILON else SENSOR_ASPECT_EPSILON

    private fun croppedAspectEpsilon(): Float =
        if (strictHighEndEnabled()) STRICT_CROPPED_ASPECT_EPSILON else CROPPED_ASPECT_EPSILON

    fun requiresAspectCrop(outputAspect: Float?, sensorAspect: Float): Boolean {
        val targetAspect = outputAspect ?: return false
        return abs(sensorAspect - targetAspect) > sensorAspectEpsilon()
    }

    fun resolveCenteredAspectCrop(
        width: Int,
        height: Int,
        targetAspect: Float?
    ): ImageCropRect? {
        if (targetAspect == null || width <= 1 || height <= 1) return null
        val normalizedTarget = if (targetAspect >= 1f) targetAspect else 1f / targetAspect
        val bitmapAspect = max(width, height).toFloat() / min(width, height).toFloat()
        if (abs(bitmapAspect - normalizedTarget) < croppedAspectEpsilon()) return null

        val cropWidth: Int
        val cropHeight: Int
        if (bitmapAspect > normalizedTarget) {
            cropHeight = height
            cropWidth = (height * normalizedTarget).roundToInt().coerceAtMost(width)
        } else {
            cropWidth = width
            cropHeight = (width / normalizedTarget).roundToInt().coerceAtMost(height)
        }

        val left = ((width - cropWidth) * 0.5f).roundToInt().coerceIn(0, width - cropWidth)
        val top = ((height - cropHeight) * 0.5f).roundToInt().coerceIn(0, height - cropHeight)
        return ImageCropRect(left, top, cropWidth, cropHeight)
    }

}
