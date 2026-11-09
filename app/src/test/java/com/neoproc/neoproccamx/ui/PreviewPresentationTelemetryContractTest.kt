package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPresentationTelemetryContractTest {
    private val viewModelSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val activitySource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"
    ).readText()
    private val previewViewSource = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/CameraVulkanPreviewView.kt"
    ).readText()

    @Test
    fun `visible route cadence is committed only after presentation acceptance`() {
        val listenerBlock = viewModelSource
            .substringAfter("previewGlCompositor.setOnFramePresentedListener")
            .substringBefore("previewGlCompositor.setOnInputFrameListener")
        val publishBlock = viewModelSource
            .substringAfter("private fun publishPreviewDisplayPresent(")
            .substringBefore("private fun camera2FrameLineageRuntimeMetadata")

        assertFalse(listenerBlock.contains("recordPreviewPixelRouteStage(\n                        PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP"))
        assertFalse(listenerBlock.contains("recordPreviewPixelRouteStage(\n                    PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP"))
        assertTrue(listenerBlock.contains("PreviewPixelRouteStage.DIRECT_VISIBLE_SWAP"))
        assertTrue(listenerBlock.contains("PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP"))
        assertTrue(publishBlock.contains("val event = accepted.event"))
        assertTrue(publishBlock.contains("stage = accepted.visibleRouteStage"))
        assertTrue(publishBlock.indexOf("recordPreviewPixelRouteStage(") < publishBlock.indexOf("pipeline.onDisplayedFrame("))
    }

    @Test
    fun `final display fps telemetry is persisted by NeoLogger`() {
        val previewFpsListenerBlock = activitySource
            .substringAfter("binding.glSurface.setOnPreviewFpsListener")
            .substringBefore("binding.glSurface.setOnSurfaceReadyListener")

        assertTrue(previewFpsListenerBlock.contains("NeoLogger.i("))
        assertFalse(previewFpsListenerBlock.contains("Log.i("))
    }

    @Test
    fun `qa cadence logs exact presentation window without changing smoothed policy input`() {
        val previewFpsListenerBlock = activitySource
            .substringAfter("binding.glSurface.setOnPreviewFpsListener")
            .substringBefore("binding.glSurface.setOnSurfaceReadyListener")

        assertTrue(previewFpsListenerBlock.contains("vm.onPreviewFpsSample(fps)"))
        assertTrue(
            previewFpsListenerBlock.contains(
                "val measuredFps = binding.glSurface.latestMeasuredPreviewFps()"
            )
        )
        assertTrue(previewFpsListenerBlock.contains("?: return@setOnPreviewFpsListener"))
        assertFalse(previewFpsListenerBlock.contains("latestMeasuredPreviewFps() ?: fps"))
        assertTrue(previewFpsListenerBlock.contains("String.format(Locale.US, \"%.3f\", measuredFps)"))
        assertTrue(previewFpsListenerBlock.contains("smoothedPreviewFps="))
        assertTrue(
            previewViewSource.contains(
                "fun latestMeasuredPreviewFps(): Float? = fpsTracker.latestStableMeasuredFps()"
            )
        )
    }
}
