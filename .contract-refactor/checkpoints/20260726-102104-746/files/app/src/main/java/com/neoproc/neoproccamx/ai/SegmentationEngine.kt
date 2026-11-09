package com.neoproc.neoproccamx.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * HIGH-END SEGMENTATION ENGINE - PROFESSIONAL cinema-oriented SEGMENTATION (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * FEATURES:
 * - Multi-class segmentation support
 * - Edge refinement for clean masks
 * - Temporal coherence (smooth mask across frames)
 * - Confidence-weighted blending
 * - Integration with depth for better separation
 * - Professional status reporting
 *
 * PERFORMANCE TARGET: 20+ FPS
 */
class SegmentationEngine(context: Context) : AutoCloseable {

    data class LumaGuide(
        val values: FloatArray,
        val width: Int,
        val height: Int
    )

    data class TemporalBlendResult(
        val mask: FloatArray,
        val meanMotion: Float,
        val meanHistoryWeight: Float
    )

    data class SkinChromaFallbackResult(
        val mask: FloatArray,
        val confidence: FloatArray,
        val approximateDepth: FloatArray,
        val coverage: Float
    )

    companion object {
        private const val TAG = "SegmentationEngine"
        val MULTICLASS_LABELS = listOf(
            "Background",
            "Hair",
            "Body / Skin",
            "Face / Skin",
            "Clothes",
            "Person"
        )
        private val MODEL_ASSET_CANDIDATES = listOf(
            AIConstants.MODEL_SEGMENTATION_PRIMARY_ASSET,
            "models/segmentation.tflite",
            "segmentation.tflite"
        )
        private const val TEMPORAL_SMOOTHING_WEIGHT = AIStrictHighEndConfig.SEGMENTATION_TEMPORAL_WEIGHT
        private const val EDGE_REFINEMENT_KERNEL = AIStrictHighEndConfig.SEGMENTATION_EDGE_KERNEL
        private const val CONFIDENCE_THRESHOLD = AIStrictHighEndConfig.SEGMENTATION_CONFIDENCE_THRESHOLD

        fun guidedFilterMaskRefine(
            lowMask: FloatArray,
            lowWidth: Int,
            lowHeight: Int,
            lumaGuide: FloatArray,
            guideWidth: Int,
            guideHeight: Int,
            radius: Int = 2,
            epsilon: Float = 1.0e-4f
        ): FloatArray {
            require(lowWidth > 0 && lowHeight > 0) { "Low-resolution mask dimensions must be positive" }
            require(guideWidth > 0 && guideHeight > 0) { "Guide dimensions must be positive" }
            require(lowMask.size >= lowWidth * lowHeight) { "Low-resolution mask buffer too small" }
            require(lumaGuide.size >= guideWidth * guideHeight) { "Luma guide buffer too small" }

            val baseMask = FloatArray(guideWidth * guideHeight)
            for (y in 0 until guideHeight) {
                val sourceY = if (guideHeight == 1) 0f else y * (lowHeight - 1f) / (guideHeight - 1f)
                for (x in 0 until guideWidth) {
                    val sourceX = if (guideWidth == 1) 0f else x * (lowWidth - 1f) / (guideWidth - 1f)
                    baseMask[y * guideWidth + x] = sampleBilinear(lowMask, lowWidth, lowHeight, sourceX, sourceY)
                }
            }

            val r = radius.coerceAtLeast(1)
            val a = FloatArray(baseMask.size)
            val b = FloatArray(baseMask.size)
            for (y in 0 until guideHeight) {
                for (x in 0 until guideWidth) {
                    var sumI = 0f
                    var sumP = 0f
                    var sumIp = 0f
                    var sumIi = 0f
                    var count = 0
                    for (dy in -r..r) {
                        val yy = (y + dy).coerceIn(0, guideHeight - 1)
                        for (dx in -r..r) {
                            val xx = (x + dx).coerceIn(0, guideWidth - 1)
                            val nIdx = yy * guideWidth + xx
                            val guide = lumaGuide[nIdx].coerceIn(0f, 1f)
                            val mask = baseMask[nIdx].coerceIn(0f, 1f)
                            sumI += guide
                            sumP += mask
                            sumIp += guide * mask
                            sumIi += guide * guide
                            count++
                        }
                    }
                    val invCount = 1f / count.coerceAtLeast(1)
                    val meanI = sumI * invCount
                    val meanP = sumP * invCount
                    val covIp = sumIp * invCount - meanI * meanP
                    val varI = sumIi * invCount - meanI * meanI
                    val idx = y * guideWidth + x
                    a[idx] = covIp / (varI + epsilon)
                    b[idx] = meanP - a[idx] * meanI
                }
            }

            val refined = FloatArray(baseMask.size)
            for (y in 0 until guideHeight) {
                for (x in 0 until guideWidth) {
                    var sumA = 0f
                    var sumB = 0f
                    var count = 0
                    for (dy in -r..r) {
                        val yy = (y + dy).coerceIn(0, guideHeight - 1)
                        for (dx in -r..r) {
                            val xx = (x + dx).coerceIn(0, guideWidth - 1)
                            val nIdx = yy * guideWidth + xx
                            sumA += a[nIdx]
                            sumB += b[nIdx]
                            count++
                        }
                    }
                    val idx = y * guideWidth + x
                    refined[idx] = ((sumA / count) * lumaGuide[idx].coerceIn(0f, 1f) + (sumB / count))
                        .coerceIn(0f, 1f)
                }
            }
            return refined
        }

        fun motionAdaptiveBackwardWarpTemporalBlend(
            currentMask: FloatArray,
            previousMask: FloatArray?,
            width: Int,
            height: Int,
            flowX: FloatArray? = null,
            flowY: FloatArray? = null,
            baseAlpha: Float = 0.85f,
            beta: Float = 0.5f
        ): TemporalBlendResult {
            require(width > 0 && height > 0) { "Mask dimensions must be positive" }
            require(currentMask.size >= width * height) { "Current mask buffer too small" }
            if (previousMask == null || previousMask.size < width * height) {
                return TemporalBlendResult(currentMask.copyOf(width * height), 0f, 0f)
            }
            val out = FloatArray(width * height)
            var motionSum = 0f
            var weightSum = 0f
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val vx = flowX?.getOrNull(idx) ?: 0f
                    val vy = flowY?.getOrNull(idx) ?: 0f
                    val motion = sqrt(vx * vx + vy * vy)
                    val prevX = x - vx
                    val prevY = y - vy
                    val inside = prevX >= 0f && prevY >= 0f && prevX <= width - 1f && prevY <= height - 1f
                    val historyWeight = if (inside) {
                        baseAlpha.coerceIn(0f, 1f) * exp(-beta * motion * motion)
                    } else {
                        0f
                    }
                    val previous = if (inside) sampleBilinear(previousMask, width, height, prevX, prevY) else 0f
                    val current = currentMask[idx].coerceIn(0f, 1f)
                    out[idx] = (current * (1f - historyWeight) + previous * historyWeight).coerceIn(0f, 1f)
                    motionSum += motion
                    weightSum += historyWeight
                }
            }
            val count = (width * height).coerceAtLeast(1)
            return TemporalBlendResult(out, motionSum / count, weightSum / count)
        }

        fun skinChromaMahalanobisFallback(
            cb: FloatArray,
            cr: FloatArray,
            width: Int,
            height: Int,
            lastCenterX: Float = 0.5f,
            lastCenterY: Float = 0.5f,
            sigmaGate: Float = 0.32f
        ): SkinChromaFallbackResult {
            require(width > 0 && height > 0) { "Fallback dimensions must be positive" }
            require(cb.size >= width * height && cr.size >= width * height) { "Chroma buffers too small" }
            val count = width * height
            val mask = FloatArray(count)
            val confidence = FloatArray(count)
            val depth = FloatArray(count)
            val meanCb = 102.4f
            val meanCr = 151.2f
            val cov00 = 160.8f
            val cov01 = -75.4f
            val cov11 = 210.6f
            val det = (cov00 * cov11 - cov01 * cov01).coerceAtLeast(1.0e-4f)
            val inv00 = cov11 / det
            val inv01 = -cov01 / det
            val inv11 = cov00 / det
            val gamma = 1.25f
            val threshold = 2.15f
            val horizon = height * 0.45f
            val sigma2 = 2f * sigmaGate * sigmaGate
            var coverage = 0f
            for (y in 0 until height) {
                val ny = if (height == 1) 0.5f else y / (height - 1f)
                val horizonDepth = if (y < horizon) {
                    0f
                } else {
                    ((y - horizon) / (height - horizon).coerceAtLeast(1f)).coerceIn(0f, 1f).pow(1.5f)
                }
                for (x in 0 until width) {
                    val idx = y * width + x
                    val nx = if (width == 1) 0.5f else x / (width - 1f)
                    val dCb = cb[idx] - meanCb
                    val dCr = cr[idx] - meanCr
                    val distance2 = dCb * (inv00 * dCb + inv01 * dCr) +
                        dCr * (inv01 * dCb + inv11 * dCr)
                    val distance = sqrt(distance2.coerceAtLeast(0f))
                    val skin = (1f / (1f + exp(gamma * (distance - threshold)))).coerceIn(0f, 1f)
                    val dx = nx - lastCenterX.coerceIn(0f, 1f)
                    val dy = ny - lastCenterY.coerceIn(0f, 1f)
                    val radial = exp(-((dx * dx + dy * dy) / sigma2.coerceAtLeast(1.0e-4f))).coerceIn(0f, 1f)
                    val subject = (skin * radial).coerceIn(0f, 1f)
                    val fused = (subject + (1f - subject) * horizonDepth).coerceIn(0f, 1f)
                    mask[idx] = fused
                    confidence[idx] = skin
                    depth[idx] = fused
                    coverage += fused
                }
            }
            return SkinChromaFallbackResult(mask, confidence, depth, (coverage / count).coerceIn(0f, 1f))
        }

        internal fun extractSnapshotLumaGuide(
            snapshot: Yuv420ImageTools.Snapshot,
            maxDimension: Int = 512
        ): LumaGuide {
            val scale = (maxDimension.toFloat() / max(snapshot.width, snapshot.height).coerceAtLeast(1))
                .coerceAtMost(1f)
            val width = max(1, (snapshot.width * scale).roundToInt())
            val height = max(1, (snapshot.height * scale).roundToInt())
            val luma = FloatArray(width * height)
            for (y in 0 until height) {
                val sourceY = ((y + 0.5f) * snapshot.height / height).toInt().coerceIn(0, snapshot.height - 1)
                for (x in 0 until width) {
                    val sourceX = ((x + 0.5f) * snapshot.width / width).toInt().coerceIn(0, snapshot.width - 1)
                    val offset = sourceY * snapshot.yRowStride + sourceX * snapshot.yPixelStride
                    luma[y * width + x] = readSnapshotLuma(snapshot, offset) / 255f
                }
            }
            return LumaGuide(luma, width, height)
        }

        private fun sampleBilinear(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
            val x0 = x.toInt().coerceIn(0, width - 1)
            val y0 = y.toInt().coerceIn(0, height - 1)
            val x1 = (x0 + 1).coerceIn(0, width - 1)
            val y1 = (y0 + 1).coerceIn(0, height - 1)
            val tx = (x - x0).coerceIn(0f, 1f)
            val ty = (y - y0).coerceIn(0f, 1f)
            val top = values[y0 * width + x0] * (1f - tx) + values[y0 * width + x1] * tx
            val bottom = values[y1 * width + x0] * (1f - tx) + values[y1 * width + x1] * tx
            return (top * (1f - ty) + bottom * ty).coerceIn(0f, 1f)
        }

        private fun readSnapshotLuma(snapshot: Yuv420ImageTools.Snapshot, offset: Int): Float {
            if (offset !in snapshot.yBytes.indices) return 0f
            if (snapshot.bitDepth > 8 && offset + 1 < snapshot.yBytes.size) {
                val low = snapshot.yBytes[offset].toInt() and 0xFF
                val high = snapshot.yBytes[offset + 1].toInt() and 0xFF
                return (((high shl 8) or low) ushr 6).coerceIn(0, 1023) / 1023f * 255f
            }
            return (snapshot.yBytes[offset].toInt() and 0xFF).toFloat()
        }
    }

    // Engine status tracking
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    
    private val imageSegmenter: ImageSegmenter? = createSegmenter(context)
    
    // Temporal smoothing
    private var previousForegroundMask: ByteArray? = null
    private var previousForegroundConfidence: FloatArray? = null
    private var previousMaskWidth = 0
    private var previousMaskHeight = 0
    private var frameCount = 0L
    private var totalProcessingTimeMs = 0L

    fun segment(
        bitmap: Bitmap,
        timestampMs: Long,
        rotationDegrees: Int = 0,
        lumaGuide: FloatArray? = null,
        guideWidth: Int = 0,
        guideHeight: Int = 0
    ): SegmentationData? {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("segmentation", startTime)
        
        return try {
            if (imageSegmenter == null) {
                val fallback = fallbackSegmentationData(bitmap.width, bitmap.height, rotationDegrees, lumaGuide, guideWidth, guideHeight)
                _status.value = EngineStatus.Degraded("ImageSegmenter unavailable", "Radial subject mask fallback")
                return fallback
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = segmentMpImage(mpImage, timestampMs, rotationDegrees, lumaGuide, guideWidth, guideHeight)
            
            val processingTime = System.currentTimeMillis() - startTime
            totalProcessingTimeMs += processingTime
            frameCount++
            
            _status.value = EngineStatus.Success(
                metadata = result?.let { "Coverage: ${(it.foregroundCoverage * 100).toInt()}%" } ?: "No mask",
                processingTimeMs = processingTime,
                throughputFps = if (processingTime > 0) 1000f / processingTime else 0f,
                qualityScore = AIFeatureQualityPolicy.scoreSegmentation(result).score
            )
            
            result
        } catch (throwable: Throwable) {
            Log.e(TAG, "Segmentation failed", throwable)
            _status.value = EngineStatus.Failure(
                message = "Segmentation error: ${throwable.message}",
                throwable = throwable,
                canRetry = true,
                failedOperation = "segmentation"
            )
            null
        }
    }

    fun segment(
        image: Image,
        timestampMs: Long,
        rotationDegrees: Int = 0,
        lumaGuide: FloatArray? = null,
        guideWidth: Int = 0,
        guideHeight: Int = 0
    ): SegmentationData? {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("segmentation", startTime)
        
        return try {
            if (imageSegmenter == null) {
                val fallback = fallbackSegmentationData(image.width, image.height, rotationDegrees, lumaGuide, guideWidth, guideHeight)
                _status.value = EngineStatus.Degraded("ImageSegmenter unavailable", "Radial subject mask fallback")
                return fallback
            }
            val mpImage = MediaImageBuilder(image).build()
            val result = segmentMpImage(mpImage, timestampMs, rotationDegrees, lumaGuide, guideWidth, guideHeight)
            
            val processingTime = System.currentTimeMillis() - startTime
            totalProcessingTimeMs += processingTime
            frameCount++
            
            _status.value = EngineStatus.Success(
                processingTimeMs = processingTime,
                throughputFps = if (processingTime > 0) 1000f / processingTime else 0f,
                qualityScore = AIFeatureQualityPolicy.scoreSegmentation(result).score
            )
            
            result
        } catch (throwable: Throwable) {
            Log.e(TAG, "Segmentation failed", throwable)
            _status.value = EngineStatus.Failure(
                message = "Segmentation error",
                throwable = throwable,
                canRetry = true
            )
            null
        } finally {
            runCatching { image.close() }
                .onFailure { Log.w(TAG, "Segmentation image close failed", it) }
        }
    }

    private fun segmentMpImage(
        mpImage: MPImage,
        timestampMs: Long,
        rotationDegrees: Int,
        lumaGuide: FloatArray?,
        guideWidth: Int,
        guideHeight: Int
    ): SegmentationData? {
        val segmenter = imageSegmenter ?: return fallbackSegmentationData(
            mpImage.width,
            mpImage.height,
            rotationDegrees,
            lumaGuide,
            guideWidth,
            guideHeight
        )
        val result = segmenter.segmentForVideo(mpImage, timestampMs)
        
        // Extract confidence masks (multi-class support)
        val confidenceMasks = result.confidenceMasks().orElse(emptyList<MPImage>())
        if (confidenceMasks.isEmpty()) return null
        
        val maskWidth = confidenceMasks.first().width
        val maskHeight = confidenceMasks.first().height
        val classConfidenceChannels = extractConfidenceChannels(confidenceMasks, maskWidth, maskHeight)
        val rawForegroundConfidence = compositeForegroundConfidence(classConfidenceChannels, maskWidth, maskHeight)
        var coverage = 0f
        var weightedCenterX = 0f
        var weightedCenterY = 0f
        var weightSum = 0f

        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                val index = y * maskWidth + x
                val confidence = rawForegroundConfidence[index].coerceIn(0f, 1f)
                coverage += confidence
                weightedCenterX += x * confidence
                weightedCenterY += y * confidence
                weightSum += confidence
            }
        }

        if (weightSum <= 0.0001f) return null

        val hasGuide = lumaGuide != null &&
            guideWidth > 0 &&
            guideHeight > 0 &&
            lumaGuide.size >= guideWidth * guideHeight
        val spatialMask = if (hasGuide) {
            guidedFilterMaskRefine(
                lowMask = rawForegroundConfidence,
                lowWidth = maskWidth,
                lowHeight = maskHeight,
                lumaGuide = lumaGuide,
                guideWidth = guideWidth,
                guideHeight = guideHeight
            )
        } else {
            bytesToFloats(applyEdgeRefinement(floatsToBytes(rawForegroundConfidence), maskWidth, maskHeight))
        }
        val outputWidth = if (hasGuide) guideWidth else maskWidth
        val outputHeight = if (hasGuide) guideHeight else maskHeight
        val temporal = motionAdaptiveBackwardWarpTemporalBlend(
            currentMask = spatialMask,
            previousMask = previousForegroundConfidence,
            width = outputWidth,
            height = outputHeight,
            baseAlpha = if (strictHighEndEnabled()) 0.88f else TEMPORAL_SMOOTHING_WEIGHT
        )
        val refinedMask = floatsToBytes(temporal.mask)
        val approximateDepth = FloatArray(temporal.mask.size)
        val confidenceMap = generateConfidenceMap(refinedMask, outputWidth, outputHeight)

        val centerX = weightedCenterX / weightSum
        val centerY = weightedCenterY / weightSum
        val normalizedCoverage = (coverage / (maskWidth * maskHeight)).coerceIn(0f, 1f)
        val subjectDistanceScore = (1f - normalizedCoverage).coerceIn(0.1f, 1f)

        // Compute approximate depth with refined mask
        for (y in 0 until outputHeight) {
            for (x in 0 until outputWidth) {
                val index = y * outputWidth + x
                val confidence = temporal.mask[index].coerceIn(0f, 1f)
                val dx = (x / outputWidth.toFloat()) - (centerX / maskWidth.toFloat())
                val dy = (y / outputHeight.toFloat()) - (centerY / maskHeight.toFloat())
                val radialFalloff = (1f - (dx * dx + dy * dy) * 2.5f).coerceIn(0f, 1f)
                approximateDepth[index] = (confidence * radialFalloff * subjectDistanceScore).coerceIn(0f, 1f)
            }
        }
        
        // Multi-class support (category mask extraction)
        val categoryMask = result.categoryMask().orElse(null)
        val multiClassMask = categoryMask?.let { extractCategoryMask(it) }
        
        // Compute temporal coherence metric
        val temporalCoherence = computeTemporalCoherence(refinedMask, previousForegroundMask, outputWidth, outputHeight)
        
        // Update previous mask for next frame
        previousForegroundMask = refinedMask.copyOf()
        previousForegroundConfidence = temporal.mask.copyOf()
        previousMaskWidth = outputWidth
        previousMaskHeight = outputHeight

        return SegmentationData(
            maskWidth = outputWidth,
            maskHeight = outputHeight,
            foregroundMask = refinedMask,
            approximateDepth = approximateDepth,
            foregroundCoverage = (temporal.mask.average().toFloat()).coerceIn(0f, 1f),
            subjectDistanceScore = subjectDistanceScore,
            rotationDegrees = rotationDegrees,
            multiClassMask = multiClassMask,
            classLabels = MULTICLASS_LABELS,
            edgeRefinementApplied = true,
            temporalCoherence = temporalCoherence,
            confidenceMap = confidenceMap,
            classConfidenceChannels = classConfidenceChannels,
            guideWidth = if (hasGuide) guideWidth else outputWidth,
            guideHeight = if (hasGuide) guideHeight else outputHeight,
            guideLuma = lumaGuide?.takeIf { hasGuide }?.copyOf(),
            temporalMotionMean = temporal.meanMotion,
            temporalHistoryWeightMean = temporal.meanHistoryWeight,
            matteBackend = if (hasGuide) "GUIDED_FILTER_TEMPORAL_WARP" else "EDGE_REFINEMENT_TEMPORAL_WARP"
        )
    }
    
    private fun extractConfidenceChannels(
        confidenceMasks: List<MPImage>,
        maskWidth: Int,
        maskHeight: Int
    ): Array<FloatArray> {
        val outputSize = maskWidth * maskHeight
        return Array(confidenceMasks.size) { channelIndex ->
            val image = confidenceMasks[channelIndex]
            val buffer = ByteBufferExtractor.extract(image).asFloatBuffer()
            FloatArray(outputSize) { index ->
                if (index < buffer.capacity()) buffer.get(index).coerceIn(0f, 1f) else 0f
            }
        }
    }

    private fun compositeForegroundConfidence(
        classConfidenceChannels: Array<FloatArray>,
        maskWidth: Int,
        maskHeight: Int
    ): FloatArray {
        val outputSize = maskWidth * maskHeight
        if (classConfidenceChannels.isEmpty()) return FloatArray(outputSize)
        if (classConfidenceChannels.size >= 6) {
            return classConfidenceChannels[5].copyOf(outputSize)
        }
        if (classConfidenceChannels.size == 1) {
            return classConfidenceChannels[0].copyOf(outputSize)
        }
        val composite = FloatArray(outputSize)
        for (index in 0 until outputSize) {
            var foreground = 0f
            for (classIndex in 1 until classConfidenceChannels.size) {
                foreground = max(foreground, classConfidenceChannels[classIndex].getOrElse(index) { 0f })
            }
            composite[index] = foreground.coerceIn(0f, 1f)
        }
        return composite
    }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val out = ByteArray(values.size)
        for (index in values.indices) {
            out[index] = (values[index].coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun bytesToFloats(values: ByteArray): FloatArray {
        val out = FloatArray(values.size)
        for (index in values.indices) {
            out[index] = (values[index].toInt() and 0xFF) / 255f
        }
        return out
    }

    /**
     * Apply temporal smoothing for stable masks across frames
     */
    private fun applyTemporalSmoothing(
        currentMask: ByteArray,
        width: Int,
        height: Int
    ): ByteArray {
        val strictHighEnd = strictHighEndEnabled()
        val previous = previousForegroundMask
        if (previous == null || previous.size != currentMask.size || 
            previousMaskWidth != width || previousMaskHeight != height) {
            return currentMask
        }
        
        val smoothed = ByteArray(currentMask.size)
        for (i in currentMask.indices) {
            val current = (currentMask[i].toInt() and 0xFF) / 255f
            val prev = (previous[i].toInt() and 0xFF) / 255f
            val temporalWeight = if (strictHighEnd) {
                (TEMPORAL_SMOOTHING_WEIGHT + 0.08f).coerceAtMost(0.88f)
            } else {
                TEMPORAL_SMOOTHING_WEIGHT
            }
            val blended = current * (1f - temporalWeight) + prev * temporalWeight
            smoothed[i] = (blended * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return smoothed
    }
    
    /**
     * Apply edge refinement for clean mask boundaries
     */
    private fun applyEdgeRefinement(
        mask: ByteArray,
        width: Int,
        height: Int
    ): ByteArray {
        val strictHighEnd = strictHighEndEnabled()
        val refined = mask.copyOf()
        val halfKernel = EDGE_REFINEMENT_KERNEL / 2
        
        for (y in halfKernel until height - halfKernel) {
            for (x in halfKernel until width - halfKernel) {
                val idx = y * width + x
                val centerValue = (mask[idx].toInt() and 0xFF) / 255f
                
                // Check if this is an edge pixel (transition)
                var isEdge = false
                var neighborSum = 0f
                var neighborCount = 0
                
                for (dy in -halfKernel..halfKernel) {
                    for (dx in -halfKernel..halfKernel) {
                        if (dx == 0 && dy == 0) continue
                        val nIdx = (y + dy) * width + (x + dx)
                        val neighborValue = (mask[nIdx].toInt() and 0xFF) / 255f
                        neighborSum += neighborValue
                        neighborCount++
                        
                        // Detect edge
                        if (abs(neighborValue - centerValue) > if (strictHighEnd) 0.26f else 0.3f) {
                            isEdge = true
                        }
                    }
                }
                
                // Refine edge pixels
                if (isEdge && neighborCount > 0) {
                    val avgNeighbor = neighborSum / neighborCount
                    val refinedValue = if (strictHighEnd) {
                        centerValue * 0.52f + avgNeighbor * 0.48f
                    } else {
                        centerValue * 0.6f + avgNeighbor * 0.4f
                    }
                    refined[idx] = (refinedValue * 255f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        
        return refined
    }
    
    /**
     * Generate confidence map for segmentation quality
     */
    private fun generateConfidenceMap(
        mask: ByteArray,
        width: Int,
        height: Int
    ): FloatArray {
        val strictHighEnd = strictHighEndEnabled()
        val confidence = FloatArray(mask.size)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val maskValue = (mask[idx].toInt() and 0xFF) / 255f
                
                // Confidence is higher for clear foreground/background
                val confidenceThreshold = if (strictHighEnd) {
                    (CONFIDENCE_THRESHOLD - 0.04f).coerceAtLeast(0.5f)
                } else {
                    CONFIDENCE_THRESHOLD
                }
                confidence[idx] = if (maskValue > confidenceThreshold) {
                    maskValue
                } else if (maskValue < (1f - confidenceThreshold)) {
                    1f - maskValue
                } else {
                    if (strictHighEnd) 0.56f else 0.5f // Uncertain region
                }
            }
        }
        
        return confidence
    }
    
    /**
     * Extract category mask for multi-class segmentation
     */
    private fun extractCategoryMask(categoryMaskImage: MPImage): ByteArray {
        val buffer = ByteBufferExtractor.extract(categoryMaskImage)
        val width = categoryMaskImage.width
        val height = categoryMaskImage.height
        val categoryMask = ByteArray(width * height)
        
        buffer.get(categoryMask)
        return categoryMask
    }
    
    /**
     * Compute temporal coherence between frames
     */
    private fun computeTemporalCoherence(
        current: ByteArray,
        previous: ByteArray?,
        width: Int,
        height: Int
    ): Float {
        if (previous == null || previous.size != current.size) return 0f
        
        var totalDifference = 0f
        for (i in current.indices) {
            val diff = abs((current[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)) / 255f
            totalDifference += diff
        }
        
        val avgDifference = totalDifference / current.size
        return (1f - avgDifference).coerceIn(0f, 1f)
    }

    private fun createSegmenter(context: Context): ImageSegmenter? {
        _status.value = EngineStatus.Initializing("segmenter", 0)
        val modelAsset = resolveSegmentationModelAsset(context) ?: run {
            _status.value = EngineStatus.Degraded("Segmentation model asset unavailable", "Radial subject mask fallback")
            Log.w(TAG, "Segmentation model asset unavailable; using deterministic fallback masks")
            return null
        }
        
        val delegates = listOf(Delegate.GPU, Delegate.CPU)
        val attemptedDelegates = mutableListOf<String>()
        var lastError: Throwable? = null
        
        for (delegate in delegates) {
            attemptedDelegates.add(delegate.name)
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelAsset)
                    .setDelegate(delegate)
                    .build()
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(true) // Enable multi-class
                    .build()
                Log.i(TAG, "Creating ImageSegmenter with delegate=$delegate")
                
                _status.value = EngineStatus.Idle()
                return ImageSegmenter.createFromOptions(context, options)
            } catch (throwable: Throwable) {
                lastError = throwable
                Log.w(TAG, "ImageSegmenter init failed for delegate=$delegate", throwable)
            }
        }
        
        _status.value = EngineStatus.InitializationFailed(
            message = "Unable to initialize ImageSegmenter",
            throwable = lastError ?: Exception("Unknown error"),
            attemptedDelegates = attemptedDelegates
        )
        Log.w(TAG, "Unable to initialize ImageSegmenter; using fallback", lastError)
        return null
    }

    private fun resolveSegmentationModelAsset(context: Context): String? {
        val available = context.assets.list(AIConstants.MODEL_ASSET_DIRECTORY)
            ?.map { "${AIConstants.MODEL_ASSET_DIRECTORY}/$it" }
            .orEmpty()
            .toSet()
        return MODEL_ASSET_CANDIDATES.firstOrNull { candidate ->
            candidate in available || runCatching { context.assets.open(candidate).use { true } }.getOrDefault(false)
        }
    }

    private fun fallbackSegmentationData(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        lumaGuide: FloatArray? = null,
        guideWidth: Int = 0,
        guideHeight: Int = 0
    ): SegmentationData {
        val maskWidth = min(width.coerceAtLeast(1), 256)
        val maskHeight = min(height.coerceAtLeast(1), 256)
        val mask = ByteArray(maskWidth * maskHeight)
        val depth = AIProcessor().synthesizeRadialDepthFallback(
            width = maskWidth,
            height = maskHeight,
            focusDistanceDiopters = 2f,
            focalLengthMm = 24f,
            faceBounds = null
        )
        var coverage = 0f
        val sigma = 0.26f
        for (y in 0 until maskHeight) {
            val ny = if (maskHeight == 1) 0.5f else y / (maskHeight - 1).toFloat()
            for (x in 0 until maskWidth) {
                val nx = if (maskWidth == 1) 0.5f else x / (maskWidth - 1).toFloat()
                val dx = nx - 0.5f
                val dy = ny - 0.48f
                val value = kotlin.math.exp(-((dx * dx + dy * dy) / (2f * sigma * sigma))).coerceIn(0f, 1f)
                coverage += value
                mask[y * maskWidth + x] = (value * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }
        val rawMask = bytesToFloats(mask)
        val hasGuide = lumaGuide != null &&
            guideWidth > 0 &&
            guideHeight > 0 &&
            lumaGuide.size >= guideWidth * guideHeight
        val current = if (hasGuide) {
            guidedFilterMaskRefine(rawMask, maskWidth, maskHeight, lumaGuide, guideWidth, guideHeight)
        } else {
            rawMask
        }
        val outputWidth = if (hasGuide) guideWidth else maskWidth
        val outputHeight = if (hasGuide) guideHeight else maskHeight
        val temporal = motionAdaptiveBackwardWarpTemporalBlend(
            currentMask = current,
            previousMask = previousForegroundConfidence,
            width = outputWidth,
            height = outputHeight,
            baseAlpha = if (strictHighEndEnabled()) 0.88f else TEMPORAL_SMOOTHING_WEIGHT
        )
        val refinedMask = floatsToBytes(temporal.mask)
        val temporalCoherence = computeTemporalCoherence(refinedMask, previousForegroundMask, maskWidth, maskHeight)
        previousForegroundMask = refinedMask.copyOf()
        previousForegroundConfidence = temporal.mask.copyOf()
        previousMaskWidth = outputWidth
        previousMaskHeight = outputHeight
        return SegmentationData(
            maskWidth = outputWidth,
            maskHeight = outputHeight,
            foregroundMask = refinedMask,
            approximateDepth = if (hasGuide) FloatArray(outputWidth * outputHeight) { idx ->
                temporal.mask[idx].coerceIn(0f, 1f)
            } else depth,
            foregroundCoverage = temporal.mask.average().toFloat().coerceIn(0f, 1f),
            subjectDistanceScore = 0.55f,
            rotationDegrees = rotationDegrees,
            classLabels = MULTICLASS_LABELS,
            edgeRefinementApplied = hasGuide,
            temporalCoherence = temporalCoherence,
            confidenceMap = FloatArray(refinedMask.size) { (refinedMask[it].toInt() and 0xFF) / 255f },
            guideWidth = if (hasGuide) guideWidth else outputWidth,
            guideHeight = if (hasGuide) guideHeight else outputHeight,
            guideLuma = lumaGuide?.takeIf { hasGuide }?.copyOf(),
            temporalMotionMean = temporal.meanMotion,
            temporalHistoryWeightMean = temporal.meanHistoryWeight,
            matteBackend = if (hasGuide) "GUIDED_FILTER_TEMPORAL_WARP_FALLBACK" else "RADIAL_FALLBACK"
        )
    }

    override fun close() {
        runCatching { imageSegmenter?.close() }
            .onFailure { Log.w(TAG, "ImageSegmenter close failed", it) }
        previousForegroundMask = null
        previousForegroundConfidence = null
        _status.value = EngineStatus.Idle()
    }
}
