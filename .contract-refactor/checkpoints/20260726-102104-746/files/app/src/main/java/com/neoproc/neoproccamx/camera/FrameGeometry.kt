package com.neoproc.neoproccamx.camera

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END FRAME GEOMETRY
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade frame geometry with anamorphic lens support, cinema aspect
// ratios, stabilization transforms, and advanced coordinate mapping.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Standard cinema aspect ratios.
 */
enum class CinemaAspectRatio(val ratio: Float, val label: String) {
    RATIO_1_1(1.0f, "1:1"),
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_3_2(3f / 2f, "3:2"),
    RATIO_16_9(16f / 9f, "16:9"),
    RATIO_1_85_1(1.85f, "1.85:1 Flat"),
    RATIO_2_1(2.0f, "2:1 Univisium"),
    RATIO_2_2_1(2.2f, "2.2:1"),
    RATIO_2_35_1(2.35f, "2.35:1 Scope"),
    RATIO_2_39_1(2.39f, "2.39:1 Anamorphic"),
    RATIO_2_76_1(2.76f, "2.76:1 Ultra Panavision");
    
    companion object {
        fun closest(ratio: Float): CinemaAspectRatio {
            return entries.minByOrNull { abs(it.ratio - ratio) } ?: RATIO_16_9
        }
    }
}

/**
 * Anamorphic squeeze factor configuration.
 */
enum class AnamorphicSqueeze(val factor: Float, val label: String) {
    NONE(1.0f, "Spherical"),
    SQUEEZE_1_33X(1.33f, "1.33x"),
    SQUEEZE_1_5X(1.5f, "1.5x"),
    SQUEEZE_1_8X(1.8f, "1.8x"),
    SQUEEZE_2X(2.0f, "2x Full Frame");
    
    fun desqueezedWidth(width: Int): Int = (width * factor).roundToInt()
}

/**
 * Stabilization mode for frame transforms.
 */
enum class FrameStabilizationMode {
    OFF,
    OPTICAL,
    ELECTRONIC,
    HYBRID,
    CINEMATIC // Subtle, natural motion
}

data class NormalizedCropRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val aspectRatio: Float get() = if (height > 0) width / height else 1f
    
    /**
     * Check if this region represents a significant crop.
     */
    val isSignificantCrop: Boolean
        get() = width < 0.95f || height < 0.95f
    
    /**
     * Calculate area as fraction of full sensor (0-1).
     */
    val areaFraction: Float
        get() = width * height

    companion object {
        val FULL = NormalizedCropRegion(0f, 0f, 1f, 1f)
        
        /**
         * Create from center point and dimensions.
         */
        fun fromCenter(centerX: Float, centerY: Float, width: Float, height: Float): NormalizedCropRegion {
            val halfW = width / 2f
            val halfH = height / 2f
            return NormalizedCropRegion(
                left = (centerX - halfW).coerceIn(0f, 1f - width),
                top = (centerY - halfH).coerceIn(0f, 1f - height),
                right = (centerX + halfW).coerceIn(width, 1f),
                bottom = (centerY + halfH).coerceIn(height, 1f)
            )
        }
        
        /**
         * Create for specific aspect ratio (letterbox/pillarbox).
         */
        fun forAspectRatio(targetRatio: Float, sensorRatio: Float): NormalizedCropRegion {
            return when {
                abs(targetRatio - sensorRatio) < 0.01f -> FULL
                targetRatio > sensorRatio -> {
                    // Letterbox (bars on top/bottom)
                    val height = sensorRatio / targetRatio
                    val margin = (1f - height) / 2f
                    NormalizedCropRegion(0f, margin, 1f, 1f - margin)
                }
                else -> {
                    // Pillarbox (bars on sides)
                    val width = targetRatio / sensorRatio
                    val margin = (1f - width) / 2f
                    NormalizedCropRegion(margin, 0f, 1f - margin, 1f)
                }
            }
        }
    }
}

/**
 * Stabilization transform data.
 */
data class StabilizationTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotation: Float = 0f,
    val scale: Float = 1f
) {
    val isIdentity: Boolean
        get() = translationX == 0f && translationY == 0f && rotation == 0f && scale == 1f
    
    companion object {
        val IDENTITY = StabilizationTransform()
    }
}

data class FrameGeometry(
    val sensorOrientationDegrees: Int,
    val deviceRotationDegrees: Int,
    val finalRotationDegrees: Int,
    val lensFacing: Int,
    val sensorAspectRatio: Float,
    val outputAspectRatio: Float?,
    val cropRegion: Rect?,
    val normalizedCropRegion: NormalizedCropRegion,
    
    // Professional extensions
    val anamorphicSqueeze: AnamorphicSqueeze = AnamorphicSqueeze.NONE,
    val stabilizationMode: FrameStabilizationMode = FrameStabilizationMode.OFF,
    val stabilizationTransform: StabilizationTransform = StabilizationTransform.IDENTITY,
    val cinemaAspectRatio: CinemaAspectRatio? = null,
    val sensorWidth: Int = 0,
    val sensorHeight: Int = 0,
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
    val pixelPitch: Float = 0f // µm per pixel
) {
    private val strictHighEndEnabled: Boolean
        get() = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ═══════════════════════════════════════════════════════════════════════════
    // BASIC TRANSFORMS
    // ═══════════════════════════════════════════════════════════════════════════
    
    val previewRotationDegrees: Int get() = finalRotationDegrees
    val videoOrientationHintDegrees: Int get() = finalRotationDegrees
    val aiRotationDegrees: Int get() = finalRotationDegrees
    val previewMirrored: Boolean get() = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
    val videoMirrored: Boolean get() = false
    val aiMirrored: Boolean get() = false
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL PROPERTIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Effective aspect ratio after anamorphic desqueeze.
     */
    val desqueezedAspectRatio: Float
        get() = (outputAspectRatio ?: sensorAspectRatio) * anamorphicSqueeze.factor
    
    /**
     * Closest standard cinema ratio to current output.
     */
    val effectiveCinemaRatio: CinemaAspectRatio
        get() = cinemaAspectRatio ?: CinemaAspectRatio.closest(desqueezedAspectRatio)
    
    /**
     * Crop factor relative to full frame (36mm diagonal).
     */
    val cropFactor: Float
        get() {
            if (sensorWidth <= 0 || sensorHeight <= 0 || pixelPitch <= 0) return 1f
            val sensorDiagonalMm = sqrt(
                (sensorWidth * pixelPitch).let { it * it } +
                (sensorHeight * pixelPitch).let { it * it }
            ) / 1000f
            return 43.27f / sensorDiagonalMm // Full frame diagonal = 43.27mm
        }
    
    /**
     * Whether stabilization is currently affecting the frame.
     */
    val hasActiveStabilization: Boolean
        get() = stabilizationMode != FrameStabilizationMode.OFF && !stabilizationTransform.isIdentity
    
    /**
     * Effective crop after stabilization margin.
     */
    val effectiveCropRegion: NormalizedCropRegion
        get() {
            if (!hasActiveStabilization) return normalizedCropRegion
            // Stabilization typically requires ~5-15% margin
            val baseMargin = when (stabilizationMode) {
                FrameStabilizationMode.OFF -> 0f
                FrameStabilizationMode.OPTICAL -> 0f
                FrameStabilizationMode.ELECTRONIC -> 0.1f
                FrameStabilizationMode.HYBRID -> 0.08f
                FrameStabilizationMode.CINEMATIC -> 0.05f
            }
            val margin = if (strictHighEndEnabled && baseMargin > 0f) {
                (baseMargin * 0.85f).coerceAtLeast(0.03f)
            } else {
                baseMargin
            }
            return NormalizedCropRegion(
                left = normalizedCropRegion.left + margin,
                top = normalizedCropRegion.top + margin,
                right = normalizedCropRegion.right - margin,
                bottom = normalizedCropRegion.bottom - margin
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // COORDINATE TRANSFORMS
    // ═══════════════════════════════════════════════════════════════════════════

    fun cropNormalizedPointToSensorNormalized(x: Float, y: Float): PointF {
        val crop = normalizedCropRegion
        val sensorX = crop.left + crop.width * x.coerceIn(0f, 1f)
        val sensorY = crop.top + crop.height * y.coerceIn(0f, 1f)
        return PointF(sensorX.coerceIn(0f, 1f), sensorY.coerceIn(0f, 1f))
    }

    fun cropNormalizedRectToSensorNormalized(rect: RectF): RectF {
        val topLeft = cropNormalizedPointToSensorNormalized(rect.left, rect.top)
        val bottomRight = cropNormalizedPointToSensorNormalized(rect.right, rect.bottom)
        return RectF(
            minOf(topLeft.x, bottomRight.x).coerceIn(0f, 1f),
            minOf(topLeft.y, bottomRight.y).coerceIn(0f, 1f),
            maxOf(topLeft.x, bottomRight.x).coerceIn(0f, 1f),
            maxOf(topLeft.y, bottomRight.y).coerceIn(0f, 1f)
        )
    }

    fun previewNormalizedPointToSensorNormalized(x: Float, y: Float): Pair<Float, Float> {
        var normX = x.coerceIn(0f, 1f)
        val normY = y.coerceIn(0f, 1f)

        if (previewMirrored) normX = 1f - normX

        val (unrotX, unrotY) = applyRotation(normX, normY, -previewRotationDegrees)
        val crop = normalizedCropRegion
        val sensorX = crop.left + unrotX * crop.width
        val sensorY = crop.top + unrotY * crop.height
        return sensorX.coerceIn(0f, 1f) to sensorY.coerceIn(0f, 1f)
    }
    
    /**
     * Convert sensor coordinates to preview display coordinates.
     */
    fun sensorToDisplayPoint(sensorX: Float, sensorY: Float, displayWidth: Int, displayHeight: Int): PointF {
        val crop = normalizedCropRegion
        
        // Normalize within crop region
        val cropX = ((sensorX - crop.left) / crop.width).coerceIn(0f, 1f)
        val cropY = ((sensorY - crop.top) / crop.height).coerceIn(0f, 1f)
        
        // Apply rotation
        val (rotX, rotY) = applyRotation(cropX, cropY, previewRotationDegrees)
        
        // Apply mirror if front camera
        val finalX = if (previewMirrored) 1f - rotX else rotX
        
        return PointF(finalX * displayWidth, rotY * displayHeight)
    }
    
    /**
     * Convert display coordinates to sensor coordinates.
     */
    fun displayToSensorPoint(displayX: Float, displayY: Float, displayWidth: Int, displayHeight: Int): PointF {
        // Normalize to 0-1
        val normX = displayX / displayWidth
        val normY = displayY / displayHeight
        val (sensorX, sensorY) = previewNormalizedPointToSensorNormalized(normX, normY)
        
        return PointF(sensorX.coerceIn(0f, 1f), sensorY.coerceIn(0f, 1f))
    }
    
    /**
     * Apply stabilization transform to a point.
     */
    fun applyStabilization(x: Float, y: Float): PointF {
        if (stabilizationTransform.isIdentity) return PointF(x, y)
        
        val tx = stabilizationTransform.translationX
        val ty = stabilizationTransform.translationY
        val rot = stabilizationTransform.rotation
        val scale = stabilizationTransform.scale
        
        // Center point
        val cx = x - 0.5f
        val cy = y - 0.5f
        
        // Apply scale
        val sx = cx * scale
        val sy = cy * scale
        
        // Apply rotation
        val cosR = cos(Math.toRadians(rot.toDouble())).toFloat()
        val sinR = sin(Math.toRadians(rot.toDouble())).toFloat()
        val rx = sx * cosR - sy * sinR
        val ry = sx * sinR + sy * cosR
        
        // Apply translation and recenter
        return PointF(
            (rx + 0.5f + tx).coerceIn(0f, 1f),
            (ry + 0.5f + ty).coerceIn(0f, 1f)
        )
    }
    
    private fun applyRotation(x: Float, y: Float, degrees: Int): Pair<Float, Float> {
        val normalizedDegrees = ((degrees % 360) + 360) % 360
        return when (normalizedDegrees) {
            0 -> x to y
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MATRIX GENERATION
    // ═══════════════════════════════════════════════════════════════════════════

    fun previewUvTransformMatrix(): FloatArray =
        buildUvTransformMatrix(
            rotationDegrees = previewRotationDegrees,
            mirrored = previewMirrored
        )

    fun encoderUvTransformMatrix(): FloatArray =
        buildUvTransformMatrix(
            rotationDegrees = 0,
            mirrored = false
        )
    
    /**
     * Generate UV transform with anamorphic desqueeze.
     */
    fun anamorphicUvTransformMatrix(): FloatArray {
        val base = previewUvTransformMatrix()
        if (anamorphicSqueeze == AnamorphicSqueeze.NONE) return base
        
        // Apply horizontal stretch for desqueeze
        val stretch = anamorphicSqueeze.factor
        base[0] *= stretch
        base[4] *= stretch
        return base
    }
    
    /**
     * Generate full transform matrix including stabilization.
     */
    fun fullTransformMatrix(): FloatArray {
        val base = previewUvTransformMatrix()
        if (!hasActiveStabilization) return base
        
        // Build stabilization matrix
        val stab = stabilizationTransform
        val stabMatrix = floatArrayOf(
            stab.scale, 0f, 0f, 0f,
            0f, stab.scale, 0f, 0f,
            0f, 0f, 1f, 0f,
            stab.translationX, stab.translationY, 0f, 1f
        )
        
        // Multiply matrices (simplified for 2D transforms)
        return multiplyMatrices4x4(base, stabMatrix)
    }
    
    private fun multiplyMatrices4x4(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                result[i * 4 + j] = (0 until 4).sumOf { k ->
                    (a[i * 4 + k] * b[k * 4 + j]).toDouble()
                }.toFloat()
            }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CINEMA HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate letterbox/pillarbox bars for aspect ratio mismatch.
     */
    fun calculateBars(displayWidth: Int, displayHeight: Int): Pair<RectF?, RectF?> {
        val displayRatio = displayWidth.toFloat() / displayHeight
        val contentRatio = desqueezedAspectRatio
        
        return when {
            abs(displayRatio - contentRatio) < 0.01f -> null to null
            contentRatio > displayRatio -> {
                // Letterbox (horizontal bars top/bottom)
                val contentHeight = displayWidth / contentRatio
                val barHeight = (displayHeight - contentHeight) / 2f
                RectF(0f, 0f, displayWidth.toFloat(), barHeight) to 
                RectF(0f, displayHeight - barHeight, displayWidth.toFloat(), displayHeight.toFloat())
            }
            else -> {
                // Pillarbox (vertical bars left/right)
                val contentWidth = displayHeight * contentRatio
                val barWidth = (displayWidth - contentWidth) / 2f
                RectF(0f, 0f, barWidth, displayHeight.toFloat()) to
                RectF(displayWidth - barWidth, 0f, displayWidth.toFloat(), displayHeight.toFloat())
            }
        }
    }
    
    /**
     * Calculate frame lines for various aspect ratios (for composition guides).
     */
    fun calculateFrameLines(
        targetRatio: CinemaAspectRatio,
        displayWidth: Int,
        displayHeight: Int
    ): RectF {
        val displayRatio = displayWidth.toFloat() / displayHeight
        val targetValue = targetRatio.ratio * anamorphicSqueeze.factor
        
        return when {
            targetValue > displayRatio -> {
                // Letterbox
                val height = displayWidth / targetValue
                val margin = (displayHeight - height) / 2f
                RectF(0f, margin, displayWidth.toFloat(), displayHeight - margin)
            }
            else -> {
                // Pillarbox
                val width = displayHeight * targetValue
                val margin = (displayWidth - width) / 2f
                RectF(margin, 0f, displayWidth - margin, displayHeight.toFloat())
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEBUG
    // ═══════════════════════════════════════════════════════════════════════════

    fun debugSummary(): String {
        val lensLabel = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
        val cropLabel = cropRegion?.let {
            "[${it.left},${it.top}] [${it.right},${it.bottom}]"
        } ?: "FULL"
        val aspectLabel = outputAspectRatio?.let {
            String.format(Locale.US, "%.2f", it)
        } ?: "FULL"
        return buildString {
            append("sensor=").append(sensorOrientationDegrees)
            append("  device=").append(deviceRotationDegrees)
            append("  final=").append(finalRotationDegrees)
            append('\n')
            append("lens=").append(lensLabel)
            append("  mirror=").append(previewMirrored)
            append("  aspect=").append(aspectLabel)
            append('\n')
            append("crop=").append(cropLabel)
            if (anamorphicSqueeze != AnamorphicSqueeze.NONE) {
                append('\n')
                append("anamorphic=").append(anamorphicSqueeze.label)
                append("  desqueezed=").append(String.format(Locale.US, "%.2f:1", desqueezedAspectRatio))
            }
            if (hasActiveStabilization) {
                append('\n')
                append("stabilization=").append(stabilizationMode)
            }
            if (strictHighEndEnabled) {
                append('\n')
                append("quality=strict_high_end")
            }
        }
    }

    private fun buildUvTransformMatrix(
        rotationDegrees: Int,
        mirrored: Boolean
    ): FloatArray {
        var a = 1f
        var b = 0f
        var d = 0f
        var e = 0f
        var f = 1f
        var h = 0f

        when (((rotationDegrees % 360) + 360) % 360) {
            0 -> Unit
            90 -> {
                a = 0f
                b = 1f
                d = 0f
                e = -1f
                f = 0f
                h = 1f
            }
            180 -> {
                a = -1f
                b = 0f
                d = 1f
                e = 0f
                f = -1f
                h = 1f
            }
            270 -> {
                a = 0f
                b = -1f
                d = 1f
                e = 1f
                f = 0f
                h = 0f
            }
        }

        if (mirrored) {
            a = -a
            b = -b
            d = 1f - d
        }

        val crop = normalizedCropRegion
        a *= crop.width
        b *= crop.width
        d = crop.left + crop.width * d
        e *= crop.height
        f *= crop.height
        h = crop.top + crop.height * h

        return floatArrayOf(
            a, e, 0f, 0f,
            b, f, 0f, 0f,
            0f, 0f, 1f, 0f,
            d, h, 0f, 1f
        )
    }
    
    companion object {
        /**
         * Create default geometry for a camera.
         */
        fun default(
            sensorOrientation: Int,
            lensFacing: Int
        ): FrameGeometry {
            return FrameGeometry(
                sensorOrientationDegrees = sensorOrientation,
                deviceRotationDegrees = 0,
                finalRotationDegrees = sensorOrientation,
                lensFacing = lensFacing,
                sensorAspectRatio = 4f / 3f,
                outputAspectRatio = null,
                cropRegion = null,
                normalizedCropRegion = NormalizedCropRegion.FULL
            )
        }
        
        /**
         * Calculate final rotation from sensor and device orientation.
         */
        fun calculateFinalRotation(
            sensorOrientation: Int,
            deviceRotation: Int,
            lensFacing: Int
        ): Int {
            return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensorOrientation + deviceRotation) % 360
            } else {
                (sensorOrientation - deviceRotation + 360) % 360
            }
        }
    }
}
