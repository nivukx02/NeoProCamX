package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SegmentationModelAndPipelineContractTest {

    @Test
    fun segmentationDataCarriesClassChannelsGuideAndTemporalMetrics() {
        val classChannels = Array(6) { classIndex -> FloatArray(4) { classIndex / 5f } }
        val data = SegmentationData(
            maskWidth = 2,
            maskHeight = 2,
            foregroundMask = byteArrayOf(0, 127, 127, 255.toByte()),
            approximateDepth = floatArrayOf(0f, 0.3f, 0.6f, 1f),
            foregroundCoverage = 0.5f,
            subjectDistanceScore = 0.4f,
            multiClassMask = byteArrayOf(0, 1, 4, 5),
            classLabels = SegmentationEngine.MULTICLASS_LABELS,
            confidenceMap = FloatArray(4) { 0.8f },
            classConfidenceChannels = classChannels,
            guideWidth = 4,
            guideHeight = 4,
            guideLuma = FloatArray(16) { 0.2f },
            temporalMotionMean = 0.12f,
            temporalHistoryWeightMean = 0.63f,
            matteBackend = "GUIDED_FILTER_TEMPORAL_WARP"
        )

        assertEquals(6, data.classConfidenceChannels?.size)
        assertEquals("Hair", data.classLabels[1])
        assertEquals(4, data.guideWidth)
        assertEquals(0.63f, data.temporalHistoryWeightMean, 0.0001f)
        assertEquals("GUIDED_FILTER_TEMPORAL_WARP", data.matteBackend)
    }

    @Test
    fun mediaImageSegmentationOwnsImageCloseAndPipelineSuppliesLumaGuide() {
        val segmentationSource = sourceFile(
            "app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationEngine.kt",
            "src/main/java/com/neoproc/neoproccamx/ai/SegmentationEngine.kt"
        ).readText()
        val pipelineSource = sourceFile(
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt",
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()

        assertTrue(segmentationSource.contains("MediaImageBuilder(image).build()"))
        assertTrue(segmentationSource.contains("finally"))
        assertTrue(segmentationSource.contains("image.close()"))
        assertTrue(pipelineSource.contains("segmentationLumaGuide"))
        assertTrue(pipelineSource.contains("trackSegmentationMediaImageFrame"))
        assertTrue(pipelineSource.contains("segmentationEngine.segment(image, frame.timestampMs, rotationDegrees"))
        assertNotNull(segmentationSource)
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("Missing source file in candidates: ${candidates.joinToString()}")
}
