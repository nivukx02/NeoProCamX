package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal object HdrMergeNative {

    private const val TAG = "HdrMergeNative"

    private val nativeLibraryLoaded: Boolean = runCatching {
        System.loadLibrary("neoproc_vulkan")
        true
    }.onFailure { throwable ->
        Log.w(TAG, "neoproc_vulkan unavailable; HDR merge will use software fallback", throwable)
    }.getOrDefault(false)

    enum class HdrMergeQuality {
        FAST,           // Fast approximation for preview
        BALANCED,       // Balanced quality/speed
        CINEMA_QUALITY, // High-end cinema quality
        ULTRA_QUALITY   // Maximum quality with noise reduction
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun isAvailable(): Boolean = nativeLibraryLoaded

    fun merge(
        frames: List<Bitmap>,
        exposureScales: FloatArray,
        normalizationFactors: FloatArray,
        fastApproximation: Boolean
    ): Bitmap? {
        val quality = when {
            fastApproximation -> HdrMergeQuality.FAST
            strictHighEndEnabled() -> HdrMergeQuality.CINEMA_QUALITY
            else -> HdrMergeQuality.BALANCED
        }
        return mergeWithQuality(frames, exposureScales, normalizationFactors, quality)
    }

    fun mergeWithQuality(
        frames: List<Bitmap>,
        exposureScales: FloatArray,
        normalizationFactors: FloatArray,
        quality: HdrMergeQuality = HdrMergeQuality.CINEMA_QUALITY
    ): Bitmap? {
        if (!nativeLibraryLoaded) return null
        if (frames.isEmpty()) return null
        if (frames.size != exposureScales.size || frames.size != normalizationFactors.size) {
            Log.w(TAG, "HDR merge input mismatch frames=${frames.size} exposure=${exposureScales.size} normalization=${normalizationFactors.size}")
            return null
        }
        val width = frames.first().width
        val height = frames.first().height
        if (width <= 0 || height <= 0) return null
        if (frames.any { it.width != width || it.height != height }) {
            Log.w(TAG, "HDR merge requires same-size frames")
            return null
        }
        return runCatching {
            val resolvedQuality = if (strictHighEndEnabled() && quality == HdrMergeQuality.BALANCED) {
                HdrMergeQuality.CINEMA_QUALITY
            } else {
                quality
            }
            val useFastMode = resolvedQuality == HdrMergeQuality.FAST
            val useDenoising = resolvedQuality == HdrMergeQuality.ULTRA_QUALITY
            val useAdvancedAlignment =
                resolvedQuality in listOf(HdrMergeQuality.CINEMA_QUALITY, HdrMergeQuality.ULTRA_QUALITY)
            
            val pixels = nativeMergeHdrBitmapsAdvanced(
                bitmaps = frames.toTypedArray(),
                exposureScales = exposureScales,
                normalizationFactors = normalizationFactors,
                fastApproximation = useFastMode,
                enableDenoising = useDenoising,
                enableAdvancedAlignment = useAdvancedAlignment,
                qualityLevel = resolvedQuality.ordinal
            ) ?: return null
            if (pixels.size != width * height) {
                Log.w(TAG, "HDR merge native output size mismatch ${pixels.size} != ${width * height}")
                return null
            }
            createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Native Vulkan HDR merge failed", throwable)
        }.getOrNull()
    }

    @JvmStatic
    private external fun nativeMergeHdrBitmaps(
        bitmaps: Array<Bitmap>,
        exposureScales: FloatArray,
        normalizationFactors: FloatArray,
        fastApproximation: Boolean
    ): IntArray?

    @JvmStatic
    private external fun nativeMergeHdrBitmapsAdvanced(
        bitmaps: Array<Bitmap>,
        exposureScales: FloatArray,
        normalizationFactors: FloatArray,
        fastApproximation: Boolean,
        enableDenoising: Boolean,
        enableAdvancedAlignment: Boolean,
        qualityLevel: Int
    ): IntArray?
}
