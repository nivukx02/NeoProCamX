package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIFeatureQualityPolicyTest {

    @Test
    fun segmentationScoreRejectsExtremeFallbackCoverage() {
        val mask = ByteArray(16 * 16) { 255.toByte() }
        val data = SegmentationData(
            maskWidth = 16,
            maskHeight = 16,
            foregroundMask = mask,
            approximateDepth = FloatArray(mask.size) { 0.5f },
            foregroundCoverage = 1f,
            subjectDistanceScore = 0.5f,
            confidenceMap = FloatArray(mask.size) { 0.92f },
            temporalCoherence = 0.95f,
            temporalHistoryWeightMean = 0.85f,
            matteBackend = "GUIDED_FILTER_TEMPORAL_WARP"
        )

        val score = AIFeatureQualityPolicy.scoreSegmentation(data, strictHighEnd = true)

        assertFalse(score.usable)
        assertTrue(score.reason.contains("coverage"))
    }

    @Test
    fun segmentationScoreAcceptsCoherentGuidedMask() {
        val width = 96
        val height = 64
        val mask = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in 28..68 && y in 14..50) 230.toByte() else 12.toByte()
        }
        val coverage = mask.count { (it.toInt() and 0xFF) > 127 }.toFloat() / mask.size
        val data = SegmentationData(
            maskWidth = width,
            maskHeight = height,
            foregroundMask = mask,
            approximateDepth = FloatArray(mask.size) { 0.45f },
            foregroundCoverage = coverage,
            subjectDistanceScore = 0.55f,
            confidenceMap = FloatArray(mask.size) { 0.88f },
            edgeRefinementApplied = true,
            temporalCoherence = 0.93f,
            temporalHistoryWeightMean = 0.82f,
            matteBackend = "GUIDED_FILTER_TEMPORAL_WARP"
        )

        val score = AIFeatureQualityPolicy.scoreSegmentation(data, strictHighEnd = true)

        assertTrue("score=${score.score} reason=${score.reason}", score.usable)
        assertTrue(score.score > 0.70f)
    }

    @Test
    fun depthScoreRejectsFlatUninformativeDepth() {
        val data = DepthData(
            mapWidth = 32,
            mapHeight = 24,
            normalizedDepth = FloatArray(32 * 24) { 0.5f },
            confidenceMap = FloatArray(32 * 24) { 0.90f },
            highResolutionDepth = FloatArray(64 * 48) { 0.5f },
            highResolutionWidth = 64,
            highResolutionHeight = 48
        )

        val score = AIFeatureQualityPolicy.scoreDepth(data, strictHighEnd = true)

        assertFalse(score.usable)
        assertTrue(score.reason.contains("flat"))
    }

    @Test
    fun depthScoreAcceptsConfidentHighResolutionDepth() {
        val width = 64
        val height = 48
        val depth = FloatArray(width * height) { index ->
            val x = index % width
            (x / (width - 1f)).coerceIn(0f, 1f)
        }
        val data = DepthData(
            mapWidth = width,
            mapHeight = height,
            normalizedDepth = depth,
            confidenceMap = FloatArray(depth.size) { 0.86f },
            edgeMap = FloatArray(depth.size) { index -> if (index % width == width / 2) 1f else 0f },
            temporallyRefined = true,
            multiFrameFusion = true,
            highResolutionDepth = FloatArray(128 * 96) { 0.5f },
            highResolutionWidth = 128,
            highResolutionHeight = 96,
            temporalHistoryWeightMean = 0.42f,
            circleOfConfusionRadius = FloatArray(128 * 96) { 1f },
            bokehRenderBackend = "VULKAN_VOGEL_SPIRAL"
        )

        val score = AIFeatureQualityPolicy.scoreDepth(data, strictHighEnd = true)

        assertTrue("score=${score.score} reason=${score.reason}", score.usable)
        assertTrue(score.score > 0.65f)
    }
}
