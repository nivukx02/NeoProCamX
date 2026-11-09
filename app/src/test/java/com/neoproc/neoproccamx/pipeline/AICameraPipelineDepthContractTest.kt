package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AICameraPipelineDepthContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt").readText()
    }

    @Test
    fun bitmapPathSchedulesDepthEstimatorWhenDepthTrackingIsEnabled() {
        val bitmapPath = source
            .substringAfter("private fun processBitmapFrame(")
            .substringBefore("private fun readMediaImageDescriptor(")

        assertTrue(
            bitmapPath.contains(
                "val depthStageAllowed = depthTrackingEnabled && heavyStageReadyForRecording(depthEstimatorDelegate)"
            )
        )
        assertTrue(
            bitmapPath.contains(
                "if (depthStageAllowed && frameIndex % depthEstimationStride == 0L)"
            )
        )
        assertTrue(
            bitmapPath.contains(
                "submitRetainedBitmapStage(depthExecutor, depthStageBusy, retainedBitmap)"
            )
        )
        assertTrue(bitmapPath.contains("depthEstimator.estimate(it, rotationDegrees)"))
        assertTrue(bitmapPath.contains("depthNode.resolve(depthScheduledNode"))
        assertTrue(bitmapPath.contains("AIFeatureQualityPolicy.qualifiedDepth(rawDepthData)?.let"))
        assertTrue(bitmapPath.contains("cachedDepthRef.set(CacheEntry(it, timestampMs))"))
    }

    @Test
    fun mediaImageIngressUsesOwnedBitmapDepthPathAndFeedsFocusEngine() {
        val mediaIngress = source
            .substringAfter("fun analyzeMediaImage(")
            .substringBefore("fun analyzeBitmap(")
        val bitmapPath = source
            .substringAfter("private fun processBitmapFrame(")
            .substringBefore("private fun readMediaImageDescriptor(")

        assertTrue(mediaIngress.contains("copyMediaImageToBitmap("))
        assertTrue(mediaIngress.contains("BitmapPendingFrame("))
        assertFalse(mediaIngress.contains("MediaImagePendingFrame("))
        assertTrue(bitmapPath.contains("focusEngine.selectSubjectAwareness("))
        assertTrue(bitmapPath.contains("depthData = depthData,"))
        assertTrue(bitmapPath.contains("AICameraFrameResult("))
    }
}
