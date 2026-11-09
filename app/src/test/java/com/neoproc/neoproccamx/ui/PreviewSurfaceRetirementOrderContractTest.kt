package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceRetirementOrderContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `non recording surface loss unregisters targets before retaining EGL inputs`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val clear = viewModel
            .substringAfter("private fun clearPreviewSurface(): PreviewSurfaceRetirementTicket")
            .substringBefore("private fun completePreviewSurfaceRetirement")
        val unregister = clear.indexOf("disablePersistentPreviewPipeline(releaseInputs = false)")
        val clearCameraTarget = clear.indexOf("camera.setPreviewSurface(null)")

        assertTrue(unregister >= 0)
        assertTrue(clearCameraTarget > unregister)
        assertFalse(clear.contains("disablePersistentPreviewPipeline(releaseInputs = true)"))
        assertFalse(clear.contains("previewGlCompositor.releaseAsync()"))
    }

    @Test
    fun `EGL retirement happens only after blocking Camera2 close returns`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val close = viewModel
            .substringAfter("private fun closeCameraInternal(")
            .substringBefore("fun handleAppBackgrounded()")
        val cameraClose = close.indexOf("camera.closeCamera()")
        val releasePresentation = close.indexOf(
            "releasePreviewPresentationResources()",
            startIndex = cameraClose
        )
        val completeRetirement = close.indexOf("completePreviewSurfaceRetirement(retirementTicket)")

        assertTrue(cameraClose >= 0)
        assertTrue(releasePresentation > cameraClose)
        assertTrue(completeRetirement > releasePresentation)
    }

    @Test
    fun `replacement binding is deferred while retirement owns the generation`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val onReady = viewModel
            .substringAfter("fun onSurfaceReady(surface: Surface?)")
            .substringBefore("fun setPreviewSurface(surface: Surface?)")
        val defer = onReady.indexOf("previewSurfaceRetirement.deferReplacement(surface)")
        val bind = onReady.indexOf("bindPreviewSurface(surface)")

        assertTrue(defer >= 0)
        assertTrue(bind > defer)
    }

    @Test
    fun `posted surface destruction is generation gated`() {
        val view = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/CameraVulkanPreviewView.kt"
        )
        val destroyed = view
            .substringAfter("override fun surfaceDestroyed(holder: SurfaceHolder)")
            .substringBefore("override fun onDetachedFromWindow()")

        assertTrue(destroyed.contains("surfaceCallbackGenerationGate.publish(null)"))
        assertTrue(destroyed.contains("post { dispatchSurfaceCallbackIfCurrent(event) }"))
    }
}
