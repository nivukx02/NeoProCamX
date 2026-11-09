package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class StillSingleCaptureResult(
    val primarySavedPath: String?,
    val rawCompanionPath: String?
)

internal data class StillSingleCapturePlan(
    val outputTag: String,
    val requiresBitmapProcessing: Boolean
) {
    val canFastSaveJpeg: Boolean
        get() = !requiresBitmapProcessing
}

internal object StillSingleCapturePolicy {
    fun resolvePlan(
        capture: ActiveStillCapture,
        requiresAspectCrop: Boolean
    ): StillSingleCapturePlan =
        StillSingleCapturePlan(
            outputTag = capture.primaryTag,
            requiresBitmapProcessing = capture.processSingleBitmap || requiresAspectCrop
        )
}

internal class StillSingleCaptureProcessor(
    private val outputStore: StillCaptureOutputStore,
    private val rawCaptureProcessor: StillRawCaptureProcessor,
    private val requiresAspectCrop: () -> Boolean,
    private val decodeOrientedBitmap: (ByteArray) -> Bitmap?,
    private val cropBitmapToSelectedAspect: (Bitmap) -> Bitmap,
    private val applyColorPipeline: suspend (Bitmap) -> Bitmap
) {
    companion object {
        private const val FAST_SAVE_JPEG_THRESHOLD_BYTES = 12 * 1024 * 1024
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    suspend fun process(
        capture: ActiveStillCapture,
        jpegBytes: ByteArray?
    ): StillSingleCaptureResult {
        jpegBytes ?: return StillSingleCaptureResult(primarySavedPath = null, rawCompanionPath = null)
        val preserveExifMetadata = !isHighThroughputDebugCapture(capture.debugLabel)
        val requiresCrop = requiresAspectCrop()
        val plan = StillSingleCapturePolicy.resolvePlan(
            capture = capture,
            requiresAspectCrop = requiresCrop
        )
        val allowFastSave = !strictHighEndEnabled()
        val forceFastSave =
            capture.processSingleBitmap &&
                !requiresCrop &&
                allowFastSave &&
                jpegBytes.size >= FAST_SAVE_JPEG_THRESHOLD_BYTES
        if (plan.canFastSaveJpeg || forceFastSave) {
            val primarySavedPath = outputStore.saveJpegBytes(
                jpegBytes = jpegBytes,
                tag = plan.outputTag,
                debugLabel = capture.debugLabel,
                preserveExifMetadata = preserveExifMetadata
            )
            return StillSingleCaptureResult(
                primarySavedPath = primarySavedPath,
                rawCompanionPath = buildSoftwareRawFallbackPathIfNeeded(
                    capture = capture,
                    sourceJpegBytes = jpegBytes,
                    renderedReference = null
                )
            )
        }

        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var processed: Bitmap? = null
        return try {
            decoded = decodeOrientedBitmap(jpegBytes)
                ?: return StillSingleCaptureResult(
                    primarySavedPath = outputStore.saveJpegBytes(
                        jpegBytes = jpegBytes,
                        tag = plan.outputTag,
                        debugLabel = capture.debugLabel,
                        preserveExifMetadata = preserveExifMetadata
                    ),
                    rawCompanionPath = buildSoftwareRawFallbackPathIfNeeded(
                        capture = capture,
                        sourceJpegBytes = jpegBytes,
                        renderedReference = null
                    )
                )
            cropped = cropBitmapToSelectedAspect(decoded)
            processed = applyColorPipeline(cropped)
            val primarySavedPath = outputStore.saveBitmapAsJpeg(
                bitmap = processed,
                tag = plan.outputTag,
                sourceExifBytes = jpegBytes,
                debugLabel = capture.debugLabel
            )
            StillSingleCaptureResult(
                primarySavedPath = primarySavedPath,
                rawCompanionPath = buildSoftwareRawFallbackPathIfNeeded(
                    capture = capture,
                    sourceJpegBytes = jpegBytes,
                    renderedReference = processed
                )
            )
        } catch (_: Exception) {
            StillSingleCaptureResult(
                primarySavedPath = outputStore.saveJpegBytes(
                    jpegBytes = jpegBytes,
                    tag = plan.outputTag,
                    debugLabel = capture.debugLabel,
                    preserveExifMetadata = preserveExifMetadata
                ),
                rawCompanionPath = buildSoftwareRawFallbackPathIfNeeded(
                    capture = capture,
                    sourceJpegBytes = jpegBytes,
                    renderedReference = null
                )
            )
        } finally {
            recycleDistinctBitmaps(decoded, cropped, processed)
        }
    }

    private fun buildSoftwareRawFallbackPathIfNeeded(
        capture: ActiveStillCapture,
        sourceJpegBytes: ByteArray,
        renderedReference: Bitmap?
    ): String? {
        if (capture.singleCaptureRawMode != StillCaptureCapturePolicy.SingleCaptureRawMode.SOFTWARE_RAW_FALLBACK) {
            return null
        }
        return rawCaptureProcessor.saveSoftwareRawFallbackPackage(
            tag = capture.rawCompanionTag ?: capture.primaryTag,
            sourceJpegBytes = sourceJpegBytes,
            renderedReference = renderedReference,
            debugLabel = capture.debugLabel
        )
    }

    private fun recycleDistinctBitmaps(vararg bitmaps: Bitmap?) {
        val recycled = mutableSetOf<Bitmap>()
        bitmaps.forEach { bitmap ->
            if (bitmap != null && recycled.add(bitmap) && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun isHighThroughputDebugCapture(debugLabel: String?): Boolean {
        val normalizedLabel = debugLabel?.trim()?.lowercase() ?: return false
        return normalizedLabel.startsWith("qa_stress_") || normalizedLabel.startsWith("qa_longrun_")
    }
}
