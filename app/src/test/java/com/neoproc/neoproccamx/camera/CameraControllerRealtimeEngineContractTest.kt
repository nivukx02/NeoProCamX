package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerRealtimeEngineContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun controllerUsesUrgentCameraThreadAndHotSessionSwap() {
        assertTrue(source.contains("Process.THREAD_PRIORITY_URGENT_AUDIO"))
        assertTrue(source.contains("ThreadPoolManager.cameraExecutor"))
        assertTrue(source.contains("previousSessionForHotSwap"))
        assertFalse(source.contains("Close the existing session before creating a new one"))
    }

    @Test
    fun hotSessionRebuildDefersReaderCloseUntilPreviousSessionCloses() {
        val setupReadersBody = source
            .substringAfter("override fun setupReaders() {")
            .substringBefore("private fun closeReaderSafely")
        val onConfiguredBody = source
            .substringAfter("override fun onConfigured(s: CameraCaptureSession) {")
            .substringBefore("override fun onClosed(s: CameraCaptureSession)")
        val onClosedBody = source
            .substringAfter("override fun onClosed(s: CameraCaptureSession) {")
            .substringBefore("override fun onConfigureFailed(s: CameraCaptureSession)")

        assertTrue(setupReadersBody.contains("deferCurrentReadersUntilSessionClose()"))
        assertFalse(setupReadersBody.contains("closeReaderSafely(jpegReader)"))
        assertFalse(setupReadersBody.contains("closeReaderSafely(hardwareImportReader)"))
        assertTrue(onConfiguredBody.contains("registerPreviousSessionReleaseBarrier("))
        assertTrue(onClosedBody.contains("closeDeferredReadersSafely"))
        assertTrue(source.contains("closeReadersInsideFramePipelineBarrier"))
        assertTrue(source.contains("CameraReaderCloseBarrierPolicy.execute("))
        assertTrue(source.contains("closeAction = { closeReaderSetsNow(readerSets) }"))
        assertFalse(source.contains("awaitFramePipelineIdleBeforeReaderClose"))
    }

    @Test
    fun rawCallbacksWithoutAnOwnerDrainTheReaderInsteadOfBackpressuringTheHal() {
        val rawDispatch = source
            .substringAfter("private fun dispatchRawReady(")
            .substringBefore("private fun dispatchYuvReady(")

        assertTrue(rawDispatch.contains("val owner = onRawReady"))
        assertTrue(rawDispatch.contains("if (owner != null)"))
        assertTrue(rawDispatch.contains("reader.acquireLatestImage()?.close()"))
    }

    @Test
    fun coldOpenResolvesFinalP010SizeAndFpsBeforeTheFirstSessionGraph() {
        val setupReadersBody = source
            .substringAfter("override fun setupReaders() {")
            .substringBefore("private fun modeSwitchStandbyOutputsAllowed")
        val promotionIndex = setupReadersBody.indexOf("promoteModeSwitchStandbyP010TargetBeforeReaderSetup()")
        val graphIndex = setupReadersBody.indexOf("currentCinemaStreamGraphPlan()")
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val openPrimeBody = viewModel
            .substringAfter("private fun primeNpv10CaptureGraphForOpen() {")
            .substringBefore("private fun configureModeSwitchStandbyAnalysisOutput")

        assertTrue(promotionIndex >= 0)
        assertTrue(graphIndex > promotionIndex)
        assertTrue(source.contains("Npv10CaptureSourcePolicy.resolveNativeP010TargetForSizes"))
        assertTrue(openPrimeBody.indexOf("camera.setFPS(requestedFps)") >= 0)
        assertTrue(
            openPrimeBody.indexOf("camera.setFPS(requestedFps)") <
                openPrimeBody.indexOf("if (!shouldUseUncompressedCinemaPipeline()) return")
        )
    }

    @Test
    fun hardwareImportPathUsesRealtimeDispatcherAndPresentationPts() {
        val framePipelineSource = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").readText()
        val runtimeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
        val bridgeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()

        assertTrue(
            framePipelineSource.contains(
                "HardwareFrameDispatchQueues<Image>"
            )
        )
        assertTrue(framePipelineSource.contains("RealtimeFrameDispatcher<RoutedHardwareFrame<T>>"))
        assertTrue(framePipelineSource.contains("RecordPipelineDepthFlag.hardwareImportAcquireGuardNs()"))
        assertTrue(framePipelineSource.contains("presentationTimeNs"))
        assertTrue(runtimeSource.contains("SensorPresentationClock"))
        assertTrue(runtimeSource.contains("presentationTimeNs"))
        assertTrue(bridgeSource.contains("presentationTimeNs: Long"))
    }

    @Test
    fun previewFpsTelemetryUsesBufferedCaptureTimestampsInsteadOfConflatedLiveResult() {
        assertTrue(source.contains("MutableSharedFlow<Long>"))
        assertTrue(source.contains("extraBufferCapacity = 120"))
        assertTrue(source.contains("BufferOverflow.DROP_OLDEST"))
        assertTrue(source.contains("val previewFrameTimestamps: SharedFlow<Long>"))
        assertTrue(source.contains("previewFrameTimestampFlow.tryEmit(liveResult.timestampNs)"))
    }

    @Test
    fun healthRawStreamQueryReadsTheConfiguredGraphWithoutRebuildingTheGraphPlan() {
        val query = source
            .substringAfter("fun isRawStreamActive(): Boolean =")
            .substringBefore("fun ispBypassSupportedByHardware()")

        assertTrue(query.contains("configuredSessionFingerprint?.streamGraph?.rawSensor?.includeInSession"))
        assertFalse(query.contains("currentCinemaStreamGraphPlan()"))
    }

    @Test
    fun retainedP010ReaderIsNeverAttachedBesideARequiredRawRoute() {
        val attachmentPolicy = source
            .substringAfter("private fun shouldAttachIdleP010StandbySurface(")
            .substringBefore("private fun currentReaderSet()")

        assertTrue(
            source.contains(
                "shouldRetainIdleP010StandbyReader(oldYuv, streamGraph) && keepYuvTransport"
            )
        )
        assertTrue(
            source.contains(
                "else if (shouldAttachIdleP010StandbySurface(yuvReader, streamGraph))"
            )
        )
        assertTrue(attachmentPolicy.contains("!streamGraph.rawSensorSurfaceActive"))
        assertTrue(
            attachmentPolicy.contains(
                "shouldRetainIdleP010StandbyReader(reader, streamGraph)"
            )
        )
    }

    @Test
    fun compatibleHardwareImportReaderSurvivesRawModeButItsSurfaceDoesNot() {
        val retentionPolicy = source
            .substringAfter("private fun shouldRetainIdleHardwareImportStandbyReader(")
            .substringBefore("private fun currentReaderSet()")
        val surfaceResolver = source
            .substringAfter("private fun resolveSessionSurfaces(")
            .substringBefore("private fun captureSessionSurfaceSnapshot(")

        assertTrue(source.contains("shouldRetainIdleHardwareImportStandbyReader(oldHw, streamGraph)"))
        assertTrue(retentionPolicy.contains("streamGraph.rawSensorSurfaceActive"))
        assertTrue(retentionPolicy.contains("streamGraph.hardwareImport.includeInSession"))
        assertTrue(retentionPolicy.contains("reader.imageFormat != ImageFormat.YCBCR_P010"))
        assertTrue(surfaceResolver.contains(
            "if (streamGraph.hardwareImport.includeInSession) hardwareImportReader?.surface?.let(::add)"
        ))
        assertFalse(surfaceResolver.contains("shouldRetainIdleHardwareImportStandbyReader"))
    }

    @Test
    fun rawProcessedModeBoundaryPrimesVisiblePreviewBeforeTheFullRepeatingGraph() {
        val startPreview = source
            .substringAfter("private fun startPreviewInternal(")
            .substringBefore("private fun identitySurfaceSet(")
        val primeSubmission = startPreview.indexOf("submitModeSwitchFirstFramePrimeCapture(")
        val repeatingSubmission = startPreview.indexOf("submitTrackedRepeatingRequest(")

        assertTrue(source.contains("CameraModeSwitchFirstFramePrimePolicy.shouldPrime("))
        assertTrue(startPreview.contains("val visiblePreviewTargets ="))
        assertTrue(startPreview.contains("visiblePreviewTargets.forEach(::addRepeatingTarget)"))
        assertTrue(startPreview.contains("buildSoftwareAeTrackedRequestForRevision("))
        assertTrue(primeSubmission >= 0)
        assertTrue(repeatingSubmission > primeSubmission)
    }

    @Test
    fun createSessionUsesClosedDeviceRecoveryPolicy() {
        assertTrue(source.contains("CameraSessionCreateFailurePolicy.resolve("))
        assertTrue(source.contains("sessionGenerationCurrent = generation == sessionGeneration.get()"))
        assertTrue(source.contains("cameraDeviceStillCurrent = cameraDevice === device"))
        assertTrue(source.contains("openCamera(onReady, onError)"))
    }

    @Test
    fun staleHotSwapFailuresCannotPolluteTheReplacementSessionLedger() {
        val callbackBody = source
            .substringAfter("private val captureCallback = object")
            .substringBefore("private fun maybeLogPhysicalTelemetryForShaderProfiles")
        val failedBody = callbackBody
            .substringAfter("override fun onCaptureFailed(")
            .substringBefore("override fun onCaptureBufferLost(")
        val lostBody = callbackBody
            .substringAfter("override fun onCaptureBufferLost(")

        assertTrue(failedBody.contains("val binding = captureResultSessionBinding.get()"))
        assertTrue(failedBody.contains(
            "binding?.session !== s || binding.generation != sessionGeneration.get()"
        ))
        assertTrue(lostBody.contains("val binding = captureResultSessionBinding.get()"))
        assertTrue(lostBody.contains(
            "binding?.session !== s || binding.generation != sessionGeneration.get()"
        ))
    }
}
