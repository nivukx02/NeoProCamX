package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingByteBufferVideoInputContractTest {
    private val managerSource =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()
    private val framePipelineSource =
        File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").readText()
    private val viewModelSource =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val packerSource =
        File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt").readText()
    private val formatPolicySource =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt").readText()

    @Test
    fun managerHasFirstClassP010ByteBufferVideoInputMode() {
        assertTrue(managerSource.contains("enum class RecordingVideoInputMode"))
        assertTrue(managerSource.contains("BYTEBUFFER_P010"))
        assertTrue(managerSource.contains("fun setVideoInputMode(mode: RecordingVideoInputMode)"))
        assertTrue(managerSource.contains("fun queueExternalVideoFrame(frame: RawYuvFrame): Boolean"))
        assertTrue(managerSource.contains("fun queueExternalVideoImage(image: Image): Boolean"))
        assertTrue(managerSource.contains("Npv10FramePacker.packIntoDirectP010"))
        assertTrue(managerSource.contains("Npv10FramePacker.copyP010ImageIntoDirectBuffer"))
        assertTrue(managerSource.contains("codec.queueInputBuffer(inputIndex, 0, payloadBytes, ptsUs, 0)"))
        assertTrue(managerSource.contains("videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010"))
    }

    @Test
    fun byteBufferP010RecordingUsesOwnedImageSinkInsteadOfCameraThreadFramePacking() {
        assertTrue(framePipelineSource.contains("fun interface RawYuvImageSink"))
        assertTrue(framePipelineSource.contains("fun setRawYuvImageSink(sink: RawYuvImageSink?)"))
        assertTrue(viewModelSource.contains("pipeline.setRawYuvImageSink { image, _ ->"))
        assertTrue(viewModelSource.contains("gpuRecording.queueExternalVideoImage(image)"))
        assertTrue(packerSource.contains("fun copyP010ImageIntoDirectBuffer"))
        assertTrue(packerSource.contains("nativeCopyP010ImagePlanes"))
        assertFalse(viewModelSource.contains("pipeline.setRawYuvFrameSink { frame ->\n                        gpuRecording.queueExternalVideoFrame(frame)"))
    }

    @Test
    fun byteBufferP010ImageHandoffIsBoundedAndDoesNotBlockCameraCallbacks() {
        assertTrue(framePipelineSource.contains("reader.acquireNextImage()"))
        assertTrue(framePipelineSource.contains("rawYuvImageSinkTelemetry()"))
        assertTrue(managerSource.contains("resolveP010ImageInFlightCapacity"))
        assertTrue(managerSource.contains("P010_TRANSIENT_STALL_BUDGET_US"))
        assertTrue(managerSource.contains("P010_MAX_IN_FLIGHT_IMAGES"))
        assertTrue(managerSource.contains("maxInFlightProvider ="))
        assertTrue(managerSource.contains("tryAcceptCallback(sourceTimestampNs)"))
        assertTrue(managerSource.contains("image_backpressure_capacity"))
        assertTrue(managerSource.contains("runCatching { image.close() }"))
        assertFalse(managerSource.contains("byteBufferPendingImageInputs.incrementAndGet()"))
    }

    @Test
    fun realtimeP010MuxerQueueAbsorbsStartupAndStorageBurstsBeforeDroppingReferences() {
        val muxerQueueSource =
            File("src/main/java/com/neoproc/neoproccamx/recording/RecordingMuxerSampleQueue.kt")
                .readText()

        assertTrue(managerSource.contains("private const val MAX_PENDING_MUXER_SAMPLES = 4096"))
        assertTrue(managerSource.contains("private const val STRICT_MUXER_PREWARM_TARGET_BYTES"))
        assertTrue(managerSource.contains("private const val STRICT_MUXER_PREWARM_MIN_BUFFERS = 24"))
        assertTrue(managerSource.contains("muxerSampleQueue.prewarmBuffers("))
        val videoDrainLaunch = managerSource
            .substringAfter("private fun launchVideoEncoderDrain(")
            .substringBefore("private fun launchAudioEncoderDrain(")
        assertTrue(videoDrainLaunch.contains("context = videoEncoderDrainDispatcher"))
        assertTrue(videoDrainLaunch.contains("start = CoroutineStart.LAZY"))
        assertTrue(videoDrainLaunch.contains("drainSession.attachVideoDrain { job.cancel() }"))
        assertTrue(managerSource.contains("scope.launch(muxerWriterDispatcher)"))
        assertTrue(muxerQueueSource.contains("pendingSamples.offer("))
        assertTrue(muxerQueueSource.contains("maxStrictBackpressureWaitNs"))
        assertTrue(muxerQueueSource.contains("pendingByteBudgetChanged.awaitNanos(remainingNs)"))
        assertTrue(muxerQueueSource.contains("failBackpressureLocked(\"sample-count capacity\")"))
        assertTrue(muxerQueueSource.contains("STRICT_BACKPRESSURE_TIMEOUT"))
        assertTrue(muxerQueueSource.contains("resetGeneration"))
        assertTrue(muxerQueueSource.contains("getRuntimeBufferAllocationCount"))
        assertTrue(muxerQueueSource.contains("if (!strictNoDrop && failure == null && !enqueued)"))
    }

    @Test
    fun explicitHighResolutionP010RouteUsesSustainableFpsBeforeEncoderPrepare() {
        val helperBlock = viewModelSource
            .substringAfter("private suspend fun applyLegacyP010SustainableFps(")
            .substringBefore("fun dispatch(action: UiAction)")
        val recordingStartBlock = viewModelSource
            .substringAfter("private fun startRecordingInternal()")
            .substringBefore("val previewBindingSessionGraphPlan = activeCamera2SessionGraphParityPlan(")

        assertTrue(helperBlock.contains("LegacyP010FpsPolicy.resolve(recording.fps, preparedEncoderSize)"))
        listOf(
            "recording.fps = sustainableP010Fps",
            "gpuRecording.videoFps = sustainableP010Fps",
            "prefs.videoFps = sustainableP010Fps",
            "camera.setFPS(sustainableP010Fps)",
            "setEncoderFrameRate(sustainableP010Fps)"
        ).forEach { update ->
            assertTrue(helperBlock.contains(update))
        }
        assertTrue(recordingStartBlock.contains("applyLegacyP010SustainableFps("))
        assertTrue(recordingStartBlock.contains("reason = \"recording-start\""))
        assertFalse(viewModelSource.contains("suspend fun prepareLegacyP010AfterOffscreenRejection("))
        assertTrue(viewModelSource.contains("suspend fun rejectOffscreenAhbEglRecordingNoDowngrade("))
    }

    @Test
    fun byteBufferP010ImageSinkDrainsQueuedReaderImagesPerCallback() {
        val enqueueBlock = framePipelineSource
            .substringAfter("fun enqueue(reader: ImageReader)")
            .substringBefore("private fun processAcquiredImage(")

        assertTrue(enqueueBlock.contains("MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES"))
        assertTrue(enqueueBlock.contains("FramePipelineImageDrainPolicy.drainAvailable("))
        assertTrue(enqueueBlock.contains("reader.acquireNextImage()"))
        assertTrue(enqueueBlock.contains("processAcquiredImage(image, rawYuvImageSinkSnapshot)"))
        assertTrue(enqueueBlock.contains("reader.acquireLatestImage()"))
        assertFalse(enqueueBlock.contains("val image = try {\n            if (rawYuvImageSinkSnapshot != null)"))
    }

    @Test
    fun byteBufferP010EvidenceSeparatesSourceCallbacksAcceptanceQueueAndEncode() {
        assertTrue(managerSource.contains("\"byteBufferP010CallbackFrames\""))
        assertTrue(managerSource.contains("\"byteBufferP010AcceptedFrames\""))
        assertTrue(managerSource.contains("\"byteBufferP010AdmissionRejectedFrames\""))
        assertTrue(managerSource.contains("\"byteBufferP010AdmissionCapacity\""))
        assertTrue(managerSource.contains("\"byteBufferP010PeakInFlightFrames\""))
        assertTrue(managerSource.contains("\"byteBufferP010FramesQueued\""))
        assertTrue(managerSource.contains("\"byteBufferP010FramesEncoded\""))
        assertTrue(managerSource.contains("\"byteBufferP010IngressHealth\""))
        val runtimeFormatter = File("src/main/java/com/neoproc/neoproccamx/ui/RecordingRuntimeEvidenceFormatter.kt")
            .readText()
        assertTrue(viewModelSource.contains("RecordingRuntimeEvidenceFormatter.format("))
        assertTrue(viewModelSource.contains("p010SourceTelemetry = pipeline.rawYuvImageSinkTelemetry()"))
        assertTrue(runtimeFormatter.contains("\"p010SourceImageCallbacks\""))
        assertTrue(runtimeFormatter.contains("\"p010SourceOwnershipTransferred\""))
    }

    @Test
    fun byteBufferP010RecordingNeverFallsBackToSnapshotAndRepack() {
        val copyBody = managerSource
            .substringAfter("private fun copyP010ImageIntoInputBuffer(")
            .substringBefore("private fun finishByteBufferImageInputTask()")

        assertTrue(copyBody.contains("copyP010ImageIntoDirectBufferWithResult"))
        assertFalse(copyBody.contains("Yuv420ImageTools.snapshot"))
        assertFalse(copyBody.contains("RawYuvFrame("))
        assertFalse(copyBody.contains("packIntoDirectP010"))
        assertTrue(managerSource.contains("\"byteBufferP010NativeDirectCopies\""))
        assertTrue(managerSource.contains("\"byteBufferP010DirectBufferCopies\""))
        assertTrue(managerSource.contains("\"byteBufferP010AverageCopyUs\""))
        assertTrue(managerSource.contains("\"byteBufferP010AverageCodecInputWaitUs\""))
    }

    @Test
    fun p010UsesCopyFreeLinearBlockAdmissionOnlyWhenCodecConfirmsCompatibility() {
        assertTrue(managerSource.contains("MediaCodec.LinearBlock.isCodecCopyFreeCompatible"))
        assertTrue(managerSource.contains("MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL"))
        assertTrue(managerSource.contains("MediaCodec.LinearBlock.obtain"))
        assertTrue(managerSource.contains("codec.getQueueRequest(inputIndex)"))
        assertTrue(managerSource.contains(".setLinearBlock(linearBlock, 0, payloadBytes)"))
        assertTrue(managerSource.contains("shouldUseP010LinearBlockAdmission"))
        assertTrue(managerSource.contains("\"byteBufferP010LinearBlockCopyFree\""))
        assertTrue(managerSource.contains("\"byteBufferP010LinearBlockFramesQueued\""))
        assertTrue(managerSource.contains("copyP010ImageIntoDirectBufferWithResult"))
    }

    @Test
    fun blockModelCodecFailureStopsFurtherQueueRequestsAndEosWaits() {
        val callbackBody = managerSource
            .substringAfter("override fun onError(codec: MediaCodec")
            .substringBefore("Handler(callbackThread.looper)")
        val queueBody = managerSource
            .substringAfter("private fun queueExternalVideoImageWithLinearBlock(")
            .substringBefore("private fun copyP010ImageIntoInputBuffer(")

        assertTrue(callbackBody.contains("p010BlockModelFailureState.markFailed()"))
        assertTrue(queueBody.contains("if (!p010BlockModelFailureState.canQueue())"))
        assertTrue(managerSource.contains("P010 LinearBlock codec failed before EOS"))
    }

    @Test
    fun byteBufferPrepareDoesNotCreateMediaCodecInputSurface() {
        val prepareBlock = managerSource
            .substringAfter("fun prepare(): Surface?")
            .substringBefore("// \u2500\u2500 Audio encoder")

        assertTrue(prepareBlock.contains("configureVideoCodecForInputMode"))
        assertTrue(managerSource.contains("encoderSurface = null"))
        assertTrue(managerSource.contains("Video encoder using P010 bytebuffer input"))
        assertFalse(prepareBlock.contains("encoderSurface = attachVideoInputSurface(videoCodecInstance)\n                videoCodecObj = videoCodecInstance"))
    }

    @Test
    fun byteBufferStopQueuesInputEosInsteadOfSurfaceEos() {
        assertTrue(managerSource.contains("private fun signalVideoEndOfStream()"))
        val eosBlock = managerSource
            .substringAfter("private fun signalVideoEndOfStream()")
            .substringBefore("// Stop recording after")

        assertTrue(eosBlock.contains("RecordingVideoInputMode.BYTEBUFFER_P010"))
        assertTrue(eosBlock.contains("MediaCodec.BUFFER_FLAG_END_OF_STREAM"))
        assertTrue(eosBlock.contains("signalEndOfInputStream()"))
    }

    @Test
    fun encoderFormatPolicyAllowsP010ColorFormatOverride() {
        assertTrue(formatPolicySource.contains("inputColorFormat: Int? = null"))
        assertTrue(formatPolicySource.contains("request.inputColorFormat ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface"))
    }
}
