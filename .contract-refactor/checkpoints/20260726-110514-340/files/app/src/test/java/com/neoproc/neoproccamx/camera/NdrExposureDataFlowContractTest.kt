package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents the live NDR exposure loop from silently losing either highlight input again. */
class NdrExposureDataFlowContractTest {

    @Test
    fun `view model forwards luma and red highlight data to camera`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val call = source
            .substringAfter("camera.setHdrExposureSceneMetrics(")
            .substringBefore(")\n")
        val exposureFlow = source
            .substringBefore("camera.setHdrExposureSceneMetrics(")
            .substringAfterLast("combine(")

        assertTrue(call.contains("highlightClipFraction"))
        assertTrue(call.contains("redHighlightClipFraction"))
        assertTrue(call.contains("analysisValid"))
        assertTrue(exposureFlow.contains("pipeline.avgLuma"))
        assertTrue(exposureFlow.contains("pipeline.histogram"))
        assertTrue(exposureFlow.contains("pipeline.redHighlightClipFraction"))
    }

    @Test
    fun `ndr mode requests live analysis independently of hidden hud monitors`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val request = source
            .substringAfter("private fun analysisFeedRequested(): Boolean")
            .substringBefore("private fun syncPreviewAnalysisFeed")

        assertTrue(request.contains("Bt2100TransportPolicy.isNeoBt2100HdrVersion"))
    }
}
