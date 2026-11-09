package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighQualityVideoSnapshotContractTest {
    private fun read(path: String): String = File(path).readText().replace("\r\n", "\n")

    private val activity = read("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
    private val viewModel = read("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
    private val rawPreviewCoordinator =
        read("src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt")
    private val controller = read("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    private val stillCapture = read("src/main/java/com/neoproc/neoproccamx/capture/StillCapture.kt")
    private val imagePipeline = read("src/main/java/com/neoproc/neoproccamx/capture/StillCaptureImagePipeline.kt")
    private val enhancementEngine = read("src/main/java/com/neoproc/neoproccamx/ai/EnhancementEngine.kt")
    private val neuralDenoise = read("src/main/java/com/neoproc/neoproccamx/ai/NeuralDenoiseEngine.kt")
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
        assertTrue(captureMethod.contains("StillPhotoCaptureModePolicy.resolve"))
        assertTrue(captureMethod.contains("stillCapture.captureSingle(raw = photoPlan.requestRawCompanion)"))
        assertTrue(captureMethod.contains("stillCapture.captureHDR"))
        assertTrue(viewModel.contains("heatEfficientPreview = true"))
        assertTrue(controller.contains("CameraDevice.TEMPLATE_VIDEO_SNAPSHOT"))
        assertTrue(controller.contains("CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT"))
        assertTrue(controller.contains("builder.set(CaptureRequest.JPEG_QUALITY, effectiveJpegQuality().toByte())"))
        assertTrue(controller.contains("builder.set(CaptureRequest.JPEG_ORIENTATION, outputJpegOrientation())"))
        assertTrue(controller.contains("handler.postAtFrontOfQueue(action)"))
        assertTrue(controller.contains("runPrioritizedCameraControl(token) {"))
        assertTrue(controller.contains("session.stopRepeating()"))
        assertTrue(controller.contains("!videoSnapshot"))
        assertTrue(controller.contains("resolveCamera2PipelineRequestPlan(streamGraph).targets.includeRawSensor"))
        val pauseIdleRawBlock = controller.substringAfter("val pauseIdleRawRepeating =")
            .substringBefore("if (pauseIdleRawRepeating)")
        assertTrue(pauseIdleRawBlock.contains("rawRepeatingTargetActive"))
        assertTrue(controller.contains("resumePreviewRepeatingAfterStillCapture(token)"))
        assertTrue(controller.contains("val dedicatedMaximumResolution ="))
        assertTrue(
            controller.contains(
                "if (dedicatedMaximumResolution || dedicatedP010Cadence)"
            )
        )
        assertTrue(
            controller.contains(
                "maximumResolutionStillCaptureActive = dedicatedMaximumResolution"
            )
        )
        assertTrue(controller.contains("maximumResolutionPixelModeActive()"))
        assertTrue(controller.contains("normalSessionPhotoSizes"))
        assertTrue(controller.contains("maximumResolutionSessionPhotoSizes"))
        assertTrue(controller.contains("val videoRenderContractActive = !forStillCapture"))
        assertTrue(controller.contains("stillPictureAutoFocusMode()"))
        assertTrue(controller.contains("val videoSnapshot = recordingSnapshot"))
        assertFalse(controller.contains("val previewContinuitySnapshot ="))
        assertTrue(controller.contains("cadenceProtectedSnapshot = videoSnapshot"))
        assertTrue(controller.contains("if (!forStillCapture || cadenceProtectedSnapshot)"))
        assertTrue(imagePipeline.contains("!colorScience.isNeutralLook() || aiEnhancementEnabledProvider()"))
    }

    @Test
    fun neutralStillsFastSaveHalJpegWhileProcessedStillsUseSelectedDetailProfile() {
        val postProcessDecision = imagePipeline
            .substringAfter("fun shouldPostProcessSingleCapture(): Boolean =")
            .substringBefore("fun requiresAspectCrop")
        assertTrue(imagePipeline.contains("private fun stillEnhancementRequested(): Boolean"))
        assertTrue(imagePipeline.contains("aiEnhancementEnabledProvider() || strictHighEndEnabled()"))
        assertTrue(imagePipeline.contains("enhancementRequested = stillEnhancementRequested()"))
        assertTrue(imagePipeline.contains("StillSingleEnhancementPolicy.shouldEnhance("))
        assertTrue(imagePipeline.contains("neutralLook = colorScience.isNeutralLook()"))
        assertFalse(postProcessDecision.contains("strictHighEndEnabled()"))
        assertTrue(postProcessDecision.contains("!colorScience.isNeutralLook()"))
        assertTrue(postProcessDecision.contains("aiEnhancementEnabledProvider()"))
        assertTrue(imagePipeline.contains("EliteDetailProfilePolicy.resolve(videoDetailProfileProvider())"))
    }

    @Test
    fun hdrBracketSerializesFullResolutionJpegsInsteadOfBurstingVendorBuffers() {
        assertTrue(
            controller.contains("submitSerializedStillCaptureRequests(session, requests, ownerToken)")
        )
        assertTrue(controller.contains("STILL_HDR_SERIAL_FRAME_SETTLE_MS"))
        assertTrue(controller.contains("handler.postDelayed("))
        assertTrue(controller.contains("{ submit(index + 1) }"))
        assertTrue(controller.contains("builder.setTag(token)"))
        assertFalse(controller.contains("session.captureBurst("))
    }

    @Test
    fun fullResolutionStillRefinementAvoidsRecursiveNeuralTilesAndCrossTileTemporalGhosting() {
        assertTrue(imagePipeline.contains("temporalDenoiseWeight = 0f"))
        assertTrue(enhancementEngine.contains("preferNeuralDenoise = false"))
        assertTrue(enhancementEngine.contains("temporalDenoiseWeight = 0f"))
        assertTrue(enhancementEngine.contains("enhanceFixedFullResolutionTile("))
        assertTrue(enhancementEngine.contains("FullResolutionStillRefinementPolicy.resolve(fixedConfig)"))
        assertTrue(enhancementEngine.contains("applyFusedFullResolutionSpatialRefinement("))
        assertTrue(enhancementEngine.contains("residualChromaSuppression = fullResolutionDecision.residualChromaSuppression"))
        assertTrue(neuralDenoise.contains("request.preferNeuralModel && !thermalFallback"))
        assertTrue(neuralDenoise.contains("if (request.temporalWeight <= 0.001f)"))
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
        assertTrue(viewModel.contains("recordCamera2FrameLineageCaptureResult(result, sessionGeneration)"))
        assertTrue(viewModel.contains("stillCapture.onCaptureResult(result)"))
        assertTrue(rawPreviewCoordinator.contains("if (stillCaptureExpectsRaw())"))
        assertTrue(rawPreviewCoordinator.contains("onStillRawImageAvailable(reader)"))
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
