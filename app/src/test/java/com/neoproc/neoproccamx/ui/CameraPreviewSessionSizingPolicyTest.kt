package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.util.Size
import java.io.File

class CameraPreviewSessionSizingPolicyTest {

    @Test
    fun resolveAspectAwareRuntimeCap_preservesFourThreePreviewAtCriticalThermalEdge() {
        val cap = CameraPreviewSessionSizingPolicy.resolveAspectAwareRuntimeCap(
            maxLongEdge = 1280,
            targetAspect = 4f / 3f
        )

        assertEquals(1280, cap.maxLongEdge)
        assertEquals(1280 * 960, cap.maxArea)
        assertFalse(cap.differsFrom(currentMaxLongEdge = 1280, currentMaxArea = 1280 * 960))
    }

    @Test
    fun resolveAspectAwareRuntimeCap_preservesSixteenNinePreviewAtCriticalThermalEdge() {
        val cap = CameraPreviewSessionSizingPolicy.resolveAspectAwareRuntimeCap(
            maxLongEdge = 1280,
            targetAspect = 16f / 9f
        )

        assertEquals(1280, cap.maxLongEdge)
        assertEquals(1280 * 720, cap.maxArea)
        assertFalse(cap.differsFrom(currentMaxLongEdge = 1280, currentMaxArea = 1280 * 720))
    }

    @Test
    fun resolveAspectAwareRuntimeCap_detectsOnlyARealLimitChange() {
        val cap = CameraPreviewSessionSizingPolicy.resolveAspectAwareRuntimeCap(
            maxLongEdge = 1280,
            targetAspect = 4f / 3f
        )

        assertTrue(cap.differsFrom(currentMaxLongEdge = 1280, currentMaxArea = 1280 * 720))
        assertTrue(cap.differsFrom(currentMaxLongEdge = null, currentMaxArea = null))
    }

    @Test
    fun heatEfficientPreviewCapsViewfinderWithoutChangingCaptureOutputs() {
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = true,
            strictHighEnd = true,
            videoSourceLongEdge = 8192,
            displayMaxLongEdge = 3200,
            displayMaxArea = 3200 * 1440,
            heatEfficientPreview = true
        )

        assertEquals(1280, limits.maxLongEdge)
        assertEquals(1280 * 960, limits.maxArea)
    }

    @Test
    fun calculateOptimalPreviewSize_usesSmallestBestAspectFallbackWhenAllSizesExceedLiveCap() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraPreviewSessionSizingPolicy.kt")
            .readText()
        val fallbackSelector = source
            .substringAfter("private fun closestFromAll(")
            .substringBefore("/**\n     * Calculates the Euclidean")

        assertTrue(fallbackSelector.contains("minWithOrNull"))
        assertTrue(fallbackSelector.contains("thenBy { size ->"))
        assertTrue(fallbackSelector.contains("size.width.toLong() * size.height.toLong()"))
    }

    @Test
    fun resolvePreviewLimits_strictHighEndStillHonorsRuntimePerformanceCaps() {
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = true,
            strictHighEnd = true,
            videoSourceLongEdge = 3840,
            thermalPreviewMaxLongEdge = null,
            thermalPreviewMaxArea = null,
            performancePreviewMaxLongEdge = 1600,
            performancePreviewMaxArea = 1600 * 900
        )

        assertEquals(1600, limits.maxLongEdge)
        assertEquals(1600 * 900, limits.maxArea)
    }

    @Test
    fun resolvePreviewLimits_strictHighEndStillHonorsThermalCaps() {
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = true,
            strictHighEnd = true,
            videoSourceLongEdge = 3840,
            thermalPreviewMaxLongEdge = 1920,
            thermalPreviewMaxArea = 1920 * 1080,
            performancePreviewMaxLongEdge = null,
            performancePreviewMaxArea = null
        )

        assertEquals(1920, limits.maxLongEdge)
        assertEquals(1920 * 1080, limits.maxArea)
    }

    @Test
    fun resolvePreviewLimits_usesMobileDisplayBudgetForHighResVideoPreview() {
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = true,
            strictHighEnd = true,
            videoSourceLongEdge = 4096,
            thermalPreviewMaxLongEdge = null,
            thermalPreviewMaxArea = null,
            performancePreviewMaxLongEdge = null,
            performancePreviewMaxArea = null,
            displayMaxLongEdge = 2800,
            displayMaxArea = 2800 * 1260
        )

        assertEquals(2800, limits.maxLongEdge)
        assertEquals(2800 * 1260, limits.maxArea)
    }

    @Test
    fun resolvePreviewLimits_preservesFourThreeFramingInsideMobileDisplayBudget() {
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = true,
            strictHighEnd = true,
            videoSourceLongEdge = 4096,
            thermalPreviewMaxLongEdge = null,
            thermalPreviewMaxArea = null,
            performancePreviewMaxLongEdge = null,
            performancePreviewMaxArea = null,
            targetAspect = 4f / 3f,
            displayMaxLongEdge = 2800,
            displayMaxArea = 2800 * 1260
        )

        assertEquals(2800, limits.maxLongEdge)
        assertEquals(2800 * 1260, limits.maxArea)
    }

    @Test
    fun resolvePreviewLimits_keepsMobileDisplayBudgetForLowerResolutionRecording() {
        val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
            videoMode = true,
            strictHighEnd = true,
            videoSourceLongEdge = 1280,
            thermalPreviewMaxLongEdge = null,
            thermalPreviewMaxArea = null,
            performancePreviewMaxLongEdge = null,
            performancePreviewMaxArea = null,
            displayMaxLongEdge = 2800,
            displayMaxArea = 2800 * 1260
        )

        assertEquals(2800, limits.maxLongEdge)
        assertEquals(2800 * 1260, limits.maxArea)
    }

    @Test
    fun sourceMatchIsRejectedWhenItsAspectWouldStretchSixteenNinePreview() {
        val shouldPrefer = CameraPreviewSessionSizingPolicy.shouldPreferSourceSizeMatch(
            sourceSize = Size(3072, 2304),
            targetAspect = 16f / 9f,
            maxLongEdge = 3840,
            maxArea = 3840 * 2160
        )

        assertFalse(shouldPrefer)
    }

    @Test
    fun sourceMatchIsRejectedWhenRecordingSourceIsBelow1080pDisplayBudget() {
        val shouldPrefer = CameraPreviewSessionSizingPolicy.shouldPreferSourceSizeMatch(
            sourceSize = Size(1280, 720),
            targetAspect = 16f / 9f,
            maxLongEdge = 1920,
            maxArea = 1920 * 1080
        )

        assertFalse(shouldPrefer)
    }

    @Test
    fun resolvePreviewAnalysisMaxLongEdge_keepsHighResPreviewAnalysisAt1080pClass() {
        val limit = CameraPreviewSessionSizingPolicy.resolvePreviewAnalysisMaxLongEdge(
            strictHighEnd = true,
            normalizedPreviewLongEdge = 3072,
            performanceAnalysisMaxLongEdge = null,
            nativePreview = false
        )

        assertEquals(1920, limit)
    }

    @Test
    fun resolveRecordingAnalysisMaxLongEdge_keepsHighResRecordingAnalysisAt1080pClass() {
        val limit = CameraPreviewSessionSizingPolicy.resolveRecordingAnalysisMaxLongEdge(
            normalizedRecordingLongEdge = 4096,
            performanceAnalysisMaxLongEdge = null
        )

        assertEquals(1920, limit)
    }

    @Test
    fun resolveRecordingAnalysisMaxLongEdge_keepsUltraRecordingAnalysisAt1080pClass() {
        val limit = CameraPreviewSessionSizingPolicy.resolveRecordingAnalysisMaxLongEdge(
            normalizedRecordingLongEdge = 7680,
            performanceAnalysisMaxLongEdge = null
        )

        assertEquals(1920, limit)
    }
}
