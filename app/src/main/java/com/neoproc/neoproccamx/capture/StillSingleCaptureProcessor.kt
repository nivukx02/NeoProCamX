package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Trace
import android.util.Log
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
        private const val TAG = "StillSingleProcessor"
        private const val FAST_SAVE_JPEG_THRESHOLD_BYTES = 12 * 1024 * 1024
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    suspend fun process(
        capture: ActiveStillCapture,
        jpegBytes: ByteArray?
    ): StillSingleCaptureResult {
        jpegBytes ?: return StillSingleCaptureResult(primarySavedPath = null, rawCompanionPath = null)
        val totalStartedNs = SystemClock.elapsedRealtimeNanos()
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
            val primarySavedPath = traceSection("NeoStill.fastSave") {
                outputStore.saveJpegBytes(
                    jpegBytes = jpegBytes,
                    tag = plan.outputTag,
                    debugLabel = capture.debugLabel,
                    preserveExifMetadata = preserveExifMetadata
                )
            }
            Log.i(
                TAG,
                "Still timing captureId=${capture.id} route=fast inputBytes=${jpegBytes.size} " +
                    "totalMs=${elapsedMs(totalStartedNs)} saved=${!primarySavedPath.isNullOrEmpty()}"
            )
            return StillSingleCaptureResult(
                primarySavedPath = primarySavedPath,
                rawCompanionPath = buildProcessedSourceFallbackPathIfNeeded(
                    capture = capture,
                    sourceJpegBytes = jpegBytes,
                    renderedReference = null
                )
            )
        }

        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var processed: Bitmap? = null
        var decodeMs = 0.0
        var cropMs = 0.0
        var colorMs = 0.0
        var saveMs = 0.0
        var fallback = false
        return try {
            var stageStartedNs = SystemClock.elapsedRealtimeNanos()
            decoded = traceSection("NeoStill.decodeRotate") { decodeOrientedBitmap(jpegBytes) }
            decodeMs = elapsedMs(stageStartedNs)
            stageStartedNs = SystemClock.elapsedRealtimeNanos()
            if (decoded == null) fallback = true
            decoded
                ?: return StillSingleCaptureResult(
                    // Bitmap processing was explicitly required (look, enhancement, or crop).
                    // Publishing the untouched source would silently misrepresent the requested
                    // result, so strict processing failures remain visible and fail closed.
                    primarySavedPath = null,
                    rawCompanionPath = buildProcessedSourceFallbackPathIfNeeded(
                        capture = capture,
                        sourceJpegBytes = jpegBytes,
                        renderedReference = null
                    )
                )
            cropped = traceSection("NeoStill.aspectCrop") { cropBitmapToSelectedAspect(decoded) }
            cropMs = elapsedMs(stageStartedNs)
            stageStartedNs = SystemClock.elapsedRealtimeNanos()
            Trace.beginSection("NeoStill.colorPipeline")
            try {
                processed = applyColorPipeline(cropped)
            } finally {
                Trace.endSection()
            }
            colorMs = elapsedMs(stageStartedNs)
            stageStartedNs = SystemClock.elapsedRealtimeNanos()
            val primarySavedPath = traceSection("NeoStill.bitmapSave") {
                outputStore.saveBitmapAsJpeg(
                    bitmap = processed,
                    tag = plan.outputTag,
                    sourceExifBytes = jpegBytes,
                    debugLabel = capture.debugLabel
                )
            }
            saveMs = elapsedMs(stageStartedNs)
            StillSingleCaptureResult(
                primarySavedPath = primarySavedPath,
                rawCompanionPath = buildProcessedSourceFallbackPathIfNeeded(
                    capture = capture,
                    sourceJpegBytes = jpegBytes,
                    renderedReference = processed
                )
            )
        } catch (error: Exception) {
            fallback = true
            Log.e(TAG, "Still bitmap processing failed captureId=${capture.id}; output rejected", error)
            StillSingleCaptureResult(
                primarySavedPath = null,
                rawCompanionPath = buildProcessedSourceFallbackPathIfNeeded(
                    capture = capture,
                    sourceJpegBytes = jpegBytes,
                    renderedReference = null
                )
            )
        } finally {
            Log.i(
                TAG,
                "Still timing captureId=${capture.id} route=bitmap inputBytes=${jpegBytes.size} " +
                    "decodeMs=$decodeMs cropMs=$cropMs colorMs=$colorMs saveMs=$saveMs " +
                    "totalMs=${elapsedMs(totalStartedNs)} fallback=$fallback"
            )
            recycleDistinctBitmaps(decoded, cropped, processed)
        }
    }

    private fun buildProcessedSourceFallbackPathIfNeeded(
        capture: ActiveStillCapture,
        sourceJpegBytes: ByteArray,
        renderedReference: Bitmap?
    ): String? {
        if (capture.singleCaptureRawMode != StillCaptureCapturePolicy.SingleCaptureRawMode.PROCESSED_SOURCE_FALLBACK) {
            return null
        }
        return rawCaptureProcessor.saveProcessedSourceFallbackPackage(
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

    private inline fun <T> traceSection(name: String, block: () -> T): T {
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    private fun elapsedMs(startedNs: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
}
