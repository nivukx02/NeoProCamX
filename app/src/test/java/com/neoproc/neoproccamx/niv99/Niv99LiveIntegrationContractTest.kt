package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Niv99LiveIntegrationContractTest {
    private val appRoot = sequenceOf(File("."), File("../../app"))
        .first { File(it, "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").isFile }
    private val framePipeline = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
    ).readText()
    private val cameraViewModel = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val cameraControllerHost = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt"
    ).readText()
    private val cameraController = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
    ).readText()
    private val metadataPublisher = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/ui/Niv99Camera2MetadataPublisher.kt"
    ).readText()
    private val runtime = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
    ).readText()
    private val bridge = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
    ).readText()
    private val nativeBridge = File(appRoot, "src/main/cpp/vulkan_bridge.cpp").readText()
    private val realtimeShader = File(appRoot,
        "src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp"
    ).readText()
    private val offscreenNativeBridge = File(appRoot,
        "src/main/cpp/offscreen_egl_encoder_bridge.cpp"
    ).readText()
    private val videoPixelPolicy = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/niv99/Niv99VideoPixelApplicationPolicy.kt"
    ).readText()
    private val liveCoordinator = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/niv99/android/Niv99LiveCaptureCoordinator.kt"
    ).readText()
    private val sensorPipelineIntegration = File(appRoot,
        "src/main/java/com/neoproc/neoproccamx/ui/SensorPipelineIntegration.kt"
    ).readText()

    @Test
    fun `live Camera2 and Vulkan paths are isolated from NIV99`() {
        val generalCallback = cameraViewModel
            .substringAfter("camera.onCaptureResultWithSessionGeneration = captureResult@")
            .substringBefore("camera.onMotionSensorSample")
        val preparation = framePipeline
            .substringAfter("private fun prepareNiv99TuningForHardwareFrame(")
            .substringBefore("private fun scheduleHardwareGpuDispatchDrain()")
        val submit = framePipeline
            .substringAfter("preparedLookup: Niv99MetadataLookupStatus")
            .substringBefore("private fun submitImageToVulkan(")

        assertTrue(cameraControllerHost.contains(
            "var onNiv99CaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)?"
        ))
        assertTrue(cameraController.contains(
            "override var onNiv99CaptureResultWithSessionGeneration: " +
                "((TotalCaptureResult, Long) -> Unit)? = null"
        ))
        assertTrue(cameraViewModel.contains(
            "camera.onNiv99CaptureResultWithSessionGeneration = null"
        ))
        assertFalse(cameraViewModel.contains(
            "camera.onNiv99CaptureResultWithSessionGeneration = niv99CaptureResult@"
        ))
        assertTrue(cameraViewModel.contains("npv10Recording.setNiv99FrameProcessor(null)"))
        assertTrue(cameraViewModel.contains("previewGlCompositor.setNiv99FrameProcessor(null)"))
        assertTrue(generalCallback.contains("pipeline.onCaptureResult(result, sessionGeneration)"))
        assertFalse(generalCallback.contains("publishNiv99CaptureMetadata(result, sessionGeneration)"))
        assertEquals(
            0,
            Regex("publishNiv99CaptureMetadata\\(result, sessionGeneration\\)")
                .findAll(cameraViewModel)
                .count()
        )
        assertTrue(metadataPublisher.contains(
            "if (!Niv99ExecutionBoundaryPolicy.LIVE_CAPTURE_METADATA_ENABLED) return"
        ))
        assertTrue(metadataPublisher.contains("pipeline.onNiv99CaptureResult("))
        assertTrue(preparation.contains(
            "if (!Niv99ExecutionBoundaryPolicy.allowsLivePixelProcessing(recordingPath))"
        ))
        assertTrue(preparation.contains("niv99LiveCapture.awaitTuningForImage("))
        assertTrue(preparation.contains("timeoutNs = runtimeNiv99ExactMetadataWaitNs"))
        assertTrue(preparation.contains("niv99LiveCapture.copyTuningForImage("))
        assertTrue(preparation.contains("sensorTimestampNs = identity.sensorTimestampNs"))
        assertTrue(submit.contains("it.identity.sessionGeneration == record.registrySessionGeneration"))
        assertTrue(submit.contains("it.identity.frameNumber == identity.frameNumber"))
        assertTrue(framePipeline.contains("preparedHardwareFrames.offer(slot)"))
        assertTrue(framePipeline.contains("hardwareGpuSubmitExecutor.execute"))
        assertFalse(submit.contains("awaitTuningForImage("))
    }

    @Test
    fun `capture callback queues tuning in order and consumers perform cache only lookup`() {
        val capturePublication = liveCoordinator
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun onAnalysisFrame(")
        val lookup = liveCoordinator
            .substringAfter("fun copyTuningForImage(")
            .substringBefore("fun onPixelRenderCompleted(")

        val metadataPublish = capturePublication.indexOf("ring.publish(")
        val orderedEnqueue = capturePublication.indexOf("enqueueOrderedTuningWork(identity)")
        val publicationReturn = capturePublication.lastIndexOf("Niv99MetadataStatus.VALID")
        assertTrue(metadataPublish >= 0)
        assertTrue(orderedEnqueue > metadataPublish)
        assertTrue(publicationReturn > orderedEnqueue)
        assertFalse(capturePublication.contains("resolveAndCachePublishedTuning("))
        assertTrue(liveCoordinator.contains("private fun drainOrderedTuningWork()"))
        assertTrue(liveCoordinator.contains("Niv99OrderedFrameIdentityQueue(metadataCapacity)"))
        assertTrue(liveCoordinator.contains("Process.THREAD_PRIORITY_DISPLAY"))
        assertTrue(liveCoordinator.contains("resolveAndCachePublishedTuning(tuningWorkerIdentity)"))
        assertTrue(liveCoordinator.contains("tuningWorkQueue.poll(tuningWorkerIdentity)"))
        assertTrue(lookup.contains("sequentialTuningCache.copyExact("))
        assertFalse(lookup.contains("policy.resolve("))
        assertFalse(lookup.contains("ring.copyExact("))
        assertFalse(lookup.contains("canResolveNewFrame("))
        assertTrue(framePipeline.contains("niv99LiveCapture.awaitTuningForCopiedFrame("))
        assertTrue(framePipeline.contains("timeoutNs = runtimeNiv99DeferredMetadataWaitNs"))
        assertTrue(framePipeline.contains("niv99LiveCapture.awaitTuningForImage("))
        assertTrue(framePipeline.contains("timeoutNs = runtimeNiv99ExactMetadataWaitNs"))
        assertTrue(framePipeline.contains("Niv99ExactFrameJoinPolicy.isSamePhysicalFrame("))
        assertTrue(framePipeline.contains("metadataAlignedSensorTimestampNs"))
        assertTrue(framePipeline.contains(
            "runtimeNiv99ExactMetadataWaitNs = Niv99ExactFrameJoinPolicy.timeoutNs(sensorFps)"
        ))
        assertTrue(framePipeline.contains(
            "runtimeNiv99DeferredMetadataWaitNs = Niv99DeferredMetadataJoinPolicy.timeoutNs(sensorFps)"
        ))
        assertTrue(liveCoordinator.contains("fun awaitTuningForImage("))
        assertTrue(liveCoordinator.contains("sequentialTuningCache.awaitTimestampAligned("))
        assertTrue(liveCoordinator.contains("fun awaitTuningForCopiedFrame("))
        assertTrue(liveCoordinator.contains(
            "timeoutNs.coerceAtMost(Niv99ExactFrameJoinPolicy.MAXIMUM_WAIT_NS)"
        ))
        assertTrue(liveCoordinator.contains(
            "timeoutNs.coerceAtMost(Niv99DeferredMetadataJoinPolicy.MAXIMUM_WAIT_NS)"
        ))
        assertTrue(liveCoordinator.contains("imageOwned=false"))
    }

    @Test
    fun `retired callbacks cannot rewind timeline and preview retries are non accounting probes`() {
        val capturePublication = liveCoordinator
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun onAnalysisFrame(")
        val previewProbe = liveCoordinator
            .substringAfter("fun probeTuningForImage(")
            .substringBefore("fun recordPreviewImageLookup(")
        val previewJoin = framePipeline
            .substringAfter("fun copyNiv99TuningForPreviewFrame(")
            .substringBefore("fun onNiv99PreviewTuningLookupFinalized(")

        val generationGuard = capturePublication.indexOf(
            "sessionGeneration != configuredGeneration"
        )
        val timelineBegin = capturePublication.indexOf(
            "timelineTelemetry.beginTimeline(cameraId, sessionGeneration)"
        )
        assertTrue(generationGuard >= 0)
        assertTrue(timelineBegin > generationGuard)
        assertTrue(capturePublication.contains("return Niv99MetadataStatus.STALE_CAPTURE_METADATA"))
        assertTrue(previewJoin.contains("niv99LiveCapture.probeTuningForImage("))
        assertTrue(previewProbe.contains("sequentialTuningCache.copyExact("))
        assertFalse(previewProbe.contains("recordImageLookup("))
        assertTrue(framePipeline.contains("niv99LiveCapture.recordPreviewImageLookup("))
    }

    @Test
    fun `full frame analysis cannot block exact Camera2 metadata publication`() {
        val capturePublication = liveCoordinator
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun onAnalysisFrame(")
        val analysisPublication = liveCoordinator
            .substringAfter("fun onAnalysisFrame(")
            .substringBefore("private fun rejectAnalysisFrame(")
        val captureCallback = cameraController
            .substringAfter("override fun onCaptureCompleted(")
            .substringBefore("override fun onCaptureFailed(")

        assertTrue(liveCoordinator.contains("private val analysisLaneLock = Any()"))
        assertTrue(liveCoordinator.contains("@Volatile private var configuredCameraId"))
        assertTrue(liveCoordinator.contains("@Volatile private var configuredGeneration"))
        assertFalse(Regex("@Synchronized\\s+fun onAnalysisFrame").containsMatchIn(liveCoordinator))
        assertTrue(analysisPublication.contains("synchronized(analysisLaneLock)"))
        assertFalse(capturePublication.contains("analysisLaneLock"))
        assertTrue(captureCallback.contains("maybeLogCaptureCallbackTiming("))
        assertTrue(cameraController.contains("NIV99_CAPTURE_CALLBACK_TIMING"))
        assertTrue(cameraController.contains("\"CameraResultIngress\""))
        assertTrue(cameraController.contains("captureResultHandler = Handler("))
        assertTrue(cameraController.contains(
            "session.setRepeatingRequest(request, callback, resultHandler)"
        ))
        assertTrue(cameraController.contains("session.capture(request, callback, resultHandler)"))
        assertFalse(cameraController.contains(
            "session.setRepeatingRequest(request, callback, backgroundHandler)"
        ))
    }

    @Test
    fun `capture publication never shares a monitor with preview or encoder consumers`() {
        assertTrue(liveCoordinator.contains("private val capturePublicationLock = Any()"))
        assertTrue(liveCoordinator.contains("private val lateMetadataLock = Any()"))
        assertTrue(liveCoordinator.contains(
            "): Niv99MetadataStatus {\n        return synchronized(capturePublicationLock)"
        ))
        listOf(
            "onCaptureResult",
            "copyTuningForImage",
            "recordPreviewImageLookup",
            "recordRecordingImageLookup",
            "onPixelRenderCompleted",
            "onPreviewPixelRenderCompleted"
        ).forEach { method ->
            assertFalse(
                "$method must not take the coordinator-wide monitor",
                Regex("@Synchronized\\s+fun $method\\b").containsMatchIn(liveCoordinator)
            )
        }
        assertTrue(liveCoordinator.contains("synchronized(lateMetadataLock)"))
        assertFalse(liveCoordinator.contains("synchronized(this)"))
    }

    @Test
    fun `recording P010 and hardware import cannot queue behind full frame analysis`() {
        assertTrue(cameraController.contains("\"HardwareImportIngress\""))
        assertTrue(cameraController.contains("hardwareImportIngressHandler = Handler("))
        assertTrue(cameraController.contains(
            "bindReaderCallback(ReaderKind.HARDWARE_IMPORT, it, hardwareImportHandler)"
        ))
        assertTrue(cameraController.contains(
            "targetYuvFormat == ImageFormat.YCBCR_P010"
        ))
        assertTrue(cameraController.contains(
            "bindReaderCallback(ReaderKind.YUV, it, yuvIngressHandler)"
        ))
        assertTrue(cameraController.contains("\"CaptureTelemetry\""))
        assertTrue(cameraController.contains(
            "captureTelemetryHandler?.post(captureResultDiagnosticsRunnable)"
        ))
    }

    @Test
    fun `session reset retires NIV99 metadata and teardown releases it`() {
        val beginSession = framePipeline
            .substringAfter("fun beginCameraSession(")
            .substringBefore("fun setCameraCalibration")
        val stop = framePipeline
            .substringAfter("fun stop()")
            .substringBefore("fun destroy()")

        assertTrue(beginSession.contains("niv99LiveCapture.onSessionGenerationAdvanced(sessionGeneration)"))
        assertTrue(stop.contains("niv99LiveCapture.close()"))
        assertTrue(stop.contains("niv99CameraId.set(\"\")"))
    }

    @Test
    fun `video activation is fail closed at the offline isolation boundary`() {
        val captureSignature = liveCoordinator
            .substringAfter("fun onCaptureResult(")
            .substringBefore("): Niv99MetadataStatus")
        val configurationUpdate = liveCoordinator
            .substringAfter("private fun updateConfiguration(")
            .substringBefore("private fun resolveAndCachePublishedTuning(")

        assertFalse(captureSignature.contains("pixelApplicationApproved"))
        assertFalse(configurationUpdate.contains("pixelApplicationApproved"))
        assertTrue(videoPixelPolicy.contains(
            "const val DEFAULT_ENABLED: Boolean = Niv99ExecutionBoundaryPolicy.ANY_LIVE_PROCESSING_ENABLED"
        ))
        assertTrue(videoPixelPolicy.contains("supportsLiveMode(configuration.mode)"))
        assertTrue(videoPixelPolicy.contains("Niv99OperatingMode.PRO -> true"))
        assertTrue(nativeBridge.contains("niv99.currentFrameActive = false"))
        assertTrue(nativeBridge.contains("return primaryNiv99Applied ? 3 : 1"))
        assertTrue(realtimeShader.contains("vec3 realtimeApplyNiv99WhiteBalance"))
        assertTrue(realtimeShader.contains("niv99FuseStrength("))
        assertTrue(offscreenNativeBridge.contains("NIV99_PIXEL_APPLIED_ENCODER"))
        assertFalse(nativeBridge.contains("dedicated graph is unattached"))
    }

    @Test
    fun `processed video analysis remains quality owned while NIV99 live analysis is disabled`() {
        val analysisDemand = cameraViewModel
            .substringAfter("private fun analysisFeedRequested(): Boolean =")
            .substringBefore("private fun syncPreviewAnalysisFeed()")
        val modeCommit = cameraViewModel
            .substringAfter("private fun applyCinemaModeSelection(")
            .substringBefore("private fun restoreProcessedRecordingSelectionAfterRawExitIfNeeded(")
        val committedState = modeCommit.indexOf("_rawOn.value = committedRawEnabled")
        val analysisSync = modeCommit.indexOf("syncPreviewAnalysisFeed()")
        val sessionRefresh = modeCommit.indexOf("refreshSessionForModeSwitchIfIdle(")

        assertTrue(analysisDemand.contains("VideoRenderProfileQualityHintPolicy.isVideoSurface"))
        assertTrue(analysisDemand.contains("Niv99ExecutionBoundaryPolicy.LIVE_ANALYSIS_ENABLED"))
        assertTrue(analysisDemand.contains("!_rawOn.value"))
        assertTrue(analysisDemand.contains("!isRawVideoModeActive()"))
        assertTrue(committedState >= 0)
        assertTrue(analysisSync > committedState)
        assertTrue(sessionRefresh > analysisSync)
    }

    @Test
    fun `metadata and renderer confirmation remain separate generation scoped evidence`() {
        assertTrue(liveCoordinator.contains("NIV99_METADATA_OBSERVE"))
        assertTrue(liveCoordinator.contains("METADATA_LOG_INTERVAL_FRAMES = 120L"))
        assertTrue(liveCoordinator.contains("pixelApplied=false"))
        assertTrue(liveCoordinator.contains("if (!tuning.pixelApplicationSucceeded"))
        assertTrue(liveCoordinator.contains("NIV99_PIXEL_APPLIED"))
        assertTrue(liveCoordinator.contains("timelineTelemetry.recordPixelApplied"))
        assertTrue(liveCoordinator.contains(
            "output.metadataOnly = output.timeline.pixelEligibleTunings == 0L"
        ))
        assertTrue(liveCoordinator.contains("output.timeline.pixelAppliedFrames > 0L"))
        assertTrue(sensorPipelineIntegration.contains("\"niv99ProcessingStatus\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99MetadataOnly\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99AnalysisFramesSeen\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99AnalysisEstimatesPublished\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99NonIdentityWhiteBalanceFrames\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99LastWhiteBalanceEffectMagnitude\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineGeneration\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineCaptureResultsPublished\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineImageMatches\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineImageMisses\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelinePixelEligibleTunings\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelinePreviewPixelAppliedFrames\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineRecordingPixelAppliedFrames\""))
        assertTrue(sensorPipelineIntegration.contains(
            "\"niv99TimelinePreviewPixelRenderRejectedFrames\""
        ))
        assertTrue(sensorPipelineIntegration.contains(
            "\"niv99TimelineRecordingPixelRenderRejectedFrames\""
        ))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineResetCount\""))
        assertTrue(sensorPipelineIntegration.contains("\"niv99TimelineLatestPublishedFrameNumber\""))
        assertTrue(sensorPipelineIntegration.contains(
            "\"niv99TimelineLatestPublishedSensorTimestampNs\""
        ))
    }

    @Test
    fun `native packet carries and validates complete frame provenance atomically`() {
        val kotlinSubmission = bridge
            .substringAfter("fun submitHardwareBuffer(")
            .substringBefore("fun setHdrDynamicRangeMode")
        val runtimeSubmission = runtime
            .substringAfter("fun submitCameraImage(")
            .substringBefore("private fun emitFrameLineageSubmission")
        val nativeSubmission = nativeBridge
            .substringAfter(
                "Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSubmitHardwareBuffer("
            )
            .substringBefore("// Zero-Copy Tensor Bridge")

        assertTrue(kotlinSubmission.contains("niv99CameraIdHash"))
        assertTrue(kotlinSubmission.contains("niv99SessionGeneration"))
        assertTrue(kotlinSubmission.contains("niv99FrameNumber"))
        assertTrue(kotlinSubmission.contains("niv99SensorTimestampNs"))
        assertTrue(runtimeSubmission.contains("sensorTimestampNs = sensorTimestampNs"))
        assertTrue(runtimeSubmission.contains("niv99Tuning = niv99Tuning"))
        assertTrue(nativeSubmission.contains("const bool completeIdentity"))
        assertTrue(nativeSubmission.contains("const bool exactImageTimestamp"))
        assertTrue(nativeSubmission.contains("const bool stale"))
        assertTrue(nativeSubmission.contains("niv99.packetsStale += 1u"))
    }

    @Test
    fun `camera identity hash is deterministic nonzero and camera specific`() {
        val first = Niv99CameraIdentityHash.of("0")
        val repeated = Niv99CameraIdentityHash.of("0")
        val second = Niv99CameraIdentityHash.of("1")

        assertEquals(first, repeated)
        assertNotEquals(0L, first)
        assertNotEquals(first, second)
        assertEquals(0L, Niv99CameraIdentityHash.of(""))
        assertEquals(0L, Niv99CameraIdentityHash.of("   "))
    }

    @Test
    fun `NPV10 recording gives exact metadata ingress equal scheduling authority`() {
        val priorityReconciliation = cameraController
            .substringAfter("private fun reconcileNiv99MetadataIngressPriority(")
            .substringBefore("private fun registerCameraAvailabilityCallback()")
        val npv10Start = cameraController
            .substringAfter("internal fun startNpv10VideoCaptureWithProducerLease()")
            .substringBefore("private fun commitNpv10ProducerStartupLease(")
        val stop = cameraController
            .substringAfter("fun stopVideoCapture(retargetRepeating: Boolean)")
            .substringBefore("override fun refreshRepeatingRequest()")

        assertTrue(priorityReconciliation.contains("Process.THREAD_PRIORITY_DISPLAY"))
        assertTrue(priorityReconciliation.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"))
        assertTrue(priorityReconciliation.contains("Process.setThreadPriority(threadId, requestedNice)"))
        assertTrue(priorityReconciliation.contains("Process.getThreadPriority(threadId)"))
        assertTrue(priorityReconciliation.contains("NIV99_METADATA_PRIORITY_APPLY"))
        assertTrue(npv10Start.contains("recordingActive = true"))
        assertTrue(npv10Start.contains("val metadataPriorityReady ="))
        assertTrue(npv10Start.contains("val imagePriorityReady ="))
        assertTrue(npv10Start.contains("reconcileNiv99MetadataIngressPriority(\"npv10-capture-start\")"))
        assertTrue(npv10Start.contains("reconcileNiv99ImageIngressPriority(\"npv10-capture-start\")"))
        assertTrue(npv10Start.contains("check(metadataPriorityReady && imagePriorityReady)"))
        assertTrue(npv10Start.contains("recordingActive = false"))
        assertTrue(npv10Start.contains("npv10-capture-start-rollback"))
        assertTrue(stop.indexOf("recordingActive = false") <
            stop.indexOf("reconcileNiv99MetadataIngressPriority(\"video-capture-stop\")"))
        assertTrue(stop.indexOf("recordingActive = false") <
            stop.indexOf("reconcileNiv99ImageIngressPriority(\"video-capture-stop\")"))
    }
}
