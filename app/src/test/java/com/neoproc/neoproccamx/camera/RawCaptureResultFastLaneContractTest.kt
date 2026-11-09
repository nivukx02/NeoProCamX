package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCaptureResultFastLaneContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    private val host by lazy {
        source("src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt")
    }
    private val controller by lazy {
        source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    }
    private val viewModel by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
    }

    @Test
    fun `host and controller expose a dedicated session-bound RAW result lane`() {
        val declaration =
            "onRawCaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)?"

        assertTrue(host.contains("var $declaration"))
        assertTrue(controller.contains("override var $declaration = null"))
    }

    @Test
    fun `RAW result lane runs after authoritative boundaries and before heavyweight observers`() {
        val callback = controller
            .substringAfter("private val captureCallback")
            .substringBefore("override fun onCaptureFailed(")
        val validation = callback.indexOf(
            "if (binding?.session !== s || binding.generation != sessionGeneration.get()) {"
        )
        val rawResumeBoundary = callback.indexOf(
            "completeRawResumeBoundary(rawResumeBoundary, s, result)"
        )
        val p010Boundary = callback.indexOf("p010StopBoundary.state.onMarkerCaptureResult(")
        val generationSnapshot = callback.indexOf("val captureSessionGeneration = binding.generation")
        val rawFastLane = callback.indexOf(
            "publishRawCaptureResultFastLane(result, captureSessionGeneration)"
        )
        val previewHealth = callback.indexOf("previewHealthMonitor.trackCaptureResult()")
        val autofocus = callback.indexOf("maybeCompleteTapAfScan(result)")
        val generalPublication = callback.indexOf("processResult(result, captureSessionGeneration)")

        assertTrue(validation >= 0)
        assertTrue(rawResumeBoundary > validation)
        assertTrue(p010Boundary > rawResumeBoundary)
        assertTrue(generationSnapshot > p010Boundary)
        assertTrue(rawFastLane > generationSnapshot)
        assertTrue(previewHealth > rawFastLane)
        assertTrue(autofocus > rawFastLane)
        assertTrue(generalPublication > rawFastLane)
    }

    @Test
    fun `RAW lane forwards exact values and isolates only non-fatal failures`() {
        val publisher = controller
            .substringAfter("private fun publishRawCaptureResultFastLane(")
            .substringBefore("private val captureCallback")

        assertTrue(
            publisher.contains(
                "onRawCaptureResultWithSessionGeneration?.invoke(result, captureSessionGeneration)"
            )
        )
        assertTrue(publisher.contains("catch (throwable: Throwable)"))
        assertTrue(
            publisher.contains(
                "if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable"
            )
        )
        assertTrue(
            publisher.contains("RAW capture-result fast-lane consumer failed independently")
        )
    }

    @Test
    fun `ViewModel binds RAW coordinator only to the fast lane and releases it on teardown`() {
        val wiring = viewModel
            .substringAfter("camera.onCaptureResult = { result ->")
            .substringBefore("camera.onMotionSensorSample")
        val generalFanOut = wiring
            .substringAfter("camera.onCaptureResultWithSessionGeneration =")

        assertTrue(
            wiring.contains(
                "camera.onRawCaptureResultWithSessionGeneration = { result, sessionGeneration ->"
            )
        )
        assertTrue(
            wiring.contains(
                "rawPreviewPresentationCoordinator.onCaptureResult(result, sessionGeneration)"
            )
        )
        assertFalse(generalFanOut.contains("rawPreviewPresentationCoordinator.onCaptureResult("))
        assertTrue(
            viewModel.substringAfter("override fun onCleared()").contains(
                "camera.onRawCaptureResultWithSessionGeneration = null"
            )
        )
    }
}
