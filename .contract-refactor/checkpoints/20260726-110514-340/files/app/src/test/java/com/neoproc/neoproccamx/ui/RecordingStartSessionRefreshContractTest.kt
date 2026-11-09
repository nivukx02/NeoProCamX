package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingStartSessionRefreshContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readNormalizedText()
    }
    private val mainActivitySource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readNormalizedText()
    }
    private val cameraControllerSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readNormalizedText()
    }
    private val debugRecordClipErrorPolicySource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/DebugRecordClipErrorPolicy.kt").readNormalizedText()
    }
    private val debugRecordClipCoordinatorSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/DebugRecordClipCoordinator.kt").readNormalizedText()
    }
    private val debugCommandReceiverSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/DebugCommandReceiver.kt").readNormalizedText()
    }

    @Test
    fun `recording start keeps visible preview live and routes encoded video through internal feed`() {
        assertTrue(source.contains("deferSessionRefresh = true"))
        assertTrue(source.contains("RecordingPipelineParityPolicy.resolve("))
        assertTrue(source.contains("Camera2SessionGraphParityPolicy.resolve("))
        assertTrue(source.contains("activeCamera2SessionGraphParityPlan("))
        assertTrue(source.contains("val recordingFeedPath = recordingParityPlan.feedPath"))
        assertTrue(source.contains("RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER"))
        assertTrue(source.contains("RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE"))
        assertTrue(source.contains("camera.setPreviewContinuityRecordSurfaceEnabled(true)"))
        assertTrue(source.contains("encoderSurfaceAlreadyInSession"))
        assertTrue(source.contains("startingSessionGraphPlan.refreshAction"))
        assertTrue(source.contains("startingSessionGraphPlan.readoutOverrideSize"))
        assertTrue(source.contains("startingSessionGraphPlan.analysisTargetSize"))
        assertTrue(source.contains("startingSessionGraphPlan.rawSensorOwnsReadout"))
        assertTrue(source.contains("Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY"))
        assertTrue(source.contains("Recording start: using pre-armed persistent encoder surface"))
        assertTrue(source.contains("Preview-continuity Camera2 recording started; visible preview request unchanged"))
        assertTrue(
            "Camera2 direct encoder surface must remain as fallback for RAW sensor recording.",
            source.contains("camera.setRecordSurface(encoderSurface, refreshSession = false)")
        )
        assertTrue(
            "Preview-continuity recording must target the encoder surface without switching Camera2 to recordingActive.",
            cameraControllerSource.contains("recordingActive || previewContinuityRecordSurfaceEnabled")
        )
        assertTrue(
            "Preview-continuity target changes must update the repeating request without rebuilding the Camera2 session.",
            cameraControllerSource.contains("setPreviewContinuityRecordSurfaceEnabled") &&
                cameraControllerSource.contains("restartRepeatingRequestForRecordTargetChange()")
        )
    }

    @Test
    fun `recording stop removes encoder target before drain and keeps prearmed session surface`() {
        val detachIndex = source.indexOf("detachRecordingSurfaceBeforeEncoderStop()")
        val encoderStopIndex = source.indexOf("val path = gpuRecording.stop()")

        assertTrue(detachIndex >= 0)
        assertTrue(encoderStopIndex >= 0)
        assertTrue(detachIndex < encoderStopIndex)
        assertTrue(source.contains("Recording stop: kept pre-armed persistent encoder surface in Camera2 session"))
    }

    @Test
    fun `debug record clip remains terminally owned until its stop is requested`() {
        val stopIndex = debugRecordClipCoordinatorSource.indexOf("debugAction recordClip stop")
        val finishIndex = debugRecordClipCoordinatorSource.indexOf(
            "finishCommand(\"stop_requested\", error = false)",
            stopIndex
        )

        assertTrue(stopIndex >= 0)
        assertTrue(finishIndex > stopIndex)
        assertTrue(mainActivitySource.contains("pendingDebugAction == DEBUG_ACTION_RECORD_CLIP"))
        assertTrue(mainActivitySource.contains("debugRecordClipCoordinator.tryClaimStart(recordClipToken)"))
        assertTrue(debugRecordClipCoordinatorSource.contains("executionGate.tryClaimStop(commandToken)"))
    }

    @Test
    fun `cold debug launch delivers queued command only once`() {
        val coldLaunchBlock = debugCommandReceiverSource
            .substringAfter("MainActivity.queueDebugCommand(commandIntent)")
            .substringBefore("runCatching {")

        assertTrue(coldLaunchBlock.contains("Intent(context, MainActivity::class.java)"))
        assertFalse(coldLaunchBlock.contains("replaceExtras(commandIntent)"))
    }

    @Test
    fun `debug receiver-only intents do not cancel pending record clip stop`() {
        val noAutomationExtrasBlock = mainActivitySource
            .substringAfter("if (!hasDebugExtras) {")
            .substringBefore("if (!DebugCommandIntentReader.isDebugCommandAction(intent.action))")

        assertTrue(noAutomationExtrasBlock.contains("DEBUG_COMMAND without automation extras"))
        assertTrue(noAutomationExtrasBlock.contains("preserving pending automation state"))
        assertFalse(noAutomationExtrasBlock.contains("clearPendingDebugCommandState()"))
    }

    @Test
    fun `debug record clip waits for recording surface prearm before starting`() {
        val automationBlock = mainActivitySource
            .substringAfter("vm.prearmRecordingSurfaceForSelectedVideoSettings(forceSessionRefresh = true)")
            .substringBefore("val actionPreset = pendingDebugPreset")

        assertTrue(source.contains("fun isRecordingSurfacePrearmedForDebugStart(): Boolean"))
        assertTrue(automationBlock.contains("vm.isRecordingSurfacePrearmedForDebugStart()"))
        assertTrue(automationBlock.contains("waiting for recording surface prearm"))
        assertTrue(automationBlock.contains("scheduleDebugAutomationRetry("))
        assertTrue(automationBlock.contains("return"))
    }

    @Test
    fun `startup open uses callback surface when holder cache is temporarily empty`() {
        val resolverBlock = mainActivitySource
            .substringAfter("private fun resolvePreviewSurfaceForCameraOpen(): Surface?")
            .substringBefore("private fun scheduleStableSurfaceCameraOpen(")
        val stableOpenBlock = mainActivitySource
            .substringAfter("private fun openFromStablePreviewSurface()")
            .substringBefore("private fun enqueueDebugIntentIfPresent(")

        assertTrue(resolverBlock.contains("binding.glSurface.getPreviewSurface()?.let { return it }"))
        assertTrue(resolverBlock.contains("val callbackSurface = cameraOpenSurface ?: return null"))
        assertTrue(resolverBlock.contains("canUseCallbackSurfaceWhenHolderCacheMissing("))
        assertTrue(resolverBlock.contains("surfaceCallbackDelivered = surfaceReady"))
        assertTrue(stableOpenBlock.contains("val activeSurface = resolvePreviewSurfaceForCameraOpen()"))
        assertTrue(stableOpenBlock.contains("Camera open recovered current holder surface after stale stability gate"))
        assertTrue(stableOpenBlock.contains("cameraOpenSurface = activeSurface"))
        assertTrue(stableOpenBlock.contains("scheduleCachedSurfaceOpenProbe()"))
    }

    @Test
    fun `debug record clip duration starts only after recording feed is validated`() {
        val helper = debugRecordClipCoordinatorSource
            .substringAfter("fun scheduleStopAfterRecordingStarts(")

        assertTrue(helper.contains("pollIntervalMs"))
        assertTrue(helper.contains("is GpuRecordingState.Recording"))
        assertTrue(helper.contains("is GpuRecordingState.Paused"))
        assertTrue(helper.contains("is GpuRecordingState.Error"))
        assertTrue(debugRecordClipErrorPolicySource.contains("isStrictHighEndAdmissionFailure(message)"))
        assertTrue(helper.contains("DebugRecordClipErrorPolicy.handle("))
        assertTrue(helper.contains("state.message"))
        assertTrue(helper.contains("strict admission failure:"))
        assertTrue(helper.contains("debugAction recordClip terminal failure"))
        assertFalse(helper.contains("scheduleDebugAutomationRetry("))
        assertTrue(helper.contains("isRecordingTimingReady()"))
        assertTrue(helper.contains("record_clip_waiting_for_validated_recording_feed"))
        assertTrue(helper.contains("debugAction recordClip active validated; scheduling stop"))
        assertTrue(helper.contains("backendSettleGraceMs"))
        assertTrue(helper.contains("markTimingStart()"))
        assertTrue(
            helper.indexOf("markTimingStart()") <
                helper.indexOf("}, stopDelayMs)")
        )
        assertTrue(helper.contains("}, stopDelayMs)"))
    }

    @Test
    fun `gpu validation readiness starts strict clip evidence at proven recording window`() {
        val readyHelper = source
            .substringAfter("private fun markGpuRecordingReadyForClipTiming()")
            .substringBefore("fun markDebugClipTimingStartForEvidence()")
        val debugMarker = source
            .substringAfter("fun markDebugClipTimingStartForEvidence()")
            .substringBefore("private fun prepareGpuRecordingForPrearmedVideoSession()")

        assertTrue(readyHelper.contains("recordingReadyForClipTiming = true"))
        assertTrue(readyHelper.contains("gpuRecording.markClipTimingStartForEvidence()"))
        assertTrue(debugMarker.contains("isRecordingReadyForDebugClipTiming()"))
        assertTrue(debugMarker.contains("gpuRecording.markClipTimingStartForEvidence()"))
    }

    @Test
    fun `recording start preview look setters skip duplicate repeating requests`() {
        assertTrue(
            "setPreviewLookControls must be idempotent so recording start does not push " +
                "duplicate repeating requests and blink the preview.",
            cameraControllerSource.contains(
                "if (previewLogLookEnabled == logEnabled &&\n" +
                    "            previewHdrLookEnabled == hdrEnabled &&\n" +
                    "            previewRawLookEnabled == rawEnabled\n" +
                    "        ) return"
            )
        )
        assertTrue(
            "setCinemaFidelityOverridesEnabled must skip unchanged values to avoid " +
                "an extra recording-time repeating request.",
            cameraControllerSource.contains("if (cinemaFidelityOverridesEnabled == enabled) return")
        )
    }

    @Test
    fun `processed gpu bind failure is rejected instead of saving a camera2 bypass recording`() {
        val encoderBindFailureBlock = source
            .substringAfter("if (!encoderBound) {")
            .substringBefore("pushVulkanPreviewRenderConfig(force = true)")

        assertTrue(source.contains("ProcessedGpuRecordingRequirementPolicy"))
        assertTrue(encoderBindFailureBlock.contains("GPU processed recording"))
        assertFalse(encoderBindFailureBlock.contains("startCamera2DirectRecording()"))
        assertTrue(source.contains("internal-hardware-import-validated"))
    }

    @Test
    fun `processed gpu recording waits for asynchronous codec start before validation`() {
        val hardwareImportBlock = source
            .substringAfter("suspend fun startInternalHardwareImportRecording(): Boolean {")
            .substringBefore("applyRecordingCameraLookControls()")
        val startIndex = hardwareImportBlock.indexOf("gpuRecording.start()")
        val stateWaitIndex = hardwareImportBlock.indexOf("awaitGpuRecordingState()", startIndex)

        assertTrue(startIndex >= 0)
        assertTrue(stateWaitIndex > startIndex)
        assertTrue(source.contains("private suspend fun awaitGpuRecordingState(timeoutMs: Long = 1_200L)"))
        assertTrue(source.contains("while (SystemClock.elapsedRealtime() < deadlineMs)"))
        assertFalse(hardwareImportBlock.contains("delay(200L)"))
    }

    @Test
    fun `processed gpu recording binds vulkan encoder surface before starting codec timeline`() {
        val hardwareImportBlock = source
            .substringAfter("suspend fun startInternalHardwareImportRecording(): Boolean {")
            .substringBefore("applyRecordingCameraLookControls()")
        val frameImportDisabledIndex = hardwareImportBlock.indexOf("vulkanRuntime.setFrameImportEnabled(false)")
        val bindIndex = hardwareImportBlock.indexOf("vulkanRuntime.setEncoderSurface(encoderSurface)")
        val codecStartIndex = hardwareImportBlock.indexOf("gpuRecording.start()")
        val stateCheckIndex =
            hardwareImportBlock.indexOf("awaitGpuRecordingState()", codecStartIndex)

        assertTrue(frameImportDisabledIndex >= 0)
        assertTrue(bindIndex > frameImportDisabledIndex)
        assertTrue(codecStartIndex >= 0)
        assertTrue(stateCheckIndex > codecStartIndex)
        assertTrue(
            "Vulkan encoder swapchain creation can take seconds on device and must finish " +
                "before MediaCodec enters the recorded timeline.",
            codecStartIndex > bindIndex
        )
    }

    @Test
    fun `mode switch flush defers preview session rebuild out of the UI transition`() {
        val flushBlock = source
            .substringAfter("fun endModeSwitchPreviewWorkBatch()")
            .substringBefore("private fun syncCameraPreviewLookControls()")

        assertTrue(
            "Mode switches may update preview geometry and LUT immediately, but Camera2 " +
                "session rebuilds must stay deferred so swipe/tap input cannot ANR.",
            flushBlock.contains("deferSessionRefresh = true")
        )
    }

    @Test
    fun `main activity throttles rapid mode preset activation before camera setters run`() {
        val activationBlock = mainActivitySource
            .substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun resetAutoImagingStateForPreset()")
        val throttleIndex = activationBlock.indexOf("CameraModeSwitchPolicy.resolvePresetActivation(")
        val batchIndex = activationBlock.indexOf("vm.beginModeSwitchPreviewWorkBatch()")

        assertTrue(throttleIndex >= 0)
        assertTrue(batchIndex > throttleIndex)
        assertTrue(activationBlock.contains("lastModePresetActivationUptimeMs"))
    }

    @Test
    fun `mandatory custom packet modes skip every mediacodec idle prearm`() {
        val skipReasonBlock = source
            .substringAfter("private fun idleRecordingSurfacePrearmSkipReason(): String?")
            .substringBefore("private fun shouldKeepRecordingSurfacePrearmed()")

        assertTrue(skipReasonBlock.contains("custom-npraw-packet-stream-does-not-use-mediacodec"))
        assertTrue(skipReasonBlock.contains("custom-npv10-packet-stream-does-not-use-mediacodec"))
        assertTrue(skipReasonBlock.contains("_uncompressedCinemaPipeline.value"))
        assertTrue(skipReasonBlock.contains("activeRecordingPipelineParityPlan()"))
        assertTrue(skipReasonBlock.contains("hardware-import-uses-codec-owned-surface"))
        assertFalse(skipReasonBlock.contains("offscreen-ahb-egl-uses-codec-owned-surface"))
        assertTrue(
            "Internal GPU recording must not pre-arm a persistent input surface because " +
                "the Vulkan hardware-import route binds a fresh codec-owned surface.",
            source.contains("gpuRecording.setPersistentInputSurfaceAllowed(!hardwareImportUsesCodecOwnedSurface)")
        )
        assertTrue(source.contains("private fun prebindOffscreenAhbEglEncoderSurface("))
        assertTrue(source.contains("private fun prearmOffscreenHardwareImportSource("))
        assertTrue(source.contains("Recording start: reusing pre-armed offscreen AHB EGL encoder surface"))
        assertTrue(source.contains("strict start will verify live hardware-import attachment"))
    }

    @Test
    fun `recording metadata claims subject detail only after gpu frame validation`() {
        assertTrue(source.contains("VideoDetailSharpeningPolicy.recordingEvidenceMetadata("))
        val validatedBlock = source
            .substringAfter("if (!awaitValidatedProcessedGpuRecordingFrames())")
            .substringBefore("syncActivePreviewFrameRate()")
        assertTrue(validatedBlock.contains("gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata("))
    }

    @Test
    fun `processed gpu recording arms encoder import before attaching hardware source`() {
        val hardwareImportBlock = source
            .substringAfter("suspend fun startInternalHardwareImportRecording(): Boolean {")
            .substringBefore("gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(")
        val encoderStartIndex = hardwareImportBlock.indexOf("gpuRecording.start()")
        val importEnableIndex =
            hardwareImportBlock.indexOf("vulkanRuntime.setFrameImportEnabled(true)", encoderStartIndex)
        val sessionRefreshIndex =
            hardwareImportBlock.indexOf("awaitFreshPreviewSessionBeforeRecording(", importEnableIndex)
        val validateIndex =
            hardwareImportBlock.indexOf("awaitValidatedProcessedGpuRecordingFrames()", sessionRefreshIndex)

        assertTrue(hardwareImportBlock.contains("camera.setHardwareImportTargetSize(preparedEncoderSize)"))
        assertTrue(hardwareImportBlock.contains("camera.setInternalRecordingHardwareImportEnabled(true)"))
        assertTrue(encoderStartIndex >= 0)
        assertTrue(importEnableIndex > encoderStartIndex)
        assertTrue(sessionRefreshIndex > importEnableIndex)
        assertTrue(hardwareImportBlock.substring(sessionRefreshIndex).contains("rebuildReaders = true"))
        assertTrue(validateIndex > sessionRefreshIndex)
    }

    @Test
    fun `validated gpu import evidence survives stop time import disable for certificate`() {
        val validationBlock = source
            .substringAfter("private suspend fun awaitValidatedProcessedGpuRecordingFrames(")
            .substringBefore("private fun applyCinemaModeSelection(")
        val metadataBlock = source
            .substringAfter("private fun buildRecordingRuntimeEvidenceMetadata(")
            .substringBefore("private fun resolveRecordingSourcePlan(")

        assertTrue(source.contains("lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY"))
        assertTrue(source.contains("private fun preserveValidatedGpuRecordingEvidence(stats: VulkanImportStats)"))
        assertTrue(validationBlock.contains("lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence("))
        assertTrue(validationBlock.contains("preserveValidatedGpuRecordingEvidence(stats)"))
        assertTrue(validationBlock.contains("stats.toProcessedGpuRecordingValidationStats()"))
        assertTrue(validationBlock.contains("frameImportEnabled = validationStats.frameImportEnabled"))
        assertTrue(validationBlock.contains("importedFrames = validationStats.importedFrames"))
        assertTrue(source.contains("offscreenEglSubmittedFrames"))
        assertTrue(source.contains("preserveValidatedGpuRecordingEvidence(vulkanImportStats.value)"))
        assertTrue(metadataBlock.contains("val validatedEvidence = lastValidatedGpuRecordingEvidence"))
        assertTrue(metadataBlock.contains("val validatedGpuImport = validatedEvidence.importedFrames > 0L"))
        assertTrue(metadataBlock.contains("val recordingUsesCustomGpu ="))
        assertTrue(metadataBlock.contains("processingReport.recordingUsesCustomGpu || validatedGpuImport"))
        assertTrue(metadataBlock.contains("maxOf(stats.sessionImportedFrames, validatedEvidence.importedFrames)"))
        assertTrue(metadataBlock.contains("stats.frameImportEnabled || validatedEvidence.frameImportEnabled"))
        assertTrue(metadataBlock.contains("\"recordingUsesCustomGpu\" to recordingUsesCustomGpu.toString()"))
        assertTrue(metadataBlock.contains("vulkanImportStats:sessionImportedFrames=$"))
    }

    @Test
    fun `internal hardware import surface is bound during background prearm`() {
        val prearmBlock = source
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun scheduleRecordingSurfacePrearmRetry()")
        val persistentSurfaceIndex = prearmBlock.indexOf("prearmedRecordingSurface = surface")
        val prebindIndex =
            prearmBlock.indexOf("prebindInternalHardwareImportEncoderSurface(surface, parityPlan)", persistentSurfaceIndex)
        val helperBlock = source
            .substringAfter("private fun prebindInternalHardwareImportEncoderSurface(")
            .substringBefore("private fun prearmRecordingSurfaceNow(")

        assertTrue(persistentSurfaceIndex >= 0)
        assertTrue(prebindIndex > persistentSurfaceIndex)
        assertTrue(helperBlock.contains("vulkanRuntime.setEncoderSurface(surface)"))
        assertTrue(helperBlock.contains("Pre-armed persistent encoder surface bound to Vulkan hardware-import target"))
    }

    @Test
    fun `offscreen ahb egl prearm attaches camera2 p010 source before record start`() {
        val prearmBlock = source
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun scheduleRecordingSurfacePrearmRetry()")
        val helperBlock = source
            .substringAfter("private fun prebindOffscreenAhbEglEncoderSurface(")
            .substringBefore("private fun prearmOffscreenHardwareImportSource(")
        val offscreenBranch =
            prearmBlock.substringAfter("RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER")
        val newOffscreenPrearmBlock = prearmBlock
            .substringAfter("if (prearmParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {")
            .substringBefore("gpuRecording.setPersistentInputSurfaceAllowed(true)")

        assertTrue(helperBlock.contains("vulkanRuntime.setOffscreenAhbEglEncoderSurface("))
        assertTrue(helperBlock.contains("Pre-armed offscreen AHB EGL encoder surface bound"))
        assertTrue(newOffscreenPrearmBlock.contains("gpuRecording.setPersistentInputSurfaceAllowed(false)"))
        assertTrue(newOffscreenPrearmBlock.contains("gpuRecording.prepare()"))
        assertTrue(newOffscreenPrearmBlock.contains("prebindOffscreenAhbEglEncoderSurface(surface, prearmParityPlan)"))
        assertTrue(offscreenBranch.contains("prearmOffscreenHardwareImportSource("))
        assertTrue(offscreenBranch.contains("prearmedRecordingSurfaceAttachedToSession ="))
        assertTrue(offscreenBranch.contains("P010 hardware-import source attached before record start"))
        assertTrue(offscreenBranch.contains("P010 hardware-import source already attached to preview session"))
        assertTrue(source.contains("runOnMainIfNeeded { syncGlPreviewCompositorBinding() }"))
        assertFalse(helperBlock.contains("setOffscreenAhbEglSubmissionEnabled(true)"))
        assertFalse(helperBlock.contains("camera.setHardwareImportP010SourceEnabled(true)"))
        assertTrue(newOffscreenPrearmBlock.contains("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)"))
        assertTrue(newOffscreenPrearmBlock.contains("prearmOffscreenHardwareImportSource("))
    }

    @Test
    fun `offscreen recording start skips camera2 rebuild when p010 source is already prearmed`() {
        val reusableBlock = source
            .substringAfter("val reusableOffscreenPrearmedSurface =")
            .substringBefore("if (internalHardwareImportUsesCodecOwnedSurface")
        val offscreenStartBlock = source
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("if (!awaitValidatedProcessedGpuRecordingFrames(requireSustainedTargetFps = true))")

        assertTrue(reusableBlock.contains("val offscreenSourceAlreadyInSession ="))
        assertTrue(reusableBlock.contains("prearmedRecordingSurfaceAttachedToSession"))
        assertTrue(offscreenStartBlock.contains("if (offscreenSourceAlreadyInSession)"))
        assertTrue(offscreenStartBlock.contains("strict start will verify live hardware-import attachment"))
        assertTrue(offscreenStartBlock.contains("awaitPrearmedOffscreenHardwareImportActivity()"))
        assertTrue(offscreenStartBlock.contains("pre-armed offscreen AHB EGL P010 source was stale"))
        assertTrue(offscreenStartBlock.contains("Camera2 session rebuild skipped"))
        assertTrue(offscreenStartBlock.contains("} else {"))
        assertTrue(offscreenStartBlock.contains("awaitFreshPreviewSessionBeforeRecording("))
        assertTrue(offscreenStartBlock.contains("prearmedRecordingSurface = null"))
    }

    @Test
    fun `internal hardware import stop removes camera source before disabling vulkan import`() {
        val detachBlock = source
            .substringAfter("private suspend fun detachInternalRecordingFeedBeforeEncoderStop(")
            .substringBefore("private fun stopRawCaptureIfNeeded()")
        val detachSourceIndex =
            detachBlock.indexOf("camera.setInternalRecordingHardwareImportEnabled(false)")
        val refreshIndex =
            detachBlock.indexOf("awaitFreshPreviewSessionBeforeRecording(", detachSourceIndex)
        val disableImportIndex =
            detachBlock.indexOf("vulkanRuntime.setFrameImportEnabled(false)", refreshIndex)

        assertTrue(detachSourceIndex >= 0)
        assertTrue(refreshIndex > detachSourceIndex)
        assertTrue(detachBlock.substring(refreshIndex).contains("rebuildReaders = true"))
        assertTrue(disableImportIndex > refreshIndex)
    }

    @Test
    fun `offscreen stop keeps egl encoder surface alive until media codec eos`() {
        val stopBlock = source
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun stopRawCaptureIfNeeded()")
        val detachBlock = source
            .substringAfter("private suspend fun detachInternalRecordingFeedBeforeEncoderStop(")
            .substringBefore("private fun stopRawCaptureIfNeeded()")
        val stopMarkerIndex = stopBlock.indexOf("gpuRecording.markStopRequestedForEvidence()")
        val detachIndex = stopBlock.indexOf("detachInternalRecordingFeedBeforeEncoderStop(")
        val encoderStopIndex = stopBlock.indexOf("val path = gpuRecording.stop()")
        val clearAfterStopIndex = stopBlock.indexOf(
            "vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)",
            encoderStopIndex
        )

        assertTrue(stopMarkerIndex >= 0)
        assertTrue(detachIndex > stopMarkerIndex)
        assertTrue(stopBlock.contains("keepOffscreenEncoderSurfaceForEos = true"))
        assertTrue(encoderStopIndex > detachIndex)
        assertTrue(clearAfterStopIndex > encoderStopIndex)
        assertTrue(detachBlock.contains("if (!keepOffscreenEncoderSurfaceForEos)"))
        assertTrue(detachBlock.contains("keeping offscreen EGL encoder surface alive until MediaCodec EOS"))
        assertTrue(detachBlock.contains("pipeline.awaitIdleBeforeReaderClose("))
        assertTrue(detachBlock.contains("timeoutMs = if (keepOffscreenEncoderSurfaceForEos) 2_000L else 750L"))
        assertTrue(
            detachBlock.indexOf("pipeline.awaitIdleBeforeReaderClose(") <
                detachBlock.indexOf("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)")
        )
    }

    @Test
    fun `preview and encoder receive independent Neo color science policies`() {
        val renderConfigBlock = source
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun buildVulkanPreviewTransform(")

        assertTrue(renderConfigBlock.contains("VulkanColorScienceUniformPolicy.resolve("))
        assertTrue(renderConfigBlock.contains("previewColorScience ="))
        assertTrue(renderConfigBlock.contains("encoderColorScience ="))
        assertTrue(renderConfigBlock.contains("logDeliveryEnabled = false"))
        assertTrue(renderConfigBlock.contains("logDeliveryEnabled = renderContract.logCaptureActive"))
    }

    @Test
    fun `processed gpu recording eligibility uses Vulkan capability before imported frame validation`() {
        val parityResolver = source
            .substringAfter("private fun activeRecordingPipelineParityPlan(")
            .substringBefore("private fun activePreviewRecordingRenderGraphLock(")
        val qualification = source
            .substringAfter("private fun vulkanRecordingPathQualified(): Boolean {")
            .substringBefore("private fun currentVulkanRecordingThroughputSignature()")
        val validationHelpers = source
            .substringAfter("private fun currentVulkanRecordingThroughputSignature()")
            .substringBefore("private fun activePreviewRecordingRenderGraphLock(")

        assertTrue(parityResolver.contains("vulkanRecordingPathQualified()"))
        assertTrue(qualification.contains("vulkanSupported.value"))
        assertTrue(qualification.contains("vulkanRuntime.isPipelineHealthy()"))
        assertFalse(qualification.contains("nativeVisiblePreviewActive"))
        assertFalse(qualification.contains("nativeVisiblePreviewConfirmed"))
        assertFalse(qualification.contains("!directPreviewSurfaceActive"))
        assertFalse(qualification.contains("stats.frameImportEnabled"))
        assertFalse(qualification.contains("stats.previewSurfaceBound"))
        assertFalse(qualification.contains("stats.sessionImportedFrames > 0L"))
        assertFalse(qualification.contains("!hasValidatedVulkanRecordingEvidenceForCurrentSignature()"))
        assertFalse(qualification.contains("lastValidatedGpuRecordingEvidence"))
        assertTrue(validationHelpers.contains("lastValidatedGpuRecordingEvidence"))
        assertTrue(validationHelpers.contains("evidence.frameImportEnabled"))
        assertTrue(validationHelpers.contains("evidence.encoderSurfaceBound"))
        assertTrue(validationHelpers.contains("evidence.importedFrames > 0L"))
        assertTrue(source.contains("awaitValidatedProcessedGpuRecordingFrames"))
        assertTrue(source.contains("Internal hardware-import recording started; visible preview unchanged"))
    }

    @Test
    fun `recording parity refreshes preserve selected active feed path`() {
        val parityResolver = source
            .substringAfter("private fun activeRecordingPipelineParityPlan(")
            .substringBefore("private fun vulkanRecordingPathQualified()")

        assertTrue(parityResolver.contains("actualFeedPathOverride ?: activeRecordingFeedPath"))
        assertTrue(source.contains("activeRecordingFeedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER"))
        assertTrue(source.contains("activeRecordingFeedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE"))
    }

    @Test
    fun `visible vulkan preview waits for camera2 surface release before native acquisition`() {
        assertTrue(cameraControllerSource.contains("onPreviousSessionClosed: (() -> Unit)? = null"))
        assertTrue(cameraControllerSource.contains("previousSessionClosedCallbacks"))
        assertTrue(cameraControllerSource.contains("override fun onClosed(s: CameraCaptureSession)"))
        assertTrue(source.contains("onPreviousSessionClosed = {"))
        assertTrue(source.contains("completeNativePreviewHandoffAfterCameraRelease"))
    }

    @Test
    fun `visible vulkan preview is bound only after camera session release confirmation`() {
        assertTrue(source.contains("nativePreviewCameraReleaseConfirmed = false"))
        assertTrue(source.contains("nativePreviewCameraReleaseConfirmed = true"))
        assertTrue(source.contains("VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface"))
        assertTrue(source.contains("cameraOwnsVisiblePreviewSurface(surface)"))
    }

    @Test
    fun `visible vulkan preview retains only active recording hardware import during surface handoff`() {
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val nativeBindingBlock = bindingBlock
            .substringAfter("if (preferNativePreview) {")
            .substringBefore("val nativeBound")
        val fallbackBlock = bindingBlock
            .substringAfter("        stopNativePreviewWatchdog()\n        clearNativePreviewHandoffState()")
        val preserveBlock = source
            .substringAfter("private fun recordingImportFeedPathToPreserveForPreviewBinding(): RecordingFeedPath? {")
            .substringBefore("private fun shouldPreserveInternalRecordingImportForPreviewBinding()")

        assertTrue(nativeBindingBlock.contains("camera.setInternalRecordingHardwareImportEnabled(true)"))
        assertTrue(
            nativeBindingBlock.indexOf("camera.setInternalRecordingHardwareImportEnabled(true)") <
                nativeBindingBlock.indexOf("nativePreviewHandoffPending = true")
        )
        assertTrue(fallbackBlock.contains("preservedRecordingImportFeedPath"))
        assertTrue(source.contains("shouldPreserveInternalRecordingImportForPreviewBinding()"))
        assertTrue(source.contains("feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER"))
        assertTrue(source.contains("recordingStartInProgress.get()"))
        assertTrue(preserveBlock.contains("_uiMode.value == AppMode.VIDEO"))
        assertTrue(preserveBlock.contains("prearmedRecordingSurface != null"))
        assertTrue(preserveBlock.contains("prearmedRecordingSurfaceAttachedToSession"))
        assertTrue(preserveBlock.contains("prearmedRecordingFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER"))
        assertTrue(preserveBlock.contains("shouldKeepRecordingSurfacePrearmed()"))
        assertFalse(preserveBlock.contains("prearmedFeedPath"))
        assertTrue(fallbackBlock.contains("if (preservedRecordingImportFeedPath != null)"))
        assertTrue(fallbackBlock.contains("camera.setInternalRecordingHardwareImportEnabled(true)"))
        assertTrue(fallbackBlock.contains("camera.setHardwareImportP010SourceEnabled(true)"))
        assertTrue(fallbackBlock.contains("vulkanRuntime.setFrameImportEnabled(true)"))
        assertTrue(fallbackBlock.contains("camera.setInternalRecordingHardwareImportEnabled(false)"))
    }

    @Test
    fun `visible vulkan preview defers native surface acquisition until camera producer settles`() {
        val handoffBlock = source
            .substringAfter("private fun completeNativePreviewHandoffAfterCameraRelease(")
            .substringBefore("private fun scheduleNativePreviewRetry(")

        assertTrue(handoffBlock.contains("delay(VulkanVisiblePreviewPolicy.SURFACE_HANDOFF_SETTLE_MS)"))
    }

    @Test
    fun `gl preview compositor waits for camera2 surface release before egl acquisition`() {
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val glReleaseBlock = bindingBlock
            .substringAfter("if (glPreviewNeedsReleasedVisibleSurface) {")
            .substringBefore("glPreviewSurfaceReleasePending = false")
        val refreshBlock = source
            .substringAfter("val glPreviewReleasePendingForSurface =")
            .substringBefore("internal fun refreshPreviewPipelineIfNeeded(")
        val handoffBlock = source
            .substringAfter("private fun completeGlPreviewCompositorHandoffAfterCameraRelease(surface: Surface)")
            .substringBefore("private fun scheduleNativePreviewRetry(surface: Surface)")

        assertTrue(source.contains("completeGlPreviewCompositorHandoffAfterCameraRelease(surface)"))
        assertTrue(glReleaseBlock.contains("camera.setPreviewSurface(null)"))
        assertFalse(glReleaseBlock.contains("delay(GL_PREVIEW_ENGAGE_RETRY_DELAY_MS)"))
        assertTrue(refreshBlock.contains("glPreviewReleasePendingForSurface"))
        assertTrue(refreshBlock.contains("forceRebuild = true"))
        assertTrue(refreshBlock.contains("onPreviousSessionClosed = {"))
        assertTrue(source.contains("GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS"))
        assertTrue(source.contains("coerceAtLeast(1_500L)"))
        assertTrue(source.contains("GL_PREVIEW_ENGAGE_RETRY_DELAY_MS = 1_200L"))
        assertTrue(handoffBlock.contains("delay(GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS)"))
        assertTrue(handoffBlock.indexOf("delay(GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS)") <
            handoffBlock.indexOf("glPreviewVisibleSurfaceReleaseConfirmed = true"))
    }

    @Test
    fun `processed gpu validation failure blocks camera2 bypass in strict high end mode`() {
        val validationFailureBlock = source
            .substringAfter("if (!awaitValidatedProcessedGpuRecordingFrames()) {")
            .substringBefore("gpuRecording.sceneMetadataDescription = buildRecordingSceneMetadata(")
        val retryHelperBlock = source
            .substringAfter("suspend fun retryPreviewContinuityAfterInternalHardwareImportFailure(")
            .substringBefore("suspend fun startInternalHardwareImportRecording()")
        val strictGuardIndex = retryHelperBlock.indexOf("if (strictHighEndQualityEnabled())")
        val fallbackRetryIndex = retryHelperBlock.indexOf("return startCamera2PreviewParityRecording()")
        val strictGuardBlock = retryHelperBlock
            .substringAfter("if (strictHighEndQualityEnabled())")
            .substringBefore("Log.w(")

        assertTrue(validationFailureBlock.contains("retryPreviewContinuityAfterInternalHardwareImportFailure("))
        assertTrue(validationFailureBlock.contains("return retryPreviewContinuityAfterInternalHardwareImportFailure("))
        assertTrue(strictGuardIndex >= 0)
        assertTrue(fallbackRetryIndex > strictGuardIndex)
        assertTrue(strictGuardBlock.contains("Camera2 preview-continuity fallback blocked"))
        assertTrue(strictGuardBlock.contains("cleanupFailedRecordingStart("))
        assertTrue(strictGuardBlock.contains("return false"))
        assertFalse(strictGuardBlock.contains("gpuRecording.prepare()"))
        assertFalse(strictGuardBlock.contains("startCamera2PreviewParityRecording()"))
    }

    @Test
    fun `internal hardware import uses bounded session refresh before retry path`() {
        val internalImportBlock = source
            .substringAfter("suspend fun startInternalHardwareImportRecording(): Boolean {")
            .substringBefore("if (!awaitValidatedProcessedGpuRecordingFrames())")
        val refreshCall = internalImportBlock
            .substringAfter("awaitFreshPreviewSessionBeforeRecording(")
            .substringBefore(")")

        assertTrue(source.contains(".INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS"))
        assertTrue(refreshCall.contains("timeoutMs ="))
        assertTrue(refreshCall.contains("ProcessedGpuRecordingRequirementPolicy"))
        assertTrue(refreshCall.contains(".INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS"))
        assertTrue(refreshCall.contains("waitForHardwareImportActivity = true"))
    }

    @Test
    fun `offscreen proof is checked before codec start and rejection aborts without p010 downgrade`() {
        val offscreenBlock = source
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean {")
        val rejectionBlock = source
            .substringAfter("suspend fun rejectOffscreenAhbEglRecordingNoDowngrade(")
            .substringBefore("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
        val bindIndex = offscreenBlock.indexOf("setOffscreenAhbEglEncoderSurface(")
        val startIndex = offscreenBlock.indexOf("gpuRecording.start()")
        val submissionEnableIndex =
            offscreenBlock.indexOf("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(true)")

        assertTrue(bindIndex >= 0)
        assertTrue(startIndex > bindIndex)
        assertTrue(submissionEnableIndex > startIndex)
        assertTrue(offscreenBlock.contains("rejectOffscreenAhbEglRecordingNoDowngrade(message)"))
        assertTrue(rejectionBlock.contains("legacy bytebuffer P010"))
        assertTrue(rejectionBlock.contains("gpuRecording.discardPreparedSession()"))
        assertTrue(rejectionBlock.contains("cleanupFailedRecordingStart("))
        assertFalse(rejectionBlock.contains("RecordingVideoInputMode.BYTEBUFFER_P010"))
        assertFalse(rejectionBlock.contains("gpuRecording.prepare()"))
        assertFalse(source.contains("offscreenProofRejected"))
        assertFalse(source.contains("startByteBufferP010Recording() else offscreenStarted"))
    }

    @Test
    fun `post start offscreen source precision rejection aborts without legacy P010`() {
        val rejectionBlock = source
            .substringAfter("suspend fun rejectOffscreenAhbEglRecordingNoDowngrade(")
            .substringBefore("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
        val offscreenBlock = source
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean {")
        val validationFailureBlock = offscreenBlock
            .substringAfter("if (!awaitValidatedProcessedGpuRecordingFrames(requireSustainedTargetFps = true))")
            .substringBefore("gpuRecording.sceneMetadataDescription")

        assertTrue(rejectionBlock.contains("vulkanRuntime.setFrameImportEnabled(false)"))
        assertTrue(rejectionBlock.contains("setOffscreenAhbEglEncoderSurface(null, 0, 0)"))
        assertTrue(rejectionBlock.contains("pipeline.setDedicatedHardwareImportEnabled(false)"))
        assertTrue(rejectionBlock.contains("camera.setInternalRecordingHardwareImportEnabled(false)"))
        assertTrue(rejectionBlock.contains("camera.setHardwareImportP010SourceEnabled(false)"))
        assertTrue(rejectionBlock.contains("gpuRecording.discardPreparedSession()"))
        assertFalse(rejectionBlock.contains("RecordingVideoInputMode.BYTEBUFFER_P010"))
        assertFalse(rejectionBlock.contains("gpuRecording.prepare()"))
        assertTrue(validationFailureBlock.contains("currentEncoderSurfaceStatus"))
        assertTrue(validationFailureBlock.contains("!offscreenStatus.sourcePrecisionProven"))
        assertTrue(validationFailureBlock.contains("rejectOffscreenAhbEglRecordingNoDowngrade("))
        assertFalse(validationFailureBlock.contains("retryPreviewContinuityAfterInternalHardwareImportFailure("))
    }

    @Test
    fun `offscreen hardware import requests proven P010 source before session rebuild`() {
        val offscreenBlock = source
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean {")
        val sessionRebuildIndex =
            offscreenBlock.indexOf("awaitFreshPreviewSessionBeforeRecording(")
        val p010EnableIndex =
            offscreenBlock.indexOf("camera.setHardwareImportP010SourceEnabled(true)")
        val sourceSupportIndex =
            offscreenBlock.indexOf("camera.supportsHardwareImportP010Source(p010SourceSize, recording.fps)")

        assertTrue(cameraControllerSource.contains("private fun hardwareImportImageFormat(): Int"))
        assertTrue(cameraControllerSource.contains("getOutputMinFrameDuration(ImageFormat.YCBCR_P010"))
        assertTrue(cameraControllerSource.contains("ImageFormat.YCBCR_P010"))
        assertTrue(cameraControllerSource.contains("ImageFormat.PRIVATE"))
        assertTrue(sourceSupportIndex >= 0)
        assertTrue(p010EnableIndex >= 0)
        assertTrue(sessionRebuildIndex > p010EnableIndex)
        assertTrue(offscreenBlock.contains("Offscreen EGL P010 hardware-import source is unavailable"))
        assertTrue(offscreenBlock.contains("rejectOffscreenAhbEglRecordingNoDowngrade(message)"))
    }

    @Test
    fun `offscreen ahb egl preserves real encoder timestamps instead of constant surface cadence`() {
        val prearmOffscreenBlock = source
            .substringAfter("if (prearmParityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {")
            .substringBefore("gpuRecording.setPersistentInputSurfaceAllowed(false)")
        val genericStartCadenceBlock = source
            .substringAfter("beginCamera2FrameLineageSession(recordingParityPlan)")
            .substringBefore("updatePreviewViewAwait { resetPreviewFpsTracking() }")
        val offscreenStartBlock = source
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("val actualOffscreenParityPlan =")

        assertTrue(prearmOffscreenBlock.contains("gpuRecording.setRealtimeSurfaceCadenceEnabled(false)"))
        assertTrue(genericStartCadenceBlock.contains("RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER"))
        assertFalse(genericStartCadenceBlock.contains("RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER"))
        // Full-sensor constant-fps mode (reversible, default ON) muxes ONLY the full-sensor offscreen clip as
        // CFR (uniform 25 fps); every OTHER offscreen size still keeps real egl-presentation PTS (VFR). The
        // start-site cadence is therefore gated on the encoder size via FullSensorConstantFpsPolicy rather
        // than a hardcoded `false` (flag OFF ⇒ useConstantMuxCadence returns false ⇒ byte-identical VFR).
        assertTrue(
            offscreenStartBlock.contains(
                "FullSensorConstantFpsPolicy.useConstantMuxCadence"
            )
        )
    }

    @Test
    fun `internal hardware import preserves requested encoder size after detaching camera2 record surface`() {
        val internalImportBlock = source
            .substringAfter("suspend fun startInternalHardwareImportRecording(): Boolean {")
            .substringBefore("val encoderBound = vulkanRuntime.setEncoderSurface(encoderSurface)")
        val detachIndex = internalImportBlock.indexOf("camera.setRecordSurface(null, refreshSession = false)")
        val restoreEncoderIndex = internalImportBlock.indexOf("camera.setRecordingEncoderTargetSize(preparedEncoderSize)", detachIndex)
        val hardwareImportIndex = internalImportBlock.indexOf("camera.setHardwareImportTargetSize(preparedEncoderSize)", detachIndex)

        assertTrue(detachIndex >= 0)
        assertTrue(restoreEncoderIndex > detachIndex)
        assertTrue(hardwareImportIndex > restoreEncoderIndex)
    }

    @Test
    fun `internal hardware import restores encoder sized reader immediately before session rebuild`() {
        val internalImportBlock = source
            .substringAfter("suspend fun startInternalHardwareImportRecording(): Boolean {")
            .substringBefore("if (!awaitValidatedProcessedGpuRecordingFrames())")
        val importEnabledIndex = internalImportBlock.indexOf("vulkanRuntime.setFrameImportEnabled(true)")
        val finalImportSizeIndex =
            internalImportBlock.indexOf("camera.setHardwareImportTargetSize(preparedEncoderSize)", importEnabledIndex)
        val sessionRebuildIndex =
            internalImportBlock.indexOf("awaitFreshPreviewSessionBeforeRecording(", importEnabledIndex)

        assertTrue(importEnabledIndex >= 0)
        assertTrue(finalImportSizeIndex > importEnabledIndex)
        assertTrue(sessionRebuildIndex > finalImportSizeIndex)
    }

    @Test
    fun `recording start retires stale prearmed persistent surface before preparing selected size`() {
        val startBlock = source
            .substringAfter("val expectedPrearmSignature = currentRecordingPrearmSignature(recordingParityPlan)")
            .substringBefore("val prepareStartElapsedMs = SystemClock.elapsedRealtime()")
        val staleBranchIndex =
            startBlock.indexOf("if (gpuRecordState.value is GpuRecordingState.Ready &&")
        val releaseIndex =
            startBlock.indexOf("releasePrearmedRecordingSurface(refreshSession = false)", staleBranchIndex)

        assertTrue(source.contains("recordingStartInProgress"))
        assertTrue(source.contains("recordingStartInProgress.compareAndSet(false, true)"))
        assertTrue(source.contains("recordingStartInProgress.set(false)"))
        assertTrue(staleBranchIndex >= 0)
        assertTrue(startBlock.substring(staleBranchIndex, releaseIndex).contains("!reusablePrearmedSurface"))
        assertTrue(startBlock.substring(staleBranchIndex, releaseIndex).contains("!reusableOffscreenPrearmedSurface"))
        assertTrue(releaseIndex > staleBranchIndex)
        assertFalse(startBlock.contains("gpuRecording.discardPreparedSession()\n                    prearmedRecordingSurface = null"))
    }

    @Test
    fun `background prearm cannot publish stale surface after recording start begins`() {
        val skipReasonBlock = source
            .substringAfter("private fun idleRecordingSurfacePrearmSkipReason(): String?")
            .substringBefore("private fun shouldKeepRecordingSurfacePrearmed()")
        val prearmBlock = source
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun scheduleRecordingSurfacePrearmRetry()")
        val surfacePrepareIndex = prearmBlock.indexOf("val surface = gpuRecording.prepare()")
        val startGuardIndex = prearmBlock.indexOf("recordingStartInProgress.get()", surfacePrepareIndex)
        val retireIndex = prearmBlock.indexOf("gpuRecording.releasePersistentInputSurface()", startGuardIndex)

        assertTrue(skipReasonBlock.contains("if (recordingStartInProgress.get()) return \"recording-starting\""))
        assertTrue(surfacePrepareIndex >= 0)
        assertTrue(startGuardIndex > surfacePrepareIndex)
        assertTrue(retireIndex > startGuardIndex)
    }

    @Test
    fun `processed gpu validation accepts near target startup before blocking low throughput`() {
        val validationBlock = source
            .substringAfter("private suspend fun awaitValidatedProcessedGpuRecordingFrames(")
            .substringBefore("private fun preserveValidatedGpuRecordingEvidence(")
        val throughputRejectIndex =
            validationBlock.indexOf("ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(lastFailureMessage)")
        val nearTargetStartupIndex =
            validationBlock.indexOf("ProcessedGpuRecordingRequirementPolicy.validateNearTargetStartupRunning(")
        val strictSustainedGateIndex =
            validationBlock.indexOf("if (requireSustainedTargetFps)")
        val markFailureIndex =
            validationBlock.indexOf("markVulkanRecordingThroughputFailure(lastFailureMessage)", nearTargetStartupIndex)

        assertTrue(throughputRejectIndex >= 0)
        assertTrue(strictSustainedGateIndex > throughputRejectIndex)
        assertTrue(strictSustainedGateIndex < nearTargetStartupIndex)
        assertTrue(nearTargetStartupIndex > throughputRejectIndex)
        assertTrue(markFailureIndex > nearTargetStartupIndex)
        assertTrue(validationBlock.substring(nearTargetStartupIndex, markFailureIndex).contains("return true"))
    }

    @Test
    fun `strict processed gpu validation waits on import stat changes instead of fixed polling`() {
        val waitHelperBlock = source
            .substringAfter("private suspend fun awaitNextVulkanImportStatsOrTimeout(")
            .substringBefore("private suspend fun awaitPrearmedOffscreenHardwareImportActivity(")
        val prearmedBlock = source
            .substringAfter("private suspend fun awaitPrearmedOffscreenHardwareImportActivity(")
            .substringBefore("private suspend fun awaitFirstProcessedGpuRecordingFrame(")
        val firstFrameBlock = source
            .substringAfter("private suspend fun awaitFirstProcessedGpuRecordingFrame(")
            .substringBefore("private fun launchStrictProcessedGpuSustainedValidation(")
        val sustainedBlock = source
            .substringAfter("private suspend fun awaitValidatedProcessedGpuRecordingFrames(")
            .substringBefore("private fun preserveValidatedGpuRecordingEvidence(")

        assertTrue(waitHelperBlock.contains("vulkanImportStats.first { it != previous }"))
        assertTrue(prearmedBlock.contains("awaitNextVulkanImportStatsOrTimeout(stats, deadlineElapsedMs)"))
        assertTrue(firstFrameBlock.contains("awaitNextVulkanImportStatsOrTimeout(stats, firstFrameDeadlineElapsedMs)"))
        assertTrue(sustainedBlock.contains("awaitNextVulkanImportStatsOrTimeout(stats, sustainedDeadlineElapsedMs)"))
        assertFalse(prearmedBlock.contains("delay(25L)"))
        assertFalse(firstFrameBlock.contains("delay(25L)"))
        assertFalse(sustainedBlock.contains("delay(25L)"))
        assertFalse(sustainedBlock.contains("delay(50L)"))
    }

    @Test
    fun `measured vulkan recording throughput failure blocks repeated hardware import attempts`() {
        val qualificationBlock = source
            .substringAfter("private fun vulkanRecordingPathQualified(): Boolean {")
            .substringBefore("private fun activePreviewRecordingRenderGraphLock(")
        val validationBlock = source
            .substringAfter("if (ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(lastFailureMessage)) {")
            .substringBefore("val startupContinuityDecision = ProcessedGpuRecordingRequirementPolicy.validateStartupContinuity(")

        assertTrue(source.contains("vulkanRecordingThroughputBlockedUntilElapsedMs"))
        assertTrue(source.contains("vulkanRecordingThroughputBlockedSignature"))
        assertTrue(source.contains("private fun markVulkanRecordingThroughputFailure("))
        assertTrue(source.contains("ProcessedGpuRecordingRequirementPolicy.THROUGHPUT_FAILURE_BLOCK_MS"))
        assertTrue(source.contains("private suspend fun awaitGpuRecordingState("))
        assertFalse(source.contains("delay(200L)\n                    if (gpuRecordState.value !is GpuRecordingState.Recording)"))
        assertTrue(qualificationBlock.contains("currentVulkanRecordingThroughputSignature()"))
        assertTrue(qualificationBlock.contains("SystemClock.elapsedRealtime() < vulkanRecordingThroughputBlockedUntilElapsedMs"))
        assertTrue(qualificationBlock.indexOf("vulkanRecordingThroughputBlockedUntilElapsedMs") <
            qualificationBlock.indexOf("vulkanRuntime.isPipelineHealthy()"))
        assertTrue(validationBlock.contains("validateNearTargetStartupRunning("))
        assertTrue(validationBlock.contains("markVulkanRecordingThroughputFailure("))
    }

    @Test
    fun `unvalidated vulkan recording path can attempt codec owned hardware import surface`() {
        val qualificationBlock = source
            .substringAfter("private fun vulkanRecordingPathQualified(): Boolean {")
            .substringBefore("private fun activePreviewRecordingRenderGraphLock(")

        assertTrue(source.contains("private fun hasValidatedVulkanRecordingEvidenceForCurrentSignature("))
        assertFalse(qualificationBlock.contains("!hasValidatedVulkanRecordingEvidenceForCurrentSignature()"))
        assertTrue(qualificationBlock.contains("vulkanSupported.value"))
        assertTrue(qualificationBlock.contains("vulkanRuntime.isPipelineHealthy()"))
    }

    @Test
    fun `hardware import recording uses fresh codec surface instead of persistent prearm`() {
        val startBlock = source
            .substringAfter("val expectedPrearmSignature = currentRecordingPrearmSignature(recordingParityPlan)")
            .substringBefore("val prepareStartElapsedMs = SystemClock.elapsedRealtime()")
        val skipBlock = source
            .substringAfter("private fun idleRecordingSurfacePrearmSkipReason(): String?")
            .substringBefore("private fun shouldKeepRecordingSurfacePrearmed()")

        assertTrue(startBlock.contains("hardwareImportUsesCodecOwnedSurface"))
        assertTrue(startBlock.contains("recordingParityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER"))
        assertTrue(startBlock.contains("!hardwareImportUsesCodecOwnedSurface &&"))
        assertTrue(startBlock.contains("Recording start: retiring persistent pre-arm for codec-owned Vulkan hardware-import surface"))
        assertTrue(startBlock.contains("releasePrearmedRecordingSurface(refreshSession = false)"))
        assertTrue(startBlock.contains("gpuRecording.setPersistentInputSurfaceAllowed(!hardwareImportUsesCodecOwnedSurface)"))
        assertTrue(skipBlock.contains("return \"hardware-import-uses-codec-owned-surface\""))
    }

    @Test
    fun `hdr debug record clip waits for slow first encoder surface bind instead of retrying`() {
        val debugDelayBlock = mainActivitySource
            .substringAfter("private fun resolveDebugRecordingVerifyDelayMs(preset: CameraModePreset?): Long =")
            .substringBefore("private fun isHighThroughputDebugCapture")

        assertTrue(mainActivitySource.contains("DEBUG_HDR_RECORDING_VERIFY_DELAY_MS = 24_000L"))
        assertTrue(mainActivitySource.contains("DEBUG_HDR_RECORDING_BACKEND_SETTLE_GRACE_MS = 1_200L"))
        assertTrue(debugDelayBlock.contains("CameraModePreset.HDR -> DEBUG_HDR_RECORDING_VERIFY_DELAY_MS"))
    }

    @Test
    fun `hardware import reader requests sampled camera buffers without gpu color output writes`() {
        val usageBlock = cameraControllerSource
            .substringAfter("private fun hardwareImportUsageFlags(): Long {")
            .substringBefore("private fun effectiveJpegQuality")

        assertTrue(usageBlock.contains("HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE"))
        assertFalse(usageBlock.contains("HardwareBuffer.USAGE_GPU_COLOR_OUTPUT"))
    }

    @Test
    fun `internal hardware import receives encoder hdr dynamic range role`() {
        val hdrRoleBlock = cameraControllerSource
            .substringAfter("private fun hdrSurfaceRoleFor(surface: Surface): HdrSurfaceRole = when (surface) {")
            .substringBefore("private fun sessionSurfaceKindFor")

        assertTrue(hdrRoleBlock.contains("hardwareImportReader?.surface -> HdrSurfaceRole.ENCODER"))
        assertFalse(hdrRoleBlock.contains("hardwareImportReader?.surface -> HdrSurfaceRole.ANALYSIS"))
    }

    @Test
    fun `stale preview surface target also triggers a camera session rebuild`() {
        val recoveryBlock = cameraControllerSource
            .substringAfter("private fun recoverUnconfiguredRecordSurfaceIfNeeded(")
            .substringBefore("private fun handleStartPreviewIllegalState(")

        assertTrue(recoveryBlock.contains("CameraPreviewStartFailurePolicy.isUnconfiguredSurfaceFailure(error)"))
        assertFalse(recoveryBlock.contains("recordSurface == null"))
    }

    @Test
    fun `repeating request targets are preflighted against configured session surfaces`() {
        val startPreviewBlock = cameraControllerSource
            .substringAfter("override fun startPreview(expectedGeneration: Long, expectedSession: CameraCaptureSession?)")
            .substringBefore("private fun identitySurfaceSet")
        val sessionConfiguredBlock = cameraControllerSource
            .substringAfter("override fun onConfigured(s: CameraCaptureSession)")
            .substringBefore("maybeArmPostRebuildColorHold()")

        assertTrue(cameraControllerSource.contains("private val configuredSessionSurfaces"))
        assertTrue(sessionConfiguredBlock.contains("configuredSessionSurfaces[s] = identitySurfaceSet(surfaces)"))
        assertTrue(startPreviewBlock.contains("val requestTargets = mutableListOf<Surface>()"))
        assertTrue(startPreviewBlock.contains("repeatingTargetsConfiguredForSession(sess, expectedGeneration, requestTargets)"))
        assertTrue(cameraControllerSource.contains("request targets not configured in current Camera2 session"))
        assertTrue(cameraControllerSource.contains("refreshOutputsAndSession(rebuildReaders = false, forceRebuild = true)"))
    }

    @Test
    fun `startup keeps direct camera2 preview visible instead of handing off before qualification`() {
        val activationBlock = source
            .substringAfter("private fun shouldAttemptNativeVulkanPreview(")
            .substringBefore("private fun cameraOwnsVisiblePreviewSurface(")
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")

        assertTrue(activationBlock.contains("startupDirectPreviewActive || startupFeatureGateActive"))
        assertTrue(activationBlock.contains("directPreviewSurfaceActive"))
        assertTrue(activationBlock.contains("cameraOwnsVisiblePreviewSurface"))
        assertTrue(bindingBlock.contains("cameraOwnsVisiblePreviewSurface"))
    }

    @Test
    fun `gl preview compositor waits for camera2 to release visible surface`() {
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val targetBlock = source
            .substringAfter("private fun applyGlPreviewCompositorTarget(")
            .substringBefore("/** Post ONE delayed re-attempt")
        val refreshBlock = source
            .substringAfter("val glPreviewReleasePendingForSurface =")
            .substringBefore("internal fun refreshPreviewPipelineIfNeeded(")
        val awaitRefreshBlock = source
            .substringAfter("private suspend fun awaitFreshPreviewSessionBeforeRecording(")
            .substringBefore("private suspend fun cleanupFailedRecordingStart(")
        val releaseGateIndex = bindingBlock.indexOf("glPreviewNeedsReleasedVisibleSurface")
        val clearPreviewIndex = bindingBlock.indexOf("camera.setPreviewSurface(null)", releaseGateIndex)
        val pendingWaitIndex = bindingBlock.indexOf("glPreviewSurfaceReleasePending &&", clearPreviewIndex)
        val compositorStartIndex = bindingBlock.indexOf("applyGlPreviewCompositorTarget(surface, previewSize)")

        assertTrue(source.contains("private var glPreviewVisibleSurfaceReleaseConfirmed = false"))
        assertTrue(releaseGateIndex >= 0)
        assertTrue(clearPreviewIndex > releaseGateIndex)
        assertTrue(pendingWaitIndex > clearPreviewIndex)
        assertTrue(compositorStartIndex > pendingWaitIndex)
        assertTrue(bindingBlock.contains("cameraOwnsVisiblePreviewSurface(surface)"))
        assertTrue(source.contains("private fun shouldCompositeVisibleGlPreview()"))
        assertTrue(bindingBlock.contains("shouldCompositeVisibleGlPreview()"))
        assertTrue(bindingBlock.contains("!glPreviewVisibleSurfaceReleaseConfirmed"))
        assertTrue(bindingBlock.contains("glPreviewSurfaceReleasePending"))
        assertTrue(source.contains("private fun visibleGlPreviewSurfaceReadyForNewEglAcquire("))
        assertTrue(targetBlock.contains("visibleGlPreviewSurfaceReadyForNewEglAcquire(visibleSurface)"))
        assertTrue(bindingBlock.substring(pendingWaitIndex, compositorStartIndex).contains("return false"))
        assertFalse(bindingBlock.substring(clearPreviewIndex, compositorStartIndex).contains("delay(GL_PREVIEW_ENGAGE_RETRY_DELAY_MS)"))
        assertTrue(refreshBlock.contains("glPreviewReleasePendingForSurface"))
        assertTrue(refreshBlock.contains("forceRebuild = true"))
        assertTrue(refreshBlock.contains("onPreviousSessionClosed = {"))
        assertTrue(refreshBlock.contains("completeGlPreviewCompositorHandoffAfterCameraRelease(surface)"))
        assertTrue(awaitRefreshBlock.contains("pendingGlPreviewReleaseSurface"))
        assertTrue(awaitRefreshBlock.contains("completeGlPreviewCompositorHandoffAfterCameraRelease(surface)"))
    }

    @Test
    fun `gl preview compositor does not churn visible surface during recording start`() {
        val retryBlock = source
            .substringAfter("private fun scheduleGlPreviewEngageRetry()")
            .substringBefore("/**\n     * Engage/disengage the GL preview compositor")
        val syncBlock = source
            .substringAfter("private fun syncGlPreviewCompositorBinding()")
            .substringBefore("private fun applyPreviewPipelineBinding(")
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val preserveBlock = source
            .substringAfter("private fun recordingImportFeedPathToPreserveForPreviewBinding(): RecordingFeedPath? {")
            .substringBefore("private fun shouldPreserveInternalRecordingImportForPreviewBinding()")
        val blockerBlock = source
            .substringAfter("private fun recordingStartOrActiveBlocksVisiblePreviewRebind(): Boolean {")
            .substringBefore("private fun markGlPreviewOwnerCooldown(")

        assertTrue(source.contains("private fun recordingStartOrActiveBlocksVisiblePreviewRebind()"))
        assertTrue(source.contains("private fun markGlPreviewOwnerCooldown("))
        assertTrue(source.contains("releaseVisibleGlPreviewCompositorsForRecordingFeed("))
        assertTrue(source.contains("Visible GL preview compositor released for"))
        assertTrue(source.contains("recordingStartInProgress.get()"))
        assertTrue(source.contains("private fun glPreviewOwnerCooldownRemainingMs(): Long"))
        assertTrue(source.contains("private fun scheduleGlPreviewOwnerCooldownResync(reason: String)"))
        assertTrue(source.contains("fun beginModeSwitchPreviewWorkBatch() {\n        markGlPreviewOwnerCooldown(GL_PREVIEW_MODE_SWITCH_OWNER_COOLDOWN_MS)"))
        assertTrue(source.contains("private fun applyResolvedRecordingConfig(") && source.contains(") {\n        markGlPreviewOwnerCooldown()"))
        assertTrue(retryBlock.contains("recordingStartOrActiveBlocksVisiblePreviewRebind()"))
        assertTrue(syncBlock.contains("recordingStartOrActiveBlocksVisiblePreviewRebind()"))
        assertTrue(bindingBlock.contains("!glPreviewOwnerTransitionBlocksVisiblePreviewRebind()"))
        assertTrue(bindingBlock.contains("recordingImportFeedPathToPreserveForPreviewBinding()"))
        assertTrue(bindingBlock.contains("camera.setInternalRecordingHardwareImportEnabled(true)"))
        assertTrue(bindingBlock.contains("camera.setHardwareImportP010SourceEnabled(true)"))
        assertTrue(blockerBlock.contains("val recordingActiveOrStopping"))
        assertTrue(blockerBlock.contains("shouldPreserveInternalRecordingImportForPreviewBinding()"))
        assertTrue(blockerBlock.contains("activeRecordingFeedPath != null"))
        assertTrue(blockerBlock.contains("Idle offscreen prearm must keep the hidden P010 source attached"))
        assertFalse(
            "A harmless owner cooldown must not masquerade as active recording and spend the retry budget",
            blockerBlock.contains("SystemClock.elapsedRealtime() < glPreviewOwnerCooldownUntilElapsedMs")
        )
        assertTrue(preserveBlock.contains("_uiMode.value == AppMode.VIDEO"))
        assertTrue(preserveBlock.contains("prearmedRecordingSurface != null"))
        assertTrue(preserveBlock.contains("prearmedRecordingSurfaceAttachedToSession"))
        assertTrue(preserveBlock.contains("prearmedRecordingFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER"))
        assertTrue(preserveBlock.contains("shouldKeepRecordingSurfacePrearmed()"))
        assertFalse(preserveBlock.contains("prearmedFeedPath"))
    }

    @Test
    fun `raw preview mode exit releases stale visible compositor state before next mode binds`() {
        val modeBlock = source
            .substringAfter("private fun applyCinemaModeSelection(")
            .substringBefore("private fun deriveCinemaModeFromFlags")
        val releaseBlock = source
            .substringAfter("private fun releaseRawPreviewMonitorForModeExit(")
            .substringBefore("private fun cameraPreviewReadyForGlPreviewCompositor()")

        assertTrue(modeBlock.contains("releaseRawPreviewMonitorForModeExit(previousMode, normalizedMode)"))
        assertTrue(releaseBlock.contains("previousMode != CinemaVideoMode.RAW"))
        assertTrue(releaseBlock.contains("currentMode == CinemaVideoMode.RAW"))
        assertTrue(releaseBlock.contains("glPreviewOwnerCooldownUntilElapsedMs = SystemClock.elapsedRealtime()"))
        assertTrue(releaseBlock.contains("previewGlCompositor.clearDebayerPresent()"))
        assertTrue(releaseBlock.contains("previewGlCompositor.release()"))
        assertTrue(releaseBlock.contains("glPreviewCompositorActive = false"))
        assertTrue(releaseBlock.contains("glPreviewSurfaceReleasePending = false"))
        assertTrue(releaseBlock.contains("previewViewRef?.setNativeCompositorActive(false)"))
        assertTrue(releaseBlock.contains("vulkanRuntime.setPreviewSurface(null)"))
        assertTrue(releaseBlock.contains("bindPreviewSurface("))
        assertTrue(releaseBlock.contains("preferNativePreviewOverride = false"))
        assertTrue(releaseBlock.contains("requestPreviewPipelineRefresh(forceSessionRefresh = true)"))
    }

    @Test
    fun `mode switch camera2 session refreshes are batched into one strongest rebuild`() {
        val queueBlock = cameraControllerSource
            .substringAfter("private fun queueBatchedSessionRefresh(")
            .substringBefore("fun refreshOutputsAndSession(")
        val refreshBlock = cameraControllerSource
            .substringAfter("fun refreshOutputsAndSession(")
            .substringBefore("private fun hdrSurfaceRoleFor(")
        val batchEndBlock = cameraControllerSource
            .substringAfter("fun endBatchUpdate()")
            .substringBefore("fun hasCameraFacing(")

        assertTrue(cameraControllerSource.contains("private val refreshBatchLock = Any()"))
        assertTrue(cameraControllerSource.contains("private var batchedSessionRefreshRequest"))
        assertTrue(refreshBlock.substringBefore("runOnCameraThread").contains("refreshBatchDepth.get() > 0"))
        assertTrue(refreshBlock.substringBefore("runOnCameraThread").contains("queueBatchedSessionRefresh("))
        assertTrue(queueBlock.contains("request.rebuildReaders = request.rebuildReaders || rebuildReaders"))
        assertTrue(queueBlock.contains("request.forceRebuild = request.forceRebuild || forceRebuild"))
        assertTrue(batchEndBlock.contains("batchedSessionRefreshRequest = null"))
        assertTrue(batchEndBlock.contains("rebuildReaders = request.rebuildReaders"))
        assertTrue(batchEndBlock.contains("forceRebuild = request.forceRebuild"))
        assertTrue(batchEndBlock.contains("Batched session refresh callback failed"))
    }

    @Test
    fun `session refresh trailing lambda remains the configured callback`() {
        val signature = cameraControllerSource
            .substringAfter("fun refreshOutputsAndSession(")
            .substringBefore(") {")

        assertTrue(signature.indexOf("onPreviousSessionClosed") < signature.indexOf("onConfigured"))
    }

    @Test
    fun `gl preview compositor waits until camera preview is open before taking surfaceview`() {
        val targetBlock = source
            .substringAfter("private fun applyGlPreviewCompositorTarget(")
            .substringBefore("/** Post ONE delayed re-attempt")
        val syncBlock = source
            .substringAfter("private fun syncGlPreviewCompositorBinding()")
            .substringBefore("private fun applyPreviewPipelineBinding(")
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")

        assertTrue(source.contains("private fun cameraPreviewReadyForGlPreviewCompositor()"))
        assertTrue(targetBlock.contains("!cameraPreviewReadyForGlPreviewCompositor()"))
        assertTrue(targetBlock.contains("camera.sessionRefreshPending.get()"))
        assertTrue(targetBlock.contains("scheduleGlPreviewEngageRetry()"))
        assertTrue(syncBlock.contains("!cameraPreviewReadyForGlPreviewCompositor()"))
        assertTrue(bindingBlock.contains("cameraPreviewReadyForGlPreviewCompositor()"))
        assertTrue(source.contains("cameraState.collect { state ->"))
        assertTrue(source.contains("syncGlPreviewCompositorBinding()"))
    }

    @Test
    fun `gl preview compositor handoff timeout restores direct preview instead of unsafe egl acquire`() {
        val fallbackBlock = source
            .substringAfter("private fun scheduleGlPreviewCompositorHandoffFallback(")
            .substringBefore("private fun scheduleNativePreviewRetry(")
        val bindingBlock = source
            .substringAfter("private fun bindPreviewSurface(")
            .substringBefore("internal fun refreshPreviewPipelineIfNeeded(")
        val refreshBlock = source
            .substringAfter("internal fun refreshPreviewPipelineIfNeeded(")
            .substringBefore("private fun preferredVideoSourceSize()")

        assertTrue(source.contains("private var glPreviewHandoffFallbackJob: Job? = null"))
        assertTrue(source.contains("GL_PREVIEW_CAMERA2_RELEASE_FALLBACK_MS"))
        assertTrue(source.contains("GL_PREVIEW_CAMERA2_RELEASE_SETTLE_MS + 1_500L"))
        assertTrue(fallbackBlock.contains("delay(GL_PREVIEW_CAMERA2_RELEASE_FALLBACK_MS)"))
        assertFalse(fallbackBlock.contains("completeGlPreviewCompositorHandoffAfterCameraRelease(surface)"))
        assertTrue(fallbackBlock.contains("keeping direct preview to avoid EGL already-connected freeze"))
        assertTrue(fallbackBlock.contains("camera.setPreviewSurface(surface)"))
        assertTrue(fallbackBlock.contains("glPreviewEngageRetries = GL_PREVIEW_MAX_ENGAGE_RETRIES"))
        assertTrue(bindingBlock.contains("scheduleGlPreviewCompositorHandoffFallback(surface)"))
        assertTrue(refreshBlock.contains("scheduleGlPreviewCompositorHandoffFallback(surface)"))
    }

    @Test
    fun `running gl preview compositor keeps camera2 bound to compositor input during rebuilds`() {
        val targetBlock = source
            .substringAfter("private fun applyGlPreviewCompositorTarget(")
            .substringBefore("/** Post ONE delayed re-attempt")
        val sdrTargetBlock = targetBlock
            .substringAfter("val grade = resolveGlPreviewGrade()")
            .substringBefore("val started = previewGlCompositor.start(")
        val activeBranchIndex = targetBlock.indexOf(
            "if (glPreviewCompositorActive && previewGlCompositor.isRunning())"
        )
        val sessionPendingIndex = sdrTargetBlock.indexOf("if (camera.sessionRefreshPending.get())")

        assertTrue(activeBranchIndex >= 0)
        assertTrue(sessionPendingIndex >= 0)
        assertTrue(
            "active compositor input must be restored before the session-pending fallback can run",
            sdrTargetBlock.indexOf(
                "if (glPreviewCompositorActive && previewGlCompositor.isRunning())"
            ) < sessionPendingIndex
        )
        assertTrue(sdrTargetBlock.contains("previewGlCompositor.inputSurface()?.let { input ->"))
        assertTrue(sdrTargetBlock.contains("camera.setPreviewSurface(input)"))
        assertTrue(sdrTargetBlock.contains("return true"))
        assertTrue(targetBlock.contains("hdr10PqPreviewCompositor.inputSurface()?.let { camera.setPreviewSurface(it) }"))
    }

    @Test
    fun `prearmed offscreen p010 graph freezes idle preview performance retunes`() {
        val freezeBlock = source
            .substringAfter("private fun shouldFreezePreviewPerformanceRetuneForPrearmedRecording(): Boolean =")
            .substringBefore("private fun evaluatePreviewPerformanceStability")
        val evaluationBlock = source
            .substringAfter("private fun evaluatePreviewPerformanceStability(measuredFps: Float)")
            .substringBefore("private fun clearPreviewSurface()")

        assertTrue(freezeBlock.contains("_uiMode.value == AppMode.VIDEO"))
        assertTrue(freezeBlock.contains("prearmedRecordingSurface != null"))
        assertTrue(freezeBlock.contains("prearmedRecordingSurfaceAttachedToSession"))
        assertTrue(freezeBlock.contains("prearmedRecordingFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER"))
        assertTrue(freezeBlock.contains("shouldKeepRecordingSurfacePrearmed()"))
        assertTrue(evaluationBlock.contains("!recordingActive && shouldFreezePreviewPerformanceRetuneForPrearmedRecording()"))
        assertTrue(evaluationBlock.contains("return"))
    }

    @Test
    fun `gl preview compositor clears stopped owner before reacquiring surfaceview`() {
        val targetBlock = source
            .substringAfter("private fun applyGlPreviewCompositorTarget(")
            .substringBefore("/** Post ONE delayed re-attempt")
        val syncBlock = source
            .substringAfter("private fun syncGlPreviewCompositorBinding()")
            .substringBefore("private fun applyPreviewPipelineBinding(")
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val readyBlock = source
            .substringAfter("private fun visibleGlPreviewSurfaceReadyForNewEglAcquire(")
            .substringBefore("/**\n     * Chooses the visible-preview camera target.")

        assertTrue(source.contains("private fun clearStoppedVisibleGlPreviewCompositorOwnership()"))
        assertTrue(targetBlock.contains("clearStoppedVisibleGlPreviewCompositorOwnership()"))
        assertTrue(syncBlock.contains("clearStoppedVisibleGlPreviewCompositorOwnership()"))
        assertTrue(bindingBlock.contains("clearStoppedVisibleGlPreviewCompositorOwnership()"))
        assertTrue(targetBlock.contains("visibleGlPreviewSurfaceReadyForNewEglAcquire(visibleSurface)"))
        assertTrue(readyBlock.contains("camera.sessionRefreshPending.get()"))
        assertTrue(readyBlock.contains("!glPreviewVisibleSurfaceReleaseConfirmed"))
        assertTrue(readyBlock.contains("cameraOwnsVisiblePreviewSurface(visibleSurface)"))
    }

    @Test
    fun `native preview import deadline waits until camera2 is streaming`() {
        val watchdogBlock = source
            .substringAfter("private fun startNativePreviewWatchdog(")
            .substringBefore("private fun applyPreviewPipelineBinding(")

        assertTrue(watchdogBlock.contains("currentCameraState != CameraState.Streaming"))
        assertTrue(watchdogBlock.contains("currentCameraState != CameraState.Recording"))
        assertTrue(watchdogBlock.contains("nativePreviewActivationElapsedMs = SystemClock.elapsedRealtime()"))
    }

    @Test
    fun `native compositor surface geometry settles before vulkan binds it`() {
        val bindingBlock = source
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val surfaceReadyBlock = source
            .substringAfter("fun onSurfaceReady(surface: Surface?)")
            .substringBefore("/** Alternative to onSurfaceReady")
        val modeTransition = "previewViewRef?.setNativeCompositorActive(true) == true"

        assertTrue(bindingBlock.contains(modeTransition))
        assertTrue(bindingBlock.indexOf(modeTransition) < bindingBlock.indexOf("val nativeBound"))
        assertTrue(bindingBlock.contains("nativePreviewSurfaceResizePending = true"))
        assertTrue(surfaceReadyBlock.contains("nativePreviewSurfaceResizePending"))
        assertTrue(
            surfaceReadyBlock.indexOf("nativePreviewSurfaceResizePending") <
                surfaceReadyBlock.indexOf("previewSurface === surface")
        )
        assertFalse(bindingBlock.contains("scheduleNativePreviewBindAfterSurfaceResize(surface)"))
    }

    private fun File.readNormalizedText(): String = readText().replace("\r\n", "\n")
}
