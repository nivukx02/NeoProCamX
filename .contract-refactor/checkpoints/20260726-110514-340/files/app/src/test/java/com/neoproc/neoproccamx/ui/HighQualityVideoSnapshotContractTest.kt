package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighQualityVideoSnapshotContractTest {
    private fun read(path: String): String = File(path).readText().replace("\r\n", "\n")

    private val activity = read("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
    private val viewModel = read("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
    private val controller = read("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    private val stillCapture = read("src/main/java/com/neoproc/neoproccamx/capture/StillCapture.kt")
    private val imagePipeline = read("src/main/java/com/neoproc/neoproccamx/capture/StillCaptureImagePipeline.kt")
    private val layout = read("src/main/res/layout/activity_main.xml")

    @Test
    fun bottomLeftSlotCapturesPhotoAndNoLongerSwitchesCamera() {
        assertTrue(layout.contains("android:id=\"@+id/btnPhotoCapture\""))
        assertFalse(layout.contains("btnFlipCamera"))
        assertFalse(layout.contains("btnTopFlipCamera"))
        assertTrue(activity.contains("binding.btnPhotoCapture.setOnClickListener { triggerHighQualityPhotoCapture() }"))
        assertFalse(activity.contains("switchCameraFromButton"))
    }

    @Test
    fun fullResolutionJpegPathIsIndependentFromPreviewAndRecordingState() {
        val captureMethod = viewModel.substringAfter("fun captureHighQualityPhoto(): Boolean")
            .substringBefore("fun isHighQualityPhotoCaptureInFlight")
        assertTrue(captureMethod.contains("camera.jpegReader == null"))
        assertTrue(captureMethod.contains("stillCapture.captureSingle(raw = false)"))
        assertTrue(viewModel.contains("heatEfficientPreview = true"))
        assertTrue(controller.contains("CameraDevice.TEMPLATE_VIDEO_SNAPSHOT"))
        assertTrue(controller.contains("CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT"))
        assertTrue(controller.contains("builder.set(CaptureRequest.JPEG_QUALITY, effectiveJpegQuality().toByte())"))
        assertTrue(controller.contains("builder.set(CaptureRequest.JPEG_ORIENTATION, outputJpegOrientation())"))
        assertTrue(controller.contains("handler.postAtFrontOfQueue(action)"))
        assertTrue(controller.contains("runPrioritizedCameraControl {"))
        assertTrue(controller.contains("session.stopRepeating()"))
        assertTrue(controller.contains("!videoSnapshot"))
        assertTrue(controller.contains("resumePreviewRepeatingAfterStillCapture()"))
        assertTrue(controller.contains("maximumResolutionStillCaptureActive = true"))
        assertTrue(controller.contains("maximumResolutionPixelModeActive()"))
        assertTrue(controller.contains("normalSessionPhotoSizes"))
        assertTrue(controller.contains("maximumResolutionSessionPhotoSizes"))
        assertFalse(imagePipeline.contains("strictHighEndEnabled() || !colorScience.isNeutralLook()"))
    }

    @Test
    fun oneKVideoSelectionCannotReduceTheIndependentJpegReaderResolution() {
        assertTrue(controller.contains("val jpegSessionSizes = when"))
        assertTrue(controller.contains("else -> normalSessionPhotoSizes"))
        assertFalse(controller.contains("recordingEncoderTargetSize ?: maxPhotoSize"))
        assertFalse(controller.contains("maxPhotoSize = recordingEncoderTargetSize"))
    }

    @Test
    fun stillCallbacksDoNotReplaceRawVideoOwnershipOrLineageEvidence() {
        assertTrue(viewModel.contains("registerRawCallback = false"))
        assertTrue(viewModel.contains("registerCaptureResultCallback = false"))
        assertTrue(viewModel.contains("recordCamera2FrameLineageCaptureResult(result)"))
        assertTrue(viewModel.contains("stillCapture.onCaptureResult(result)"))
    }

    @Test
    fun rawPreviewCannotStarvePhotoSubmissionOrItsWatchdog() {
        assertTrue(stillCapture.contains("Executors.newSingleThreadExecutor"))
        assertTrue(stillCapture.contains("Thread(runnable, \"StillCaptureControl\")"))
        assertTrue(stillCapture.contains("captureControlScope.launch"))
        assertTrue(stillCapture.contains("captureTimeoutJob = captureControlScope.launch"))
        assertTrue(stillCapture.contains("captureControlDispatcher.close()"))
        val beginCapture = stillCapture.substringAfter("private fun beginCapture(")
            .substringBefore("private fun scheduleTimeout")
        assertTrue(beginCapture.indexOf("trigger()") < beginCapture.indexOf("_events.emit(CaptureEvent.Started)"))
    }
}
