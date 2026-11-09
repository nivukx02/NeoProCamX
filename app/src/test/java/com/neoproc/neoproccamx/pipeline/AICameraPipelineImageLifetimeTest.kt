package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AICameraPipelineImageLifetimeTest {
    @Test
    fun retainedResourceClosesOnlyAfterOwnerAndAsyncLeaseRelease() {
        val finalReleases = AtomicInteger(0)
        val retained = AICameraRetainedResource("image") {
            finalReleases.incrementAndGet()
        }

        assertEquals("image", retained.retain())

        retained.release()
        assertEquals(0, finalReleases.get())

        retained.release()
        assertEquals(1, finalReleases.get())
        assertNull(retained.retain())

        retained.release()
        assertEquals(1, finalReleases.get())
    }

    @Test
    fun closedImageFailureClassificationIsNarrowAndCauseAware() {
        assertTrue(
            AICameraImageLifetimePolicy.isClosedImageFailure(
                IllegalStateException("Image is already closed")
            )
        )
        assertTrue(
            AICameraImageLifetimePolicy.isClosedImageFailure(
                RuntimeException("wrapper", IllegalStateException("Image is closed"))
            )
        )
        assertFalse(
            AICameraImageLifetimePolicy.isClosedImageFailure(
                IllegalStateException("MediaPipe model is unavailable")
            )
        )
    }

    @Test
    fun p010BufferInaccessibleFailureIsTreatedAsStaleIngress() {
        assertTrue(
            AICameraImageLifetimePolicy.isClosedImageFailure(
                IllegalStateException("buffer is inaccessible")
            )
        )
    }

    @Test
    fun mediaImagesAreSnapshottedAndReleasedBeforeQueueing() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()
        val analyzeBlock = source
            .substringAfter("fun analyzeMediaImage(")
            .substringBefore("fun analyzeBitmap(")

        val copyIndex = analyzeBlock.indexOf("copyMediaImageToBitmap(")
        val releaseIndex = analyzeBlock.indexOf("releaseMediaImageSource(", copyIndex)
        val enqueueIndex = analyzeBlock.indexOf("enqueueFrame(")
        assertTrue(copyIndex >= 0)
        assertTrue(releaseIndex > copyIndex)
        assertTrue(enqueueIndex > releaseIndex)
        assertTrue(analyzeBlock.contains("BitmapPendingFrame("))
        assertFalse(analyzeBlock.contains("MediaImagePendingFrame("))
        assertFalse(analyzeBlock.contains("AICameraRetainedResource(image)"))
    }

    @Test
    fun mediaIngressDropsStalePlaneBuffersWithoutReportingAnAiFailure() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()
        val analyzeBlock = source
            .substringAfter("fun analyzeMediaImage(")
            .substringBefore("fun analyzeBitmap(")

        assertTrue(analyzeBlock.contains("AICameraImageLifetimePolicy.isClosedImageFailure"))
        assertTrue(analyzeBlock.contains("droppedFrameCounter.incrementAndGet()"))
        assertTrue(analyzeBlock.contains("Skipping stale media image ingress"))
    }

    @Test
    fun bitmapStagesRetainTheirOwnLeasePastFrameDrain() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()
        val processBlock = source
            .substringAfter("private fun processBitmapFrame(")
            .substringBefore("private fun readMediaImageDescriptor(")

        assertTrue(processBlock.contains("val retainedBitmap = frame.retainedBitmap"))
        assertTrue(processBlock.contains("submitRetainedBitmapStage("))
        assertFalse(processBlock.contains("submitBitmapStage("))
        assertFalse(processBlock.contains("submitStage(faceExecutor"))
        assertFalse(processBlock.contains("submitStage(poseExecutor"))
        assertFalse(processBlock.contains("submitStage(sceneExecutor"))
    }
}
