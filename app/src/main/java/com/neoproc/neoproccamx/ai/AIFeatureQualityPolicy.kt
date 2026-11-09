package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AIFeatureQualityScore(
    val score: Float,
    val usable: Boolean,
    val reason: String
)

data class AIFeatureQualityReport(
    val focusScore: Float = 0f,
    val trackingScore: Float = 0f,
    val segmentationScore: Float = 0f,
    val segmentationUsable: Boolean = false,
    val depthScore: Float = 0f,
    val depthUsable: Boolean = false,
    val denoiseScore: Float = 0f,
    val radianceToneScore: Float = 0f,
    val qualityAnalysisScore: Float = 0f
) {
    val focusAssistScore: Float
        get() = weightedAverage(
            focusScore to 0.28f,
            trackingScore to 0.22f,
            segmentationScore to 0.18f,
            depthScore to 0.20f,
            qualityAnalysisScore to 0.12f
        )

    val overallScore: Float
        get() = weightedAverage(
            focusScore to 0.18f,
            trackingScore to 0.16f,
            segmentationScore to 0.14f,
            depthScore to 0.14f,
            denoiseScore to 0.16f,
            radianceToneScore to 0.12f,
            qualityAnalysisScore to 0.10f
        )

    val usableForSubjectAssist: Boolean
        get() = focusScore >= 0.45f && trackingScore >= 0.42f && (segmentationUsable || depthUsable)
}

object AIFeatureQualityPolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun scoreSegmentation(
        data: SegmentationData?,
        strictHighEnd: Boolean = strictHighEndEnabled()
    ): AIFeatureQualityScore {
        data ?: return AIFeatureQualityScore(0f, usable = false, reason = "segmentation_missing")
        val expectedSize = data.maskWidth * data.maskHeight
        if (data.maskWidth <= 0 || data.maskHeight <= 0 || data.foregroundMask.size < expectedSize) {
            return AIFeatureQualityScore(0f, usable = false, reason = "segmentation_invalid_dimensions")
        }

        val coverage = data.foregroundCoverage.coerceIn(0f, 1f)
        val coverageScore = coverageQuality(coverage)
        val confidenceScore = segmentationConfidenceScore(data, expectedSize)
        val resolutionScore = dimensionQuality(data.maskWidth, data.maskHeight)
        val temporalScore = temporalMatteScore(data)
        val edgeScore = edgeMatteScore(data)
        val backendScore = segmentationBackendScore(data.matteBackend)

        val score = weightedAverage(
            confidenceScore to 0.28f,
            coverageScore to 0.18f,
            resolutionScore to 0.14f,
            temporalScore to 0.14f,
            edgeScore to 0.12f,
            backendScore to 0.14f
        )
        val threshold = if (strictHighEnd) 0.56f else 0.50f
        val usable = score >= threshold && coverage in 0.002f..0.985f
        val reason = when {
            coverage !in 0.002f..0.985f -> "segmentation_extreme_coverage"
            score < threshold -> "segmentation_low_quality"
            else -> "segmentation_usable"
        }
        return AIFeatureQualityScore(score, usable, reason)
    }

    fun scoreDepth(
        data: DepthData?,
        strictHighEnd: Boolean = strictHighEndEnabled()
    ): AIFeatureQualityScore {
        data ?: return AIFeatureQualityScore(0f, usable = false, reason = "depth_missing")
        val expectedSize = data.mapWidth * data.mapHeight
        if (data.mapWidth <= 0 || data.mapHeight <= 0 || data.normalizedDepth.size < expectedSize) {
            return AIFeatureQualityScore(0f, usable = false, reason = "depth_invalid_dimensions")
        }

        val depthRange = depthRange(data, expectedSize)
        val rangeScore = (depthRange / 0.28f).coerceIn(0f, 1f)
        val confidenceScore = depthConfidenceScore(data, expectedSize)
        val resolutionScore = max(
            dimensionQuality(data.mapWidth, data.mapHeight) * 0.72f,
            dimensionQuality(data.highResolutionWidth, data.highResolutionHeight)
        )
        val temporalScore = when {
            data.multiFrameFusion || data.temporallyRefined ->
                (0.74f + data.temporalHistoryWeightMean.coerceIn(0f, 1f) * 0.22f).coerceIn(0f, 1f)
            data.temporalHistoryWeightMean > 0f ->
                (0.62f + data.temporalHistoryWeightMean.coerceIn(0f, 1f) * 0.18f).coerceIn(0f, 1f)
            else -> 0.60f
        }
        val edgeScore = depthEdgeScore(data.edgeMap)
        val cocScore = if (data.circleOfConfusionRadius?.isNotEmpty() == true) 0.86f else 0.64f
        val backendScore = if (data.bokehRenderBackend.contains("VULKAN", ignoreCase = true)) 0.92f else 0.74f

        val score = weightedAverage(
            confidenceScore to 0.24f,
            rangeScore to 0.18f,
            resolutionScore to 0.16f,
            temporalScore to 0.14f,
            edgeScore to 0.10f,
            cocScore to 0.08f,
            backendScore to 0.10f
        )
        val threshold = if (strictHighEnd) 0.54f else 0.48f
        val usable = score >= threshold && depthRange >= 0.012f
        val reason = when {
            depthRange < 0.012f -> "depth_flat_or_uninformative"
            score < threshold -> "depth_low_quality"
            else -> "depth_usable"
        }
        return AIFeatureQualityScore(score, usable, reason)
    }

    fun qualifiedSegmentation(data: SegmentationData?): SegmentationData? =
        data?.takeIf { scoreSegmentation(it).usable }

    fun qualifiedDepth(data: DepthData?): DepthData? =
        data?.takeIf { scoreDepth(it).usable }

    fun buildReport(
        faces: List<TrackedFace>,
        objects: List<TrackedObject>,
        pose: TrackedPose?,
        segmentation: SegmentationData?,
        depthData: DepthData?,
        focusPoint: FocusPoint?,
        enhancementResult: EnhancementResult?
    ): AIFeatureQualityReport {
        val segmentationQuality = scoreSegmentation(segmentation)
        val depthQuality = scoreDepth(depthData)
        val focusScore = focusPoint?.confidence?.coerceIn(0f, 1f)
            ?: maxSubjectConfidence(faces, objects, pose) * 0.86f
        val trackingScore = trackingQuality(faces, objects, pose)
        val denoiseScore = denoiseQuality(enhancementResult)
        val toneScore = toneHelperQuality(segmentationQuality.score, depthQuality.score, enhancementResult)
        val analysisScore = qualityAnalysisReadiness(
            focusScore = focusScore,
            trackingScore = trackingScore,
            segmentationScore = segmentationQuality.score,
            depthScore = depthQuality.score,
            denoiseScore = denoiseScore
        )

        return AIFeatureQualityReport(
            focusScore = focusScore.coerceIn(0f, 1f),
            trackingScore = trackingScore,
            segmentationScore = segmentationQuality.score,
            segmentationUsable = segmentationQuality.usable,
            depthScore = depthQuality.score,
            depthUsable = depthQuality.usable,
            denoiseScore = denoiseScore,
            radianceToneScore = toneScore,
            qualityAnalysisScore = analysisScore
        )
    }

    private fun segmentationConfidenceScore(data: SegmentationData, expectedSize: Int): Float {
        val sampleLimit = min(expectedSize, 4096)
        val step = (expectedSize / sampleLimit).coerceAtLeast(1)
        var confidenceSum = 0f
        var certaintySum = 0f
        var count = 0
        var index = 0
        while (index < expectedSize) {
            val maskValue = (data.foregroundMask[index].toInt() and 0xFF) / 255f
            certaintySum += abs(maskValue - 0.5f) * 2f
            confidenceSum += data.confidenceMap?.getOrNull(index)?.coerceIn(0f, 1f) ?: maskValue
            count++
            index += step
        }
        val confidence = if (count == 0) 0f else confidenceSum / count
        val certainty = if (count == 0) 0f else certaintySum / count
        return max(0.45f, confidence * 0.64f + certainty * 0.36f).coerceIn(0f, 1f)
    }

    private fun depthConfidenceScore(data: DepthData, expectedSize: Int): Float {
        val confidenceMap = data.confidenceMap ?: return 0.68f
        val sampleLimit = min(expectedSize, 4096)
        val step = (expectedSize / sampleLimit).coerceAtLeast(1)
        var sum = 0f
        var count = 0
        var index = 0
        while (index < expectedSize && index < confidenceMap.size) {
            sum += confidenceMap[index].coerceIn(0f, 1f)
            count++
            index += step
        }
        return if (count == 0) 0f else (sum / count).coerceIn(0f, 1f)
    }

    private fun depthRange(data: DepthData, expectedSize: Int): Float {
        var minDepth = Float.POSITIVE_INFINITY
        var maxDepth = Float.NEGATIVE_INFINITY
        val sampleLimit = min(expectedSize, 4096)
        val step = (expectedSize / sampleLimit).coerceAtLeast(1)
        var index = 0
        while (index < expectedSize) {
            val value = data.normalizedDepth[index].takeIf { it.isFinite() } ?: 0f
            minDepth = min(minDepth, value)
            maxDepth = max(maxDepth, value)
            index += step
        }
        if (!minDepth.isFinite() || !maxDepth.isFinite()) return 0f
        return (maxDepth - minDepth).coerceIn(0f, 1f)
    }

    private fun coverageQuality(coverage: Float): Float =
        when {
            coverage < 0.002f || coverage > 0.985f -> 0f
            coverage < 0.02f -> (coverage / 0.02f).coerceIn(0f, 1f)
            coverage <= 0.72f -> 1f
            else -> (1f - ((coverage - 0.72f) / 0.265f)).coerceIn(0f, 1f)
        }

    private fun temporalMatteScore(data: SegmentationData): Float {
        val coherence = data.temporalCoherence.coerceIn(0f, 1f)
        val history = data.temporalHistoryWeightMean.coerceIn(0f, 1f)
        val motionPenalty = (data.temporalMotionMean / 8f).coerceIn(0f, 0.18f)
        return (0.54f + coherence * 0.28f + history * 0.20f - motionPenalty).coerceIn(0f, 1f)
    }

    private fun edgeMatteScore(data: SegmentationData): Float {
        val backend = data.matteBackend.uppercase()
        val base = when {
            data.edgeRefinementApplied && backend.contains("GUIDED") -> 0.92f
            data.edgeRefinementApplied -> 0.82f
            backend.contains("FALLBACK") -> 0.58f
            else -> 0.70f
        }
        return (base + data.temporalCoherence.coerceIn(0f, 1f) * 0.08f).coerceIn(0f, 1f)
    }

    private fun segmentationBackendScore(backend: String): Float {
        val normalized = backend.uppercase()
        return when {
            normalized.contains("GUIDED_FILTER_TEMPORAL") -> 0.96f
            normalized.contains("EDGE_REFINEMENT_TEMPORAL") -> 0.86f
            normalized.contains("GUIDED") -> 0.84f
            normalized.contains("FALLBACK") -> 0.64f
            normalized.isNotBlank() -> 0.74f
            else -> 0.58f
        }
    }

    private fun depthEdgeScore(edgeMap: FloatArray?): Float {
        edgeMap ?: return 0.70f
        if (edgeMap.isEmpty()) return 0.60f
        val sampleLimit = min(edgeMap.size, 4096)
        val step = (edgeMap.size / sampleLimit).coerceAtLeast(1)
        var sum = 0f
        var count = 0
        var index = 0
        while (index < edgeMap.size) {
            sum += edgeMap[index].coerceIn(0f, 1f)
            count++
            index += step
        }
        val coverage = if (count == 0) 0f else sum / count
        return (1f - abs(coverage - 0.06f) / 0.22f).coerceIn(0.50f, 1f)
    }

    private fun dimensionQuality(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val longEdge = max(width, height)
        val shortEdge = min(width, height)
        val longScore = (longEdge / 512f).coerceIn(0.30f, 1f)
        val shortScore = (shortEdge / 384f).coerceIn(0.30f, 1f)
        return sqrt(longScore * shortScore).coerceIn(0f, 1f)
    }

    private fun maxSubjectConfidence(
        faces: List<TrackedFace>,
        objects: List<TrackedObject>,
        pose: TrackedPose?
    ): Float = max(
        faces.maxOfOrNull { it.confidence.coerceIn(0f, 1f) } ?: 0f,
        max(
            objects.maxOfOrNull { it.confidence.coerceIn(0f, 1f) } ?: 0f,
            pose?.confidence?.coerceIn(0f, 1f) ?: 0f
        )
    )

    private fun trackingQuality(
        faces: List<TrackedFace>,
        objects: List<TrackedObject>,
        pose: TrackedPose?
    ): Float {
        val confidences = ArrayList<Float>(faces.size + objects.size + if (pose != null) 1 else 0)
        faces.forEach { face ->
            val eyeSupport = listOfNotNull(face.leftEye?.confidence, face.rightEye?.confidence)
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
                ?: face.confidence * 0.72f
            val occlusionPenalty = (face.occlusionLevel * 0.24f).coerceIn(0f, 0.24f)
            confidences += (face.confidence * 0.68f + eyeSupport * 0.32f - occlusionPenalty).coerceIn(0f, 1f)
        }
        objects.forEach { obj ->
            val continuity = ((obj.trackedFrames - obj.missedFrames).coerceAtLeast(0) / 8f).coerceIn(0f, 0.20f)
            confidences += (obj.confidence * 0.86f + continuity).coerceIn(0f, 1f)
        }
        pose?.let {
            confidences += (it.confidence * 0.72f + it.skeletonStability.coerceIn(0f, 1f) * 0.28f).coerceIn(0f, 1f)
        }
        if (confidences.isEmpty()) return 0f
        return confidences.average().toFloat().coerceIn(0f, 1f)
    }

    private fun denoiseQuality(result: EnhancementResult?): Float {
        result ?: return 0f
        val cleanSignal = (1f - result.estimatedNoiseLevel.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val algorithmScore = when {
            result.algorithmsApplied.any { it.contains("denoise", ignoreCase = true) } -> 0.86f
            result.algorithmsApplied.isNotEmpty() -> 0.64f
            else -> 0.45f
        }
        val sharpness = result.sharpnessScore.coerceIn(0f, 1f)
        return weightedAverage(
            cleanSignal to 0.42f,
            algorithmScore to 0.34f,
            sharpness to 0.24f
        )
    }

    private fun toneHelperQuality(
        segmentationScore: Float,
        depthScore: Float,
        result: EnhancementResult?
    ): Float {
        val enhancementSignal = result?.let {
            max(
                it.highlightRecovery.coerceIn(0f, 1f),
                max(it.shadowRecovery.coerceIn(0f, 1f), it.colorVibrance.coerceIn(0f, 1f))
            )
        } ?: 0f
        return weightedAverage(
            segmentationScore to 0.24f,
            depthScore to 0.28f,
            enhancementSignal to 0.18f,
            0.70f to 0.30f
        )
    }

    private fun qualityAnalysisReadiness(
        focusScore: Float,
        trackingScore: Float,
        segmentationScore: Float,
        depthScore: Float,
        denoiseScore: Float
    ): Float =
        weightedAverage(
            focusScore to 0.22f,
            trackingScore to 0.20f,
            segmentationScore to 0.18f,
            depthScore to 0.18f,
            denoiseScore to 0.22f
        )
}

private fun weightedAverage(vararg pairs: Pair<Float, Float>): Float {
    var weighted = 0f
    var weights = 0f
    pairs.forEach { (value, weight) ->
        if (weight <= 0f) return@forEach
        weighted += value.coerceIn(0f, 1f) * weight
        weights += weight
    }
    return if (weights <= 0f) 0f else (weighted / weights).coerceIn(0f, 1f)
}
