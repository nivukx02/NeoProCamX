package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AICameraPipelineP010LifetimeContractTest {
    @Test
    fun p010MediaImageBitmapCopyUsesBoundedSynchronousHeapOutput() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()
        val copyBlock = source
            .substringAfter("private fun copyMediaImageToBitmap(")
            .substringBefore("private class BitmapPool")

        assertTrue(copyBlock.contains("Yuv420ImageTools.fillArgbPixelsFromOpenImage("))
        assertTrue(copyBlock.contains("bitmap.setPixels(outPixels"))
        assertTrue(!copyBlock.contains("Yuv420ImageTools.snapshot(image)"))
    }

    @Test
    fun cameraViewModelClosesReadersInsideSealedFramePipelineBarrier() {
        val viewModel = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val coordinator = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraReaderCloseCoordinator.kt"
        ).readText()

        assertTrue(viewModel.contains("camera.closeReadersInsideFramePipelineBarrier"))
        assertTrue(viewModel.contains("CameraReaderCloseCoordinator(pipeline, stillCapture, rawVideoManager)"))
        assertTrue(coordinator.contains("pipeline::sealImageIngressForReaderClose"))
        assertTrue(coordinator.contains("pipeline::awaitIdleBeforeReaderClose"))
        assertTrue(coordinator.contains("stillCapture::sealImageReaderIngress"))
        assertTrue(coordinator.contains("rawVideoManager::awaitImageReaderOwnershipIdle"))
        assertTrue(coordinator.contains("closeReaders()"))
    }
}
