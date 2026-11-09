package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.neoproc.neoproccamx.ai.AIProcessor
import com.neoproc.neoproccamx.ai.EnhancementEngine
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.EliteDetailProfilePolicy
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.pow

internal object StillSingleEnhancementPolicy {
    /**
     * A neutral HAL JPEG is already spatially denoised and sharpened by the ISP. Re-running the
     * heavyweight JVM refinement solely because strict mode is active adds heat, latency, and
     * generational loss after an aspect crop. Explicit AI still enhancement remains honored, while
     * strict non-neutral NDR/LOG/creative looks keep their existing refinement path.
     */
    fun shouldEnhance(
        aiEnhancementEnabled: Boolean,
        strictHighEndEnabled: Boolean,
        neutralLook: Boolean
    ): Boolean = aiEnhancementEnabled || (strictHighEndEnabled && !neutralLook)
}

internal class StillCaptureImagePipeline(
    private val cameraController: CameraController,
    private val colorScience: ColorScience,
    private val computationalProcessor: AIProcessor,
    private val outputStore: StillCaptureOutputStore,
    private val rawCaptureProcessor: StillRawCaptureProcessor,
    private val enhancementEngine: EnhancementEngine,
    private val aiEnhancementEnabledProvider: () -> Boolean,
    private val aiSceneTypeProvider: () -> SceneType,
    private val videoDetailProfileProvider: () -> VideoDetailProfile
) {
    companion object {
        private const val TAG = "StillCaptureImagePipeline"
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    /**
     * Still rendering is deliberately independent from the live AI switch. The product switch keeps
     * the continuous preview analyzer off for frame-rate stability, but a photo that already needs an
     * app render (NDR bracket fusion, LOG/color science, or an aspect crop) can safely use the bounded
     * scene-aware refinement pass. A neutral, uncropped HAL JPEG still takes the lossless fast-save
     * route below, and a native DNG never enters this bitmap pipeline.
     */
    private fun stillEnhancementRequested(): Boolean =
        aiEnhancementEnabledProvider() || strictHighEndEnabled()

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
        !colorScience.isNeutralLook() || aiEnhancementEnabledProvider()

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
        if (jpegFrames.size < 2) return handleHdrProcessingFailure(capture, jpegFrames.firstOrNull())
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
            return handleHdrProcessingFailure(capture, jpegFrames.firstOrNull())
        }

        var merged: Bitmap? = null
        var processed: Bitmap? = null
        return try {
            val mergedBitmap = mergeHdrFrames(
                frames = frames,
                requestedBracket = capture.hdrBracketEvs,
                hdrMode = capture.hdrMode
            ) ?: return handleHdrProcessingFailure(capture, jpegFrames.firstOrNull())
            merged = mergedBitmap
            val processedBitmap = applyEnhancementAndColor(
                bitmap = mergedBitmap,
                enhancementRequested = stillEnhancementRequested()
            )
            processed = processedBitmap
            outputStore.saveBitmapAsJpeg(
                bitmap = processedBitmap,
                tag = capture.primaryTag,
                sourceExifBytes = jpegFrames.firstOrNull(),
                debugLabel = capture.debugLabel
            )
        } catch (e: Exception) {
            Log.e(TAG, "processHdrCapture failed; requested HDR output rejected", e)
            handleHdrProcessingFailure(capture, jpegFrames.firstOrNull())
        } finally {
            recycleBitmaps(frames.map { it as Bitmap? } + listOf(merged, processed))
        }
    }

    private suspend fun applyColorPipeline(bitmap: Bitmap): Bitmap {
        return applyEnhancementAndColor(
            bitmap = bitmap,
            enhancementRequested = StillSingleEnhancementPolicy.shouldEnhance(
                aiEnhancementEnabled = aiEnhancementEnabledProvider(),
                strictHighEndEnabled = strictHighEndEnabled(),
                neutralLook = colorScience.isNeutralLook()
            )
        )
    }

    private suspend fun applyEnhancementAndColor(
        bitmap: Bitmap,
        enhancementRequested: Boolean
    ): Bitmap {
        val enhanced = if (enhancementRequested) {
            val detailTuning = EliteDetailProfilePolicy.resolve(videoDetailProfileProvider())
            val enhancementStartedNs = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("NeoStill.enhancement")
            val enhancement = try {
                enhancementEngine.enhance(
                    bitmap = bitmap,
                    sceneType = aiSceneTypeProvider(),
                    config = EnhancementEngine.EnhancementConfig(
                        // Profile intent is adapted from measured image noise and scene type. Native-size
                        // tiles preserve source resolution and the enhancer's luma halo guard remains final.
                        denoiseStrength = detailTuning.stillDenoise,
                        sharpenStrength = detailTuning.stillSharpen,
                        microContrastStrength = detailTuning.stillMicroContrast,
                        residualChromaSuppression = detailTuning.stillResidualChromaSuppression,
                        adaptiveByScene = true,
                        temporalDenoiseWeight = 0f
                    )
                )
            } finally {
                Trace.endSection()
            }
            Log.i(
                TAG,
                "Still enhancement ${bitmap.width}x${bitmap.height} " +
                    "wallMs=${(SystemClock.elapsedRealtimeNanos() - enhancementStartedNs) / 1_000_000.0} " +
                    "engineMs=${enhancement.second.processingTimeMs} path=${enhancement.second.denoisePath} " +
                    "denoise=${enhancement.second.denoisingApplied} " +
                    "sharpen=${enhancement.second.sharpeningApplied} " +
                    "microContrast=${enhancement.second.microContrastApplied}"
            )
            enhancement.first.also { result ->
                check(result.width == bitmap.width && result.height == bitmap.height) {
                    "Still enhancement changed source resolution " +
                        "${bitmap.width}x${bitmap.height} -> ${result.width}x${result.height}"
                }
            }
        } else {
            bitmap
        }
        if (colorScience.isNeutralLook()) return enhanced
        val colorProcessed = colorScience.applyToBitmap(enhanced)
        check(colorProcessed.width == bitmap.width && colorProcessed.height == bitmap.height) {
            "Still color pipeline changed source resolution " +
                "${bitmap.width}x${bitmap.height} -> " +
                "${colorProcessed.width}x${colorProcessed.height}"
        }
        if (colorProcessed !== enhanced && enhanced !== bitmap && !enhanced.isRecycled) enhanced.recycle()
        return colorProcessed
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

    private fun handleHdrProcessingFailure(
        capture: ActiveStillCapture,
        sourceJpegBytes: ByteArray?
    ): String? {
        // Debug automation deliberately requests source artifacts to inspect capture transport.
        // Production strict mode must never label a single source exposure as a completed HDR photo.
        return if (!capture.debugLabel.isNullOrBlank() || !strictHighEndEnabled()) {
            saveFallbackJpeg(capture, sourceJpegBytes)
        } else {
            null
        }
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
