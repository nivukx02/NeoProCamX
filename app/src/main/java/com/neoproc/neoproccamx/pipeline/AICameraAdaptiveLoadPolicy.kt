package com.neoproc.neoproccamx.pipeline

internal data class AICameraAdaptiveLoadBaseProfile(
    val poseDetectionStride: Int,
    val segmentationStride: Int,
    val depthEstimationStride: Int,
    val enhancementStride: Int,
    val sceneDetectionStride: Int,
    val analysisTimeoutMs: Long,
    val analysisMaxDimension: Int
)

internal data class AICameraAdaptiveLoadProfile(
    val poseDetectionStride: Int,
    val segmentationStride: Int,
    val depthEstimationStride: Int,
    val enhancementStride: Int,
    val sceneDetectionStride: Int,
    val analysisTimeoutMs: Long,
    val analysisMaxDimension: Int
)

internal object AICameraAdaptiveLoadPolicy {
    fun resolve(
        base: AICameraAdaptiveLoadBaseProfile,
        loadPenalty: Int,
        // s7-f01 / CP92: false (DEFAULT) => today's uniform multiplier for every stage (bit-for-bit).
        // true => ordered drop (depth heaviest, then segmentation, then pose) so the governor sheds
        // the most expensive AI stages first; face has no stride and is therefore dropped last.
        orderedDrop: Boolean = false
    ): AICameraAdaptiveLoadProfile {
        val penalty = loadPenalty.coerceIn(0, 3)
        if (penalty == 0) {
            return AICameraAdaptiveLoadProfile(
                poseDetectionStride = base.poseDetectionStride,
                segmentationStride = base.segmentationStride,
                depthEstimationStride = base.depthEstimationStride,
                enhancementStride = base.enhancementStride,
                sceneDetectionStride = base.sceneDetectionStride,
                analysisTimeoutMs = base.analysisTimeoutMs,
                analysisMaxDimension = base.analysisMaxDimension
            )
        }

        val strideMultiplier = when (penalty) {
            1 -> 1.35f
            2 -> 1.75f
            else -> 2.35f
        }
        val dimensionScale = when (penalty) {
            1 -> 0.86f
            2 -> 0.72f
            else -> 0.58f
        }
        val timeoutScale = when (penalty) {
            1 -> 0.90f
            2 -> 0.78f
            else -> 0.64f
        }

        // Ordered drop (gated): depth >= segmentation >= pose multiplier so the heaviest stages
        // shed first; default keeps the uniform multiplier => byte-identical to today.
        val depthMultiplier = if (orderedDrop) strideMultiplier * 1.30f else strideMultiplier
        val segmentationMultiplier = if (orderedDrop) strideMultiplier * 1.15f else strideMultiplier
        val poseMultiplier = strideMultiplier
        return AICameraAdaptiveLoadProfile(
            poseDetectionStride = scaleStride(base.poseDetectionStride, poseMultiplier),
            segmentationStride = scaleStride(base.segmentationStride, segmentationMultiplier),
            depthEstimationStride = scaleStride(base.depthEstimationStride, depthMultiplier),
            enhancementStride = scaleStride(base.enhancementStride, strideMultiplier),
            sceneDetectionStride = scaleStride(base.sceneDetectionStride, strideMultiplier),
            analysisTimeoutMs = (base.analysisTimeoutMs * timeoutScale)
                .toLong()
                .coerceIn(12L, base.analysisTimeoutMs.coerceAtLeast(12L)),
            analysisMaxDimension = (base.analysisMaxDimension * dimensionScale)
                .toInt()
                .coerceIn(256, base.analysisMaxDimension.coerceAtLeast(256))
        )
    }

    private fun scaleStride(base: Int, multiplier: Float): Int =
        (base * multiplier).toInt().coerceAtLeast(base + 1)
}
