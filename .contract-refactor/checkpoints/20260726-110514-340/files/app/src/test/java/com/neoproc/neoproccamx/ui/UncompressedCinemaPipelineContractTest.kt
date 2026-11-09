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
        val controller = File("src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsController.kt").readText()
        val settings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(preferences.contains("KEY_UNCOMPRESSED_CINEMA_PIPELINE"))
        assertTrue(preferences.contains("uncompressedCinemaPipelineEnabled"))
        assertTrue(preferences.contains("enforceHiddenCaptureToolsOff()"))
        assertTrue(preferences.contains("putBoolean(KEY_UNCOMPRESSED_CINEMA_PIPELINE, true)"))
        assertTrue(viewModel.contains("uncompressedCinemaPipelineEnabled"))
        assertTrue(viewModel.contains("setUncompressedCinemaPipelineEnabled"))
        assertTrue(viewModel.contains("Strict processed recording uses the custom lossless packed-10 stream"))
        assertTrue(viewModel.contains("_cinemaVideoMode.value != CinemaVideoMode.RAW"))
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

        assertTrue(viewModel.contains("publishConvertedNpv10MovToAlbum"))
        assertFalse(viewModel.contains("getExternalFilesDir(\"Converted\")"))
    }

    @Test
    fun temporaryMovCertificateIsRemovedAfterManualAlbumPublication() {
        val converter = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

        assertTrue(converter.contains("fun certificateSidecarFile(output: File): File"))
        assertTrue(converter.contains("fun focusMapSidecarFile(output: File): File"))
        assertTrue(viewModel.contains("Npv10MovConverter.certificateSidecarFile(tempOutput).delete()"))
        assertTrue(viewModel.contains("Npv10MovConverter.focusMapSidecarFile(tempOutput).delete()"))
    }

    @Test
    fun npv10ManualConversionDeletesPrivateMasterOnlyAfterPublish() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

        assertTrue(viewModel.contains("convertNpv10MasterToMovAndDeleteSource"))
        assertTrue(viewModel.contains("publishConvertedNpv10MovToAlbum(tempOutput, clip.file)"))
        assertTrue(viewModel.contains("deleteConvertedNpv10SourceMaster(clip.file)"))
        assertTrue(viewModel.contains("sourceMaster.delete()"))
        assertFalse(viewModel.contains("scheduleNpv10MasterConversion"))
    }

    @Test
    fun npv10StopEndsRecordingNotificationAndDoesNotStartConversion() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val service = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingForegroundService.kt").readText()
        val stopBody = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private fun buildNpv10ColorProfileLabel")
        val stopIndex = stopBody.indexOf("stopRecordingForegroundService()")
        val convertIndex = stopBody.indexOf("convertNpv10MasterToMovAndDeleteSource(")

        assertTrue(stopIndex >= 0)
        assertTrue(convertIndex < 0)
        assertTrue(service.contains("return START_NOT_STICKY"))
        assertFalse(service.contains("return START_STICKY"))
        assertTrue(service.contains("fun stop(context: Context)"))
        assertTrue(service.contains("cancel(NOTIFICATION_ID)"))
    }

    @Test
    fun npv10CaptureStopsBeforeWriterDrainAndPreservesMasterForManualConversion() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val captureStopBody = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private fun buildNpv10ColorProfileLabel")

        assertTrue(captureStopBody.indexOf("camera.stopVideoCapture()") < captureStopBody.indexOf("npv10Recording.stop()"))
        assertFalse(captureStopBody.contains("scheduleNpv10MasterConversion("))
        assertFalse(captureStopBody.contains("convertNpv10MasterToMovAndDeleteSource("))
    }

    @Test
    fun npv10ShutterAndPreviewReturnBeforeLosslessBackgroundFinalization() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val manager = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()
        val stopBody = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")
        val managerStopBody = manager
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")

        assertTrue(stopBody.indexOf("npv10Recording.beginFinalization()") < stopBody.indexOf("npv10Recording.stop()"))
        assertTrue(stopBody.indexOf("recordingTransitionState.value = GpuRecordingState.Idle") < stopBody.indexOf("npv10Recording.stop()"))
        assertTrue(stopBody.indexOf("restorePreviewAfterNpv10CaptureStop()") < stopBody.indexOf("npv10Recording.stop()"))
        assertFalse(stopBody.contains("recordingTransitionState.value = GpuRecordingState.Finalizing"))
        assertTrue(manager.contains("fun hasActiveSession(): Boolean = writer != null"))
        assertTrue(manager.contains("Previous NPV10 lossless master is still finalizing"))
        assertTrue(managerStopBody.contains("closeTask.get()"))
        assertFalse(managerStopBody.contains("closeTask.get(CLOSE_TIMEOUT_SECONDS"))
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
        val stopBody = viewModel.substringAfter("fun stopRecording()").substringBefore("private fun rebindVisiblePreviewTarget")

        assertTrue(viewModel.contains("recordingTransitionState.value = GpuRecordingState.Starting"))
        assertTrue(stopBody.contains("if (recordingStartInProgress.get())"))
        assertTrue(stopBody.contains("recordingStartJob?.cancel"))
        assertTrue(stopBody.contains("recordingStopInProgress.compareAndSet(false, true)"))
    }

    @Test
    fun npv10StartCleanupStopsRecordingNotification() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val startBody = viewModel
            .substringAfter("private suspend fun startNpv10RecordingSession")
            .substringBefore("private fun requestNpv10P010SessionRefresh")
        val noFrameCleanup = startBody
            .substringAfter("if (!awaitNpv10FirstWrittenFrame())")
            .substringBefore("return false")
        val exceptionCleanup = startBody
            .substringAfter("} catch (t: Throwable) {")
            .substringBefore("handleCameraError")

        assertTrue(noFrameCleanup.contains("stopRecordingForegroundService()"))
        assertTrue(exceptionCleanup.contains("stopRecordingForegroundService()"))
    }

    @Test
    fun npv10ProcessedRouteKeepsSeparateRawSelectionAndSettings() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val settings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(viewModel.contains("mode == CinemaVideoMode.LOG && isNpv10LiveLogBlocked()"))
        assertFalse(viewModel.contains("enabled && isNpv10RawModeBlocked()"))
        assertTrue(viewModel.contains("_cinemaVideoMode.value == CinemaVideoMode.LOG"))
        assertTrue(settings.contains("Use the normal processed pipeline or SENSOR RAW"))
        assertFalse(settings.contains("DISABLED IN NPV10"))
        assertFalse(settings.contains("SENSOR RAW is disabled"))
    }

    @Test
    fun npv10MastersRemainListedForManualLaneSpecificConversion() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val settings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").readText()

        assertTrue(viewModel.contains("fun listNpv10MastersForConversion("))
        assertTrue(viewModel.contains("Npv10MasterLanePolicy.resolve(it.file) == kind"))
        assertTrue(settings.contains("viewModel.listNpv10MastersForConversion(kind)"))
        assertFalse(viewModel.contains("convertPendingNpv10MastersOnIdle"))
        assertFalse(viewModel.contains("scheduleNpv10MasterConversion"))
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
        val controller = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val rawVideoManager = File("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt").readText()
        val sourcePolicy = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10CaptureSourcePolicy.kt").readText()

        val packetCodec = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10PacketCodec.kt").readText()

        assertTrue(viewModel.contains("private fun shouldUseUncompressedCinemaPipeline(): Boolean"))
        assertTrue(viewModel.contains("_cinemaVideoMode.value != CinemaVideoMode.RAW"))
        assertTrue(viewModel.contains("shouldUseCustomRawRecordingPipeline"))
        assertTrue(viewModel.contains("Dropping mismatched NPV10 P010 frame"))
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

        assertTrue(viewModel.contains("camera.startVideoCapture()"))
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
            assertFalse("${file.name} must not import MediaCodec", source.contains("android.media.MediaCodec"))
            assertFalse("${file.name} must not call MediaCodec", source.contains("MediaCodec."))
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
