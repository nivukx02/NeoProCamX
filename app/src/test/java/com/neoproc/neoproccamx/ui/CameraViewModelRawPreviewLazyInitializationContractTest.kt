package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewModelRawPreviewLazyInitializationContractTest {
    @Test
    fun `RAW result callback does not force compositor coordinator during ViewModel init`() {
        val candidates = listOf(
            File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"),
            File("app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        )
        val viewModelFile = candidates.first { it.isFile }
        val source = viewModelFile.readText()
        val initialization = source
            .substringAfter("camera.onRawCaptureResultWithSessionGeneration =")
            .substringBefore("camera.onCaptureResultWithSessionGeneration =")

        assertTrue(source.contains("rawPreviewPresentationCoordinator: RawPreviewPresentationCoordinator by lazy"))
        assertTrue(initialization.contains("{ result, sessionGeneration ->"))
        assertTrue(initialization.contains("rawPreviewPresentationCoordinator.onCaptureResult("))
        assertFalse(initialization.contains("rawPreviewPresentationCoordinator::onCaptureResult"))
        assertTrue(
            source.indexOf("private val previewGlCompositor = PreviewGlCompositor()") >
                source.indexOf("camera.onRawCaptureResultWithSessionGeneration =")
        )
    }

    @Test
    fun `render-profile readiness collector starts only after compositor dependencies exist`() {
        val candidates = listOf(
            File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"),
            File("app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        )
        val viewModelFile = candidates.first { it.isFile }
        val source = viewModelFile.readText()
        val previewCompositor = source.indexOf("private val previewGlCompositor = PreviewGlCompositor()")
        val hdrCompositor = source.indexOf(
            "private val hdr10PqPreviewCompositor = Hdr10PqPreviewCompositor()"
        )
        val readinessObserver = source.indexOf(
            "NdrRenderProfileLifecycleCoordinator.observeReadyGenerations("
        )
        val helper = requireNotNull(viewModelFile.parentFile)
            .resolve("NdrRenderProfileLifecycleCoordinator.kt")
            .readText()

        assertTrue(previewCompositor >= 0)
        assertTrue(hdrCompositor > previewCompositor)
        assertTrue(readinessObserver > hdrCompositor)
        assertTrue(helper.contains("scope.launch(Dispatchers.Default)"))
        assertTrue(helper.contains("readyGenerations.collect"))
        assertTrue(helper.contains("isGenerationCurrent(readyGeneration)"))
        assertTrue(helper.contains("isModeActive()"))
        assertFalse(source.substring(readinessObserver).contains(
            "rawPreviewPresentationCoordinator::onNdrRenderProfileReady"
        ))
    }
}
