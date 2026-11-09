package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UncompressedCinemaPipelineContractTest {

    @Test
    fun customPacked10PipelineRemainsHiddenFromSettingsAndIsMandatory() {
        val preferences = File("src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt").readText()
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val routePolicy = File("src/main/java/com/neoproc/neoproccamx/ui/Npv10RecordingRoutePolicy.kt").readText()
        val controller = File("src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsController.kt").readText()
        val settings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(preferences.contains("KEY_UNCOMPRESSED_CINEMA_PIPELINE"))
        assertTrue(preferences.contains("uncompressedCinemaPipelineEnabled"))
        assertTrue(preferences.contains("enforceHiddenCaptureToolsOff()"))
        assertTrue(preferences.contains("putBoolean(KEY_UNCOMPRESSED_CINEMA_PIPELINE, true)"))
        assertTrue(viewModel.contains("uncompressedCinemaPipelineEnabled"))
        assertTrue(viewModel.contains("setUncompressedCinemaPipelineEnabled"))
        assertTrue(viewModel.contains("Npv10RecordingRoutePolicy.shouldUseCamera2P010Master("))
        assertTrue(routePolicy.contains("cinemaMode != CinemaVideoMode.RAW"))
        assertTrue(routePolicy.contains("!SensorLinearNdrModePolicy.isActive("))
        assertTrue(controller.contains("it.copy(uncompressedCinemaPipeline = true)"))
        assertFalse(settings.contains("Uncompressed Cinema Pipeline"))
    }

    @Test
    fun npv10RecordingManagerKeepsMastersPrivateAndDoesNotPublishGalleryProxy() {
        val manager = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()

        assertTrue(manager.contains("filesDir"))
        assertFalse(manager.contains("cacheDir"))
        assertFalse(manager.contains("MediaStore"))
        assertFalse(manager.contains("DIRECTORY_MOVIES"))
        assertFalse(manager.contains(".mp4"))
        assertFalse(manager.contains(".mov"))
    }

    @Test
    fun convertedMovPublishesThroughMediaStoreButNpv10MasterDoesNot() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val conversionCoordinator = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraMediaConversionCoordinator.kt"
        ).readText()
        val conversionSource = viewModel + conversionCoordinator

        assertTrue(conversionSource.contains("publishConvertedNpv10MovToAlbum"))
        assertFalse(conversionSource.contains("getExternalFilesDir(\"Converted\")"))
    }

    @Test
    fun temporaryMovCertificateIsRemovedAfterManualAlbumPublication() {
        val converter = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val conversionCoordinator = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraMediaConversionCoordinator.kt"
        ).readText()

        assertTrue(converter.contains("fun certificateSidecarFile(output: File): File"))
        assertTrue(converter.contains("fun focusMapSidecarFile(output: File): File"))
        assertTrue(conversionCoordinator.contains("Npv10MovConverter.certificateSidecarFile(tempOutput).delete()"))
        assertTrue(conversionCoordinator.contains("Npv10MovConverter.focusMapSidecarFile(tempOutput).delete()"))
    }

    @Test
    fun npv10ManualConversionKeepsPrivateMasterForAdditionalDeliveryFormats() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val conversionCoordinator = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraMediaConversionCoordinator.kt"
        ).readText()
        val conversionSource = viewModel + conversionCoordinator

        assertTrue(conversionSource.contains("convertNpv10MasterToMovAndPublish"))
        assertTrue(conversionSource.contains("publishConvertedNpv10MovToAlbum(tempOutput, clip.file)"))
        assertFalse(conversionSource.contains("deleteConvertedNpv10SourceMaster(clip.file)"))
        assertFalse(conversionSource.contains("scheduleNpv10MasterConversion"))
    }

    @Test
    fun npv10StopEndsRecordingNotificationAndDoesNotStartConversion() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val service = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingForegroundService.kt").readText()
        val stopBody = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private fun buildNpv10ColorProfileLabel")
        val stopIndex = stopBody.indexOf("stopRecordingForegroundService()")
        val convertIndex = stopBody.indexOf("convertNpv10MasterToMovAndPublish(")

        assertTrue(stopIndex >= 0)
        assertTrue(convertIndex < 0)
        assertTrue(service.contains("return START_NOT_STICKY"))
        assertFalse(service.contains("return START_STICKY"))
        assertTrue(service.contains("fun stop(context: Context)"))
        assertTrue(service.contains("cancel(NOTIFICATION_ID)"))
    }

    @Test
    fun npv10AsyncWriterErrorUsesTheSerializedFullLifecycleStopOwner() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val observer = File(
            "src/main/java/com/neoproc/neoproccamx/ui/RecordingTerminalFailureCleanupObserver.kt"
        ).readText()
            .substringAfter("private fun startNpv10TerminalErrorCleanupObserver()")
            .substringBefore("private fun startEncodedTerminalErrorCleanupObserver()")
        val lifecycle = File(
            "src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt"
        ).readText()
        val stop = lifecycle
            .substringAfter("fun requestStop()")
            .substringBefore("fun releaseActiveStopOwnerWithoutBackend()")
        val npv10Stop = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")

        assertTrue(viewModel.contains("RecordingTerminalFailureCleanupObserver("))
        assertTrue(viewModel.contains("::setHardwareWarning, ::requestRecordingStop"))
        assertTrue(observer.contains("npv10Recording.state.collect"))
        assertTrue(observer.contains("state !is GpuRecordingState.Error"))
        assertTrue(observer.contains("npv10Recording.hasActiveSession()"))
        assertTrue(observer.contains("requestRecordingStop("))
        assertTrue(observer.contains("origin = RecordingStopOrigin.NPV10_WRITER_FAILURE"))
        val stopRequest = viewModel
            .substringAfter("private fun requestRecordingStop(origin: RecordingStopOrigin, detail: String)")
            .substringBefore("private fun recordRecordingStopOrigin(")
        assertTrue(stopRequest.contains("recordingStopRestartLifecycle.requestStop()"))
        assertTrue(stop.contains("stopInProgress.compareAndSet(false, true)"))
        assertTrue(npv10Stop.contains("pipeline.setRawYuvImageSink(null)"))
        assertTrue(npv10Stop.contains("camera.stopNpv10P010CaptureAtExactBoundary("))
        assertTrue(npv10Stop.contains("poisonRecorder = { message ->"))
        assertTrue(npv10Stop.contains("npv10Recording.awaitAcceptedFrameDrain()"))
        assertTrue(npv10Stop.contains("stopRecordingForegroundService()"))
        assertTrue(npv10Stop.contains("restorePreviewAfterNpv10CaptureStop()"))
        assertTrue(npv10Stop.contains("npv10Recording.stop()"))
        assertTrue(npv10Stop.contains("npv10Recording.lastStopPassedDurabilityAudit()"))
        assertTrue(npv10Stop.contains("failed master preserved privately and not announced as saved"))
    }

    @Test
    fun npv10StrictInputContractRejectsInvalidFpsAndMismatchedFrames() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val startupIngress = File(
            "src/main/java/com/neoproc/neoproccamx/ui/Npv10RecordingStartupIngress.kt"
        ).readText()
        val manager = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).readText()
        val start = viewModel
            .substringAfter("private suspend fun startNpv10RecordingSession")
            .substringBefore("private suspend fun awaitNpv10P010SessionReady")

        assertTrue(start.contains("require(fps > 0)"))
        assertFalse(start.contains("recording.fps.coerceAtLeast(1)"))
        assertTrue(startupIngress.contains("NPV10 input contract violation"))
        assertTrue(startupIngress.contains("recorder.failInputContractViolation(message)"))
        assertFalse(startupIngress.contains("Dropping mismatched NPV10 P010 frame"))
        assertTrue(manager.contains("fun failInputContractViolation(message: String): Boolean"))
        assertTrue(manager.contains("droppedFrames.incrementAndGet()"))
    }

    @Test
    fun npv10ProducerIsProvenBeforeWriterLineageAndSinkOwnTheTimeline() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val start = viewModel
            .substringAfter("private suspend fun startNpv10RecordingSession")
            .substringBefore("private suspend fun awaitNpv10P010SessionReady")

        val foregroundStart = start.indexOf("startRecordingForegroundService()")
        val audioPrepare = start.indexOf("npv10AudioRecording.prepare(recording.audioSource)")
        val cameraStart = start.indexOf("camera.startNpv10VideoCaptureWithProducerLease()")
        val producerProof = start.indexOf("awaitNpv10P010ProducerReady(", cameraStart)
        val cadenceStabilization = start.indexOf("stabilizeNpv10ProducerBeforeWriter(", producerProof)
        val writerStart = start.indexOf("npv10Recording.start(config)")
        val lineageStart = start.indexOf("beginCamera2FrameLineageSession(")
        val sinkAttach = start.indexOf("pipeline.setRawYuvImageSinkAtExactIngressBoundary(sink = startupIngress::onImage)")
        val firstFrameProof = start.indexOf("awaitNpv10FirstWrittenFrame(")
        val producerLeaseRelease =
            start.indexOf("producerStartupLease.commitAfterCadenceStabilization()", firstFrameProof)
        val audioTimelineStart = start.indexOf("npv10AudioRecording.start(", firstFrameProof)

        assertTrue(foregroundStart >= 0)
        assertTrue(audioPrepare > foregroundStart)
        assertTrue(cameraStart > audioPrepare)
        assertTrue(cameraStart >= 0)
        assertTrue(producerProof > cameraStart)
        assertTrue(cadenceStabilization > producerProof)
        assertTrue(writerStart > cadenceStabilization)
        assertTrue(lineageStart > writerStart)
        assertTrue(sinkAttach > lineageStart)
        assertTrue(firstFrameProof > sinkAttach)
        assertTrue(audioTimelineStart > firstFrameProof)
        assertTrue(producerLeaseRelease > firstFrameProof)
        assertTrue(producerLeaseRelease > audioTimelineStart)
        assertTrue(start.indexOf("startedAtEpochMs = System.currentTimeMillis()") > producerProof)
        assertTrue(start.contains("cadenceAuthority = stabilizedProducer.cadence"))
        assertTrue(start.contains("startupReplayCount = stabilizedProducer.replayCount"))
    }

    @Test
    fun npv10CaptureStopsBeforeWriterDrainAndPreservesMasterForManualConversion() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val captureStopBody = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private fun buildNpv10ColorProfileLabel")

        assertTrue(captureStopBody.indexOf("camera.stopVideoCapture()") < captureStopBody.indexOf("npv10Recording.stop()"))
        assertTrue(
            captureStopBody.indexOf("npv10Recording.awaitAcceptedFrameDrain()") <
                captureStopBody.indexOf("restorePreviewAfterNpv10CaptureStop()")
        )
        assertTrue(
            captureStopBody.indexOf("restorePreviewAfterNpv10CaptureStop()") <
                captureStopBody.indexOf("npv10Recording.stop()")
        )
        assertFalse(captureStopBody.contains("scheduleNpv10MasterConversion("))
        assertFalse(captureStopBody.contains("convertNpv10MasterToMovAndPublish("))
    }

    @Test
    fun npv10PreviewRestoresWhileTransitionRemainsFinalizingUntilOwnerRelease() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val manager = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()
        val stopBody = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")
        val managerStopBody = manager
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")

        assertTrue(stopBody.indexOf("npv10Recording.beginFinalization()") < stopBody.indexOf("npv10Recording.stop()"))
        assertTrue(stopBody.indexOf("restorePreviewAfterNpv10CaptureStop()") < stopBody.indexOf("npv10Recording.stop()"))
        assertTrue(
            stopBody.indexOf("recordingTransitionState.value = GpuRecordingState.Finalizing") <
                stopBody.indexOf("npv10Recording.stop()")
        )
        assertFalse(stopBody.contains("recordingTransitionState.value = GpuRecordingState.Idle"))
        assertTrue(manager.contains("_state.value = GpuRecordingState.Finalizing"))
        assertTrue(
            managerStopBody.lastIndexOf("_state.value = GpuRecordingState.Idle") >
                managerStopBody.indexOf("val qualityCertificatePath")
        )
        assertTrue(
            managerStopBody.lastIndexOf("finalizationInProgress = false") >
                managerStopBody.indexOf("val qualityCertificatePath")
        )
        assertTrue(manager.contains("fun hasActiveSession(): Boolean = writer != null"))
        assertTrue(manager.contains("Previous NPV10 lossless master is still finalizing"))
        assertTrue(managerStopBody.contains("awaitFutureUninterruptibly(closeTask)"))
        assertFalse(managerStopBody.contains("closeTask.get(CLOSE_TIMEOUT_SECONDS"))
    }

    @Test
    fun npv10UnchangedPrearmedGraphRestartsRepeatingWithoutRebuildingTheSession() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val restore = viewModel
            .substringAfter("private suspend fun restorePreviewAfterNpv10CaptureStop()")
            .substringBefore("private fun buildNpv10ColorProfileLabel")
        val pipelineRefresh = restore.indexOf(
            "refreshPreviewPipelineIfNeeded(forceSessionRefresh = !keepNpv10Prearmed)"
        )
        val restartWindow = restore
            .substringAfter("refreshPreviewPipelineIfNeeded(forceSessionRefresh = !keepNpv10Prearmed)")
            .substringBefore("syncCameraPreviewLookControls()")
        val keepPrearmedGuard = restartWindow.indexOf("if (keepNpv10Prearmed) {")
        val repeatingRestart = restartWindow.indexOf("camera.refreshRepeatingRequest()")

        assertTrue(pipelineRefresh >= 0)
        assertTrue(keepPrearmedGuard >= 0)
        assertTrue(repeatingRestart > keepPrearmedGuard)
        assertTrue(Regex("camera\\.refreshRepeatingRequest\\(\\)").findAll(restore).count() == 1)
        assertFalse(restore.contains("camera.refreshOutputsAndSession("))
    }

    @Test
    fun npv10StopEmitsSeparatedDurableFinalizationTimings() {
        val manager = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()
        val writer = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt").readText()

        listOf(
            "queueDrainMs",
            "truncateMs",
            "durableSyncMs",
            "evidenceBuildMs",
            "evidenceWriteMs",
            "certificateMs"
        ).forEach { stage -> assertTrue("Missing NPV10 stop stage $stage", manager.contains(stage)) }
        assertTrue(writer.contains("Npv10FileFinalizationTimings"))
        assertTrue(writer.contains("channel.truncate(committedBytes)"))
        assertTrue(writer.contains("channel.force(true)"))
    }

    @Test
    fun rapidSecondShutterPressCancelsStartupAndDuplicateStopsAreSerialized() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val lifecycle = File(
            "src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt"
        ).readText()
        val stopBody = lifecycle.substringAfter("fun requestStop()").substringBefore("fun releaseActiveStopOwnerWithoutBackend()")

        assertTrue(viewModel.contains("recordingTransitionState.value = GpuRecordingState.Starting"))
        assertTrue(stopBody.contains("startupHandoff.requestStopDuringStart"))
        assertTrue(viewModel.contains("recordingStopRestartLifecycle.registerStartupCancellationAction"))
        assertTrue(viewModel.contains("recordingStopRestartLifecycle.completeStart"))
        assertTrue(lifecycle.contains("handoffCompletion.backendStopReserved"))
        assertTrue(stopBody.contains("stopInProgress.compareAndSet(false, true)"))
    }

    @Test
    fun admittedRetryClearsInactiveEncoderErrorBeforeAlternateBackendStarts() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val startBody = viewModel
            .substringAfter("private fun startRecordingInternal()")
            .substringBefore("private fun pauseRecordingInternal()")
        val manualAdmissionIndex = startBody.indexOf("ensureManualVideoColorSelectionReadyForRecording()")
        val staleErrorClearIndex = startBody.indexOf("Cleared stale recording error before admitted retry")
        val npv10StartIndex = startBody.indexOf("startNpv10RecordingSession(recordingSize)")

        assertTrue(manualAdmissionIndex >= 0)
        assertTrue(staleErrorClearIndex > manualAdmissionIndex)
        assertTrue(npv10StartIndex > staleErrorClearIndex)
        assertTrue(startBody.contains("gpuRecording.state.value is GpuRecordingState.Error"))
        assertTrue(startBody.contains("gpuRecording.discardPreparedSession()"))
    }

    @Test
    fun npv10StartCleanupStopsRecordingNotification() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val startBody = viewModel
            .substringAfter("private suspend fun startNpv10RecordingSession")
            .substringBefore("private fun requestNpv10P010SessionRefresh")
        val noFrameCleanup = startBody
            .substringAfter("if (!awaitNpv10FirstWrittenFrame(")
            .substringBefore("return false")
        val exceptionCleanup = startBody
            .substringAfter("} catch (t: Throwable) {")
            .substringBefore("handleCameraError")
        val abortCleanup = startBody
            .substringAfter("private fun abortNpv10Startup(")
            .substringBefore("private suspend fun awaitNpv10P010SessionReady(")

        assertTrue(noFrameCleanup.contains("abortNpv10Startup("))
        assertTrue(exceptionCleanup.contains("abortNpv10Startup("))
        assertTrue(abortCleanup.contains("stopRecordingForegroundService()"))
    }

    @Test
    fun npv10ProcessedRouteKeepsSeparateRawSelectionAndSettings() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val settings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(viewModel.contains("normalizeNpv10CinemaMode(mode: CinemaVideoMode): CinemaVideoMode = mode"))
        assertFalse(viewModel.contains("mode == CinemaVideoMode.LOG && isNpv10LiveLogBlocked()"))
        assertFalse(viewModel.contains("enabled && isNpv10RawModeBlocked()"))
        assertTrue(viewModel.contains("_cinemaVideoMode.value == CinemaVideoMode.LOG"))
        assertTrue(settings.contains("Use the normal processed pipeline or SENSOR RAW"))
        assertFalse(settings.contains("DISABLED IN NPV10"))
        assertFalse(settings.contains("SENSOR RAW is disabled"))
    }

    @Test
    fun npv10MastersRemainListedForManualLaneSpecificConversion() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val conversionCoordinator = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraMediaConversionCoordinator.kt"
        ).readText()
        val conversionSource = viewModel + conversionCoordinator
        val settings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(viewModel.contains("fun listNpv10MastersForConversion("))
        assertTrue(conversionSource.contains("Npv10MasterLanePolicy.resolve(it.file) == kind"))
        assertTrue(settings.contains("viewModel.listNpv10MastersForConversion(kind)"))
        assertFalse(conversionSource.contains("convertPendingNpv10MastersOnIdle"))
        assertFalse(conversionSource.contains("scheduleNpv10MasterConversion"))
    }

    @Test
    fun npv10StartupDoesNotConsumePrivateMastersInBackground() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val restoreBody = viewModel
            .substringAfter("restoreVideoFeatures(persist = false)")
            .substringBefore("syncCameraPreviewLookControls()")

        assertFalse(restoreBody.contains("if (_uncompressedCinemaPipeline.value)"))
        assertFalse(restoreBody.contains("convertPendingNpv10MastersOnIdle()"))
    }

    @Test
    fun customNpv10CaptureIsPackedNativeP010OnlyAndRawUsesSeparatePacketRoute() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val startupIngress = File(
            "src/main/java/com/neoproc/neoproccamx/ui/Npv10RecordingStartupIngress.kt"
        ).readText()
        val controller = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val rawVideoManager = File("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt").readText()
        val sourcePolicy = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10CaptureSourcePolicy.kt").readText()

        val packetCodec = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10PacketCodec.kt").readText()

        assertTrue(viewModel.contains("private fun shouldUseUncompressedCinemaPipeline(): Boolean"))
        assertTrue(viewModel.contains("Npv10RecordingRoutePolicy.shouldUseCamera2P010Master("))
        assertTrue(viewModel.contains("hdrMode = _videoHdrMode.value"))
        assertTrue(viewModel.contains("shouldUseCustomRawRecordingPipeline"))
        assertTrue(startupIngress.contains("NPV10 input contract violation"))
        assertTrue(startupIngress.contains("recorder.failInputContractViolation(message)"))
        assertTrue(controller.contains("Ignoring analysis target retune during NPV10 recording"))
        assertTrue(sourcePolicy.contains("custom lossless packed 10-bit P010 data only"))
        assertTrue(packetCodec.contains("PACKED_P010_10_LE_V1"))
        assertTrue(packetCodec.contains("No RGB"))
        assertFalse(viewModel.contains("setNpv10RawFrameSink"))
        assertFalse(rawVideoManager.contains("npv10RawFrameSink"))
        assertFalse(sourcePolicy.contains("RAW12_NPV10_PACKED"))
        assertFalse(sourcePolicy.contains("RAW14_NPV10_PACKED"))
    }

    @Test
    fun npv10RecordingUsesCameraRecordingStateSoAnalysisFramesReachWriter() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

        assertTrue(viewModel.contains("camera.startNpv10VideoCaptureWithProducerLease()"))
        assertTrue(viewModel.contains("camera.stopVideoCapture()"))
    }

    @Test
    fun customProcessedAndRawRecordersDoNotUseMediaCodec() {
        val customFiles = listOf(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10PacketCodec.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10FileReader.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt",
            "src/main/java/com/neoproc/neoproccamx/pipeline/NprawFrameCodec.kt",
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/RawToNeoLogMovConverter.kt"
        ).map(::File)

        customFiles.forEach { file ->
            val source = file.readText()
            val executableSource = source
                .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("(?m)//.*$"), "")
            assertFalse(
                "${file.name} must not import MediaCodec",
                Regex("(?m)^\\s*import\\s+android\\.media\\.MediaCodec(?:\\s|$)")
                    .containsMatchIn(executableSource)
            )
            assertFalse(
                "${file.name} must not call MediaCodec",
                Regex("\\bMediaCodec\\s*\\.").containsMatchIn(executableSource)
            )
        }
    }

    @Test
    fun npv10RecordingAppliesNormalVideoFeatureParityBeforeCapture() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val parityBody = viewModel
            .substringAfter("private fun applyNpv10RecordingFeatureParity")
            .substringBefore("private fun buildNpv10FeatureMetadata")

        assertTrue(viewModel.contains("applyNpv10RecordingFeatureParity"))
        assertTrue(viewModel.contains("syncEffectiveRecordingColorProfile()"))
        assertTrue(viewModel.contains("applyRecordingCameraLookControls()"))
        assertFalse(parityBody.contains("applyPreRecordingPerformanceCaps("))
        assertTrue(viewModel.contains("camera.setNoiseReductionEnabled(effectiveNoiseReductionEnabled())"))
        assertTrue(viewModel.contains("camera.setStabilizationMode(stabilizationMode)"))
        assertTrue(viewModel.contains("applyAiTrackingPreferences()"))
        assertTrue(viewModel.contains("buildNpv10FeatureMetadata"))
        assertTrue(viewModel.contains("featureMetadata ="))
    }

    @Test
    fun mandatoryNpv10SetterIsIdempotentAndCannotRebuildAnAlreadyEnabledGraph() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val setterBody = viewModel
            .substringAfter("fun setUncompressedCinemaPipelineEnabled")
            .substringBefore("fun setGridMode")

        assertTrue(setterBody.contains("if (_uncompressedCinemaPipeline.value) return"))
        assertTrue(setterBody.indexOf("if (_uncompressedCinemaPipeline.value) return") < setterBody.indexOf("refreshPreviewPipelineIfNeeded"))
    }

    @Test
    fun npv10MasterMetadataDoesNotCopyPreviewOnlySubjectDetailAsAppliedPixels() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

        assertTrue(viewModel.contains("VideoDetailSharpeningPolicy.npv10MasterEvidenceMetadata("))
    }
}
