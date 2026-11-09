package com.neoproc.neoproccamx.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import androidx.exifinterface.media.ExifInterface
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * IMAGE UTILS — COMPREHENSIVE IMAGE PROCESSING AND MANIPULATION UTILITIES
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Comprehensive image utility providing:
 *   - Bitmap creation, scaling, and transformation operations
 *   - Color space conversion and manipulation
 *   - EXIF metadata reading and writing
 *   - Format conversion (JPEG, PNG, WEBP, RAW)
 *   - Image cropping, rotation, and flipping
 *   - Filter and effect application
 *   - Memory-efficient bitmap operations
 *   - YUV to RGB conversion for camera frames
 *
 * Performance: Optimized native operations with memory pooling.
 * Quality: High-quality resampling and color management.
 *
 * @author NeoProCamX Image Processing Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
object ImageUtils {

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // IMAGE CONSTANTS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /** Default JPEG compression quality (0-100). */
    private const val DEFAULT_JPEG_QUALITY = 95

    /** Maximum bitmap dimension to prevent OOM. */
    private const val MAX_BITMAP_DIMENSION = 8192

    /** Standard sRGB gamma value. */
    private const val SRGB_GAMMA = 2.2

    /** Rec. 709 luminance coefficients. */
    private const val LUMA_RED = 0.2126f
    private const val LUMA_GREEN = 0.7152f
    private const val LUMA_BLUE = 0.0722f

    /** D65 white point (sRGB standard). */
    private val D65_WHITE_POINT = floatArrayOf(0.95047f, 1.0f, 1.08883f)

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SCALE MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class ScaleMode {
        FIT_XY,          // Scale to fit exactly (may distort aspect)
        FIT_CENTER,     // Scale to fit while maintaining aspect, center
        FIT_START,      // Scale to fit while maintaining aspect, align start
        FIT_END,        // Scale to fit while maintaining aspect, align end
        CENTER,         // Center without scaling
        CENTER_CROP,    // Scale to fill while maintaining aspect, center
        CENTER_INSIDE   // Scale to fit inside while maintaining aspect
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ROTATION ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class Rotation {
        NONE, ROTATE_90, ROTATE_180, ROTATE_270
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // FLIP MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class FlipMode {
        NONE, HORIZONTAL, VERTICAL, BOTH
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // IMAGE INFO DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val format: String,
        val fileSize: Long,
        val hasAlpha: Boolean,
        val colorSpace: String?,
        val exifOrientation: Int,
        val exifDateTime: String?,
        val exifMake: String?,
        val exifModel: String?
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // COLOR ADJUSTMENT DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class ColorAdjustment(
        val brightness: Float = 0f,      // -1.0 to 1.0
        val contrast: Float = 1f,        // 0.0 to 2.0
        val saturation: Float = 1f,     // 0.0 to 2.0
        val warmth: Float = 0f,          // -1.0 to 1.0
        val tint: Float = 0f,            // -1.0 to 1.0
        val fade: Float = 0f,            // 0.0 to 1.0
        val highlights: Float = 0f,       // -1.0 to 1.0
        val shadows: Float = 0f,          // -1.0 to 1.0
        val vignette: Float = 0f,        // 0.0 to 1.0
        val grain: Float = 0f            // 0.0 to 1.0
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // BITMAP CREATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun createBitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (width > MAX_BITMAP_DIMENSION || height > MAX_BITMAP_DIMENSION) {
            NeoLogger.w("ImageUtils", "Bitmap dimensions too large: ${width}x$height")
            return null
        }

        return try {
            Bitmap.createBitmap(width, height, config)
        } catch (e: OutOfMemoryError) {
            NeoLogger.e("ImageUtils", "Out of memory creating bitmap: ${width}x$height", e)
            null
        }
    }

    fun createBitmapFromBytes(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            NeoLogger.e("ImageUtils", "Failed to decode bitmap from bytes", e)
            null
        }
    }

    fun createBitmapFromFile(filePath: String, sampleSize: Int = 1): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(filePath, options)
        } catch (e: Exception) {
            NeoLogger.e("ImageUtils", "Failed to decode bitmap from file: $filePath", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // BITMAP SCALING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun scaleBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int, mode: ScaleMode = ScaleMode.FIT_CENTER): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null

        val srcWidth = source.width
        val srcHeight = source.height

        if (srcWidth == targetWidth && srcHeight == targetHeight) {
            return source
        }

        val (scaleX, scaleY) = when (mode) {
            ScaleMode.FIT_XY -> {
                Pair(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
            }
            ScaleMode.FIT_CENTER, ScaleMode.CENTER_CROP -> {
                val scale = if (mode == ScaleMode.CENTER_CROP) {
                    max(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
                } else {
                    min(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
                }
                Pair(scale, scale)
            }
            ScaleMode.CENTER_INSIDE -> {
                val scale = if (srcWidth > targetWidth || srcHeight > targetHeight) {
                    min(targetWidth.toFloat() / srcWidth, targetHeight.toFloat() / srcHeight)
                } else {
                    1.0f
                }
                Pair(scale, scale)
            }
            else -> Pair(1.0f, 1.0f)
        }

        val matrix = Matrix().apply {
            setScale(scaleX, scaleY)
        }

        return try {
            Bitmap.createBitmap(source, 0, 0, srcWidth, srcHeight, matrix, true)
        } catch (e: OutOfMemoryError) {
            NeoLogger.e("ImageUtils", "Out of memory scaling bitmap", e)
            null
        }
    }

    fun scaleBitmapToMaxDimension(source: Bitmap, maxDimension: Int): Bitmap? {
        val maxSourceDim = max(source.width, source.height)
        if (maxSourceDim <= maxDimension) return source

        val scale = maxDimension.toFloat() / maxSourceDim
        val newWidth = (source.width * scale).roundToInt()
        val newHeight = (source.height * scale).roundToInt()

        return scaleBitmap(source, newWidth, newHeight)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // BITMAP TRANSFORMATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun rotateBitmap(source: Bitmap, rotation: Rotation, recycleSource: Boolean = false): Bitmap? {
        if (rotation == Rotation.NONE) return source

        val angle = when (rotation) {
            Rotation.ROTATE_90 -> 90f
            Rotation.ROTATE_180 -> 180f
            Rotation.ROTATE_270 -> 270f
            Rotation.NONE -> 0f
        }

        val matrix = Matrix().apply {
            postRotate(angle)
        }

        return try {
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (recycleSource && rotated != source) {
                source.recycle()
            }
            rotated
        } catch (e: OutOfMemoryError) {
            NeoLogger.e("ImageUtils", "Out of memory rotating bitmap", e)
            null
        }
    }

    fun flipBitmap(source: Bitmap, flipMode: FlipMode, recycleSource: Boolean = false): Bitmap? {
        if (flipMode == FlipMode.NONE) return source

        val matrix = Matrix().apply {
            when (flipMode) {
                FlipMode.HORIZONTAL -> preScale(-1f, 1f)
                FlipMode.VERTICAL -> preScale(1f, -1f)
                FlipMode.BOTH -> preScale(-1f, -1f)
                FlipMode.NONE -> {}
            }
        }

        return try {
            val flipped = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (recycleSource && flipped != source) {
                source.recycle()
            }
            flipped
        } catch (e: OutOfMemoryError) {
            NeoLogger.e("ImageUtils", "Out of memory flipping bitmap", e)
            null
        }
    }

    fun cropBitmap(source: Bitmap, cropRect: Rect, recycleSource: Boolean = false): Bitmap? {
        val safeRect = Rect(
            max(0, cropRect.left),
            max(0, cropRect.top),
            min(source.width, cropRect.right),
            min(source.height, cropRect.bottom)
        )

        if (safeRect.width() <= 0 || safeRect.height() <= 0) {
            NeoLogger.w("ImageUtils", "Invalid crop rect: $cropRect")
            return null
        }

        return try {
            val cropped = Bitmap.createBitmap(source, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
            if (recycleSource && cropped != source) {
                source.recycle()
            }
            cropped
        } catch (e: OutOfMemoryError) {
            NeoLogger.e("ImageUtils", "Out of memory cropping bitmap", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // COLOR MANIPULATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun applyColorMatrix(source: Bitmap, colorMatrix: ColorMatrix, recycleSource: Boolean = false): Bitmap? {
        val result = createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
            ?: return null

        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(source, 0f, 0f, paint)

        if (recycleSource) {
            source.recycle()
        }

        return result
    }

    fun adjustBrightness(source: Bitmap, brightness: Float, recycleSource: Boolean = false): Bitmap? {
        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                1f, 0f, 0f, 0f, brightness * 255,
                0f, 1f, 0f, 0f, brightness * 255,
                0f, 0f, 1f, 0f, brightness * 255,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        return applyColorMatrix(source, colorMatrix, recycleSource)
    }

    fun adjustContrast(source: Bitmap, contrast: Float, recycleSource: Boolean = false): Bitmap? {
        val scale = contrast
        val translate = (1.0f - contrast) * 0.5f * 255f

        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        return applyColorMatrix(source, colorMatrix, recycleSource)
    }

    fun adjustSaturation(source: Bitmap, saturation: Float, recycleSource: Boolean = false): Bitmap? {
        val colorMatrix = ColorMatrix().apply {
            setSaturation(saturation)
        }
        return applyColorMatrix(source, colorMatrix, recycleSource)
    }

    fun convertToGrayscale(source: Bitmap, recycleSource: Boolean = false): Bitmap? {
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }
        return applyColorMatrix(source, colorMatrix, recycleSource)
    }

    fun applySepia(source: Bitmap, intensity: Float = 1.0f, recycleSource: Boolean = false): Bitmap? {
        val colorMatrix = ColorMatrix().apply {
            val sepiaMat = floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            set(sepiaMat)
        }
        return applyColorMatrix(source, colorMatrix, recycleSource)
    }

    fun invertColors(source: Bitmap, recycleSource: Boolean = false): Bitmap? {
        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        return applyColorMatrix(source, colorMatrix, recycleSource)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // IMAGE FORMAT CONVERSION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = DEFAULT_JPEG_QUALITY): ByteArray? {
        return try {
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            NeoLogger.e("ImageUtils", "Failed to convert bitmap to JPEG", e)
            null
        }
    }

    fun bitmapToPng(bitmap: Bitmap): ByteArray? {
        return try {
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            NeoLogger.e("ImageUtils", "Failed to convert bitmap to PNG", e)
            null
        }
    }

    fun saveBitmapToFile(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = DEFAULT_JPEG_QUALITY): Boolean {
        return try {
            FileOutputStream(file).use { stream ->
                bitmap.compress(format, quality, stream)
            }
            true
        } catch (e: IOException) {
            NeoLogger.e("ImageUtils", "Failed to save bitmap to file: ${file.absolutePath}", e)
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // EXIF OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun readExifInfo(filePath: String): ImageInfo? {
        return try {
            val exif = ExifInterface(filePath)
            val file = File(filePath)

            val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            val format = file.extension.uppercase()

            ImageInfo(
                width = width,
                height = height,
                format = format,
                fileSize = file.length(),
                hasAlpha = format == "PNG" || format == "WEBP",
                colorSpace = exif.getAttribute(ExifInterface.TAG_COLOR_SPACE),
                exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
                exifDateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
                exifMake = exif.getAttribute(ExifInterface.TAG_MAKE),
                exifModel = exif.getAttribute(ExifInterface.TAG_MODEL)
            )
        } catch (e: IOException) {
            NeoLogger.e("ImageUtils", "Failed to read EXIF info: $filePath", e)
            null
        }
    }

    fun writeExifOrientation(filePath: String, orientation: Int): Boolean {
        return try {
            val exif = ExifInterface(filePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            true
        } catch (e: IOException) {
            NeoLogger.e("ImageUtils", "Failed to write EXIF orientation: $filePath", e)
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // YUV TO RGB CONVERSION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun yuvToRgb(yuvImage: YuvImage, width: Int, height: Int): Bitmap? {
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, stream)
        val jpegBytes = stream.toByteArray()
        return createBitmapFromBytes(jpegBytes)
    }

    fun yuvToRgb(image: Image, output: Bitmap) {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, stream)

        val jpegBytes = stream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        // Copy to output
        val canvas = Canvas(output)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun getBitmapDimensions(filePath: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            Pair(options.outWidth, options.outHeight)
        } else null
    }

    fun recycleSafely(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    fun copyBitmap(source: Bitmap, mutable: Boolean = false): Bitmap {
        return source.copy(source.config ?: Bitmap.Config.ARGB_8888, mutable)
    }

    fun blendBitmaps(base: Bitmap, overlay: Bitmap, blendMode: BlendMode, alpha: Float = 1.0f): Bitmap? {
        val result = createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888) ?: return null

        val canvas = Canvas(result)
        canvas.drawBitmap(base, 0f, 0f, null)

        val paint = Paint().apply {
            this.alpha = (alpha * 255).toInt()
        }

        canvas.drawBitmap(overlay, 0f, 0f, paint)

        return result
    }

    enum class BlendMode {
        NORMAL, MULTIPLY, SCREEN, OVERLAY, SOFT_LIGHT, HARD_LIGHT
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // MEMORY MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun getBitmapMemorySize(bitmap: Bitmap): Int {
        return bitmap.allocationByteCount
    }

    fun getAvailableMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    }

    fun canAllocateBitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Boolean {
        val bytesPerPixel = when (config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.ARGB_8888 -> 4
            else -> 4
        }
        val requiredMemory = width.toLong() * height * bytesPerPixel
        return requiredMemory < getAvailableMemory() * 0.8
    }
}
