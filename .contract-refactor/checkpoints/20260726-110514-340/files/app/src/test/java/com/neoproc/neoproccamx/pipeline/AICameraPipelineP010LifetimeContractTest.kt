package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AICameraPipelineP010LifetimeContractTest {
    @Test
    fun p010MediaImageBitmapCopySnapshotsBeforeArgbFill() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()
        val copyBlock = source
            .substringAfter("private fun copyMediaImageToBitmap(")
            .substringBefore("private class BitmapPool")

        assertTrue(copyBlock.contains("Yuv420ImageTools.isP010(image)"))
        assertTrue(copyBlock.contains("val snapshot = Yuv420ImageTools.snapshot(image)"))
        assertTrue(copyBlock.contains("Yuv420ImageTools.fillArgbPixels(snapshot"))
    }

    @Test
    fun cameraViewModelWiresFramePipelineIdleBeforeReaderClose() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        assertTrue(source.contains("camera.awaitFramePipelineIdleBeforeReaderClose"))
        assertTrue(source.contains("pipeline.awaitIdleBeforeReaderClose(timeoutMs)"))
    }
}
