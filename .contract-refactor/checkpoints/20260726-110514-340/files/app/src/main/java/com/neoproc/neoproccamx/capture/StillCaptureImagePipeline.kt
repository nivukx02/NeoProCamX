package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.neoproc.neoproccamx.ai.AIProcessor
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.pow

internal class StillCaptureImagePipeline(
    private val cameraController: CameraController,
    private val colorScience: ColorScience,
    private val computationalProcessor: AIProcessor,
    private val outputStore: StillCaptureOutputStore,
    private val rawCaptureProcessor: StillRawCaptureProcessor
) {
    companion object {
        private const val TAG = "StillCaptureImagePipeline"
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private val singleCaptureProcessor = StillSingleCaptureProcessor(
        outputStore = outputStore,
        rawCaptureProcessor = rawCaptureProcessor,
        requiresAspectCrop = ::requiresAspectCrop,
        decodeOrientedBitmap = ::decodeOrientedBitmap,
        cropBitmapToSelectedAspect = ::cropBitmapToSelectedAspect,
        applyColorPipeline = ::applyColorPipeline
    )
    fun shouldPostProcessSingleCapture(): Boolean =
        // A neutral capture is already the camera HAL's highest-quality JPEG. Decoding it to an
        // 8-bit Bitmap and recompressing merely because strict mode is enabled adds generational
        // loss and memory pressure. Aspect crops are still detected independently, and an active
        // color-science look still takes the full processing route.
        !colorScience.isNeutralLook()

    fun requiresAspectCrop(): Boolean =
        StillCaptureImageMath.requiresAspectCrop(
            outputAspect = cameraController.outputAspectRatio,
            sensorAspect = cameraController.stillOutputAspectRatio()
        )

    suspend fun processSingleCapture(
        capture: ActiveStillCapture,
        jpegBytes: ByteArray?
    ): StillSingleCaptureResult = singleCaptureProcessor.process(capture, jpegBytes)

    suspend fun processHdrCapture(
        capture: ActiveStillCapture,
        jpegFrames: List<ByteArray>
    ): String? {
        if (!capture.debugLabel.isNullOrBlank()) {
            return saveFallbackJpeg(capture, jpegFrames.firstOrNull())
        }
        if (jpegFrames.size < 2) return saveFallbackJpeg(capture, jpegFrames.firstOrNull())
        val decoded = jpegFrames.mapNotNull(::decodeOrientedBitmap)
        val frames = decoded.map(::cropBitmapToSelectedAspect)
        // Recycle the pre-crop originals that a DISTINCT cropped bitmap replaced. When a non-native
        // output aspect is selected, cropBitmapToSelectedAspect returns a new bitmap and the full-res
        // original (~48MB ARGB_8888 at 12MP) was previously leaked for every bracket frame. When no crop
        // is applied it returns the same instance (!== guard), so nothing is double-recycled.
        decoded.forEachIndexed { index, original ->
            if (frames.getOrNull(index) !== original && !original.isRecycled) {
                original.recycle()
            }
        }
        if (frames.size < 2) {
            recycleBitmaps(frames)
            return saveFallbackJpeg(capture, jpegFrames.firstOrNull())
        }

        var merged: Bitmap? = null
        var processed: Bitmap? = null
        return try {
            val mergedBitmap = mergeHdrFrames(
                frames = frames,
                requestedBracket = capture.hdrBracketEvs,
                hdrMode = capture.hdrMode
            ) ?: return saveFallbackJpeg(capture, jpegFrames.firstOrNull())
            merged = mergedBitmap
            val processedBitmap = applyColorPipeline(mergedBitmap)
            processed = processedBitmap
            outputStore.saveBitmapAsJpeg(
                bitmap = processedBitmap,
                tag = capture.primaryTag,
                sourceExifBytes = jpegFrames.firstOrNull(),
                debugLabel = capture.debugLabel
            )
        } catch (e: Exception) {
            Log.w(TAG, "processHdrCapture failed, falling back to source JPEG", e)
            saveFallbackJpeg(capture, jpegFrames.firstOrNull())
        } finally {
            recycleBitmaps(frames.map { it as Bitmap? } + listOf(merged, processed))
        }
    }

    private suspend fun applyColorPipeline(bitmap: Bitmap): Bitmap {
        if (colorScience.isNeutralLook()) return bitmap
        return colorScience.applyToBitmap(bitmap)
    }

    private fun decodeOrientedBitmap(bytes: ByteArray): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val exif = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return decoded

        val rotation = when (
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return decoded

        val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return rotated
    }

    private fun cropBitmapToSelectedAspect(bitmap: Bitmap): Bitmap {
        val crop = StillCaptureImageMath.resolveCenteredAspectCrop(
            width = bitmap.width,
            height = bitmap.height,
            targetAspect = cameraController.outputAspectRatio
        ) ?: return bitmap
        return Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
    }

    private suspend fun mergeHdrFrames(
        frames: List<Bitmap>,
        requestedBracket: List<Int>,
        hdrMode: String
    ): Bitmap? {
        val bracket = resolveHdrBracketForFrameCount(
            frameCount = frames.size,
            requestedBracket = requestedBracket
        )
        val exposureScales = FloatArray(bracket.size) { index ->
            2.0.pow((-bracket[index]).toDouble()).toFloat()
        }
        val normalizationFactors = FloatArray(bracket.size) { index ->
            (1f / (1f + abs(bracket[index]).toFloat() * 0.35f)).coerceIn(0.30f, 1f)
        }

        val nativeMerged = HdrMergeNative.mergeWithQuality(
            frames = frames,
            exposureScales = exposureScales,
            normalizationFactors = normalizationFactors,
            quality = resolveHdrMergeQuality(hdrMode)
        )
        return nativeMerged ?: computationalProcessor.stackHDR(frames)
    }

    private fun resolveHdrMergeQuality(hdrMode: String): HdrMergeNative.HdrMergeQuality =
        when {
            hdrMode.contains("FAST", ignoreCase = true) ->
                if (strictHighEndEnabled()) {
                    HdrMergeNative.HdrMergeQuality.BALANCED
                } else {
                    HdrMergeNative.HdrMergeQuality.FAST
                }

            hdrMode.contains("ULTRA", ignoreCase = true) ->
                HdrMergeNative.HdrMergeQuality.ULTRA_QUALITY

            hdrMode.contains("MAX", ignoreCase = true) ||
                hdrMode.contains("CINEMA", ignoreCase = true) ->
                HdrMergeNative.HdrMergeQuality.CINEMA_QUALITY

            else -> if (strictHighEndEnabled()) {
                HdrMergeNative.HdrMergeQuality.CINEMA_QUALITY
            } else {
                HdrMergeNative.HdrMergeQuality.ULTRA_QUALITY
            }
        }

    private fun resolveHdrBracketForFrameCount(
        frameCount: Int,
        requestedBracket: List<Int>
    ): List<Int> {
        if (frameCount <= 0) return emptyList()
        val fallbackBracket = buildCenterOutBracket(frameCount)
        return (requestedBracket + fallbackBracket)
            .map { it.coerceIn(-4, 4) }
            .distinct()
            .take(frameCount)
    }

    private fun buildCenterOutBracket(frameCount: Int): List<Int> {
        if (frameCount <= 0) return emptyList()
        return buildList(frameCount) {
            add(0)
            var ev = 1
            while (size < frameCount) {
                add(-ev)
                if (size < frameCount) add(ev)
                ev += 1
            }
        }
    }

    private fun saveFallbackJpeg(capture: ActiveStillCapture, sourceJpegBytes: ByteArray?): String? {
        sourceJpegBytes ?: return null
        return outputStore.saveJpegBytes(
            jpegBytes = sourceJpegBytes,
            tag = capture.primaryTag,
            debugLabel = capture.debugLabel
        )
    }

    private fun recycleBitmaps(bitmaps: Collection<Bitmap?>) {
        val seen = HashSet<Int>(bitmaps.size)
        bitmaps.forEach { bitmap ->
            if (bitmap == null || bitmap.isRecycled) return@forEach
            val identity = System.identityHashCode(bitmap)
            if (seen.add(identity)) {
                bitmap.recycle()
            }
        }
    }
}
