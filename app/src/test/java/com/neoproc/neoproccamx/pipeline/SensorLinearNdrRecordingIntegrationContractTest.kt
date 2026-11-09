package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrRecordingIntegrationContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `NDR recorder has typed Vulkan processor and completion owners`() {
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt"
        )

        assertTrue(coordinator.contains("sensorLinearNdrRecordingProcessor ="))
        assertTrue(coordinator.contains("submitSensorLinearNdrRecordingImage("))
        assertTrue(coordinator.contains("sensorLinearNdrRecordingCompletionPoller ="))
        assertTrue(coordinator.contains("pollSensorLinearNdrRecordingCompletion("))
    }

    @Test
    fun `ordered ingress arms before strict first-frame validation`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val lifecycle = source(
            "src/main/java/com/neoproc/neoproccamx/ui/SensorLinearNdrRecordingLifecycleCoordinator.kt"
        )
        val offscreenStart = viewModel
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean")

        val transition = offscreenStart.indexOf(
            "sensorLinearNdrRecordingLifecycle.awaitDemosaicTransition()"
        )
        val enableSubmission = offscreenStart.indexOf(
            "vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(true)"
        )
        val armIngress = offscreenStart.indexOf("sensorLinearNdrRecordingLifecycle.startIngress()")
        val firstFrameProof = offscreenStart.indexOf("awaitFirstProcessedGpuRecordingFrame(")

        assertTrue(transition >= 0)
        assertTrue(enableSubmission > transition)
        assertTrue(armIngress > enableSubmission)
        assertTrue(firstFrameProof > armIngress)

        val transitionBody = lifecycle
            .substringAfter("suspend fun awaitDemosaicTransition(): Boolean")
            .substringBefore("suspend fun startIngress(): Boolean")
        assertTrue(transitionBody.contains("prepareSensorLinearNdrRecordingDemosaicResources("))
        assertTrue(transitionBody.contains("withContext(Dispatchers.Default)"))
        val pipelineProof = transitionBody.indexOf("isRawDemosaicPipelineReady()")
        val resourcePrewarm = transitionBody.indexOf(
            "prepareSensorLinearNdrRecordingDemosaicResources("
        )
        assertTrue(pipelineProof >= 0)
        assertTrue(resourcePrewarm > pipelineProof)
        assertTrue(transitionBody.contains("withTimeoutOrNull("))
        assertTrue(transitionBody.contains("delay(pollDelayMs)"))
    }

    @Test
    fun `full sensor Vulkan resources prewarm before ordered ingress publication`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val bridge = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        )
        val native = source("src/main/cpp/vulkan_bridge.cpp")

        assertTrue(runtime.contains("prepareSensorLinearNdrRecordingDemosaicResources("))
        assertTrue(runtime.contains("internal fun isRawDemosaicPipelineReady(): Boolean"))
        assertTrue(runtime.contains("syncRawSceneLogTransportState()"))
        assertTrue(bridge.contains("prepareRawRecordingDemosaicResources("))
        assertTrue(bridge.contains("fun isRawDemosaicPipelineReady(): Boolean"))
        assertTrue(bridge.contains("nativeIsRawDemosaicPipelineReady(handle)"))
        assertTrue(native.contains("nativePrepareRawRecordingDemosaicResources"))
        assertTrue(native.contains("nativeIsRawDemosaicPipelineReady"))
        val readinessProbe = native
            .substringAfter("nativeIsRawDemosaicPipelineReady(")
            .substringBefore("nativePrepareRawRecordingDemosaicResources(")
        assertTrue(
            readinessProbe.contains(
                "rawDemosaicPipelineReady.load(std::memory_order_acquire)"
            )
        )
        assertFalse(readinessProbe.contains("lock(state->mutex)"))
        assertTrue(native.contains("ensureRawDemosaicResources(state, width, height)"))
        val nativePrewarm = native
            .substringAfter("nativePrepareRawRecordingDemosaicResources(")
            .substringBefore("nativeCopyRawDemosaicPreviewInto(")
        assertTrue(
            nativePrewarm.contains(
                "rawDemosaicPipelineReady.load(std::memory_order_acquire)"
            )
        )
        assertTrue(native.contains("RAW_RECORD_RESOURCE_PREWARM"))
    }

    @Test
    fun `ordered ingress drains before feed detach and encoder stop`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val stop = viewModel
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun rebindVisiblePreviewTargetBeforeRecordingStopRetarget(")
        val tailDrain = stop.indexOf("sensorLinearNdrRecordingLifecycle.stopIngress(drain = true)")
        val feedDetach = stop.indexOf("detachInternalRecordingFeedBeforeEncoderStop(")
        val encoderStop = stop.indexOf("gpuRecording.stop()")

        assertTrue(tailDrain >= 0)
        assertTrue(feedDetach > tailDrain)
        assertTrue(encoderStop > feedDetach)
    }

    @Test
    fun `recording ownership releases only after codec EOS and offscreen detach`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val stop = viewModel
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun rebindVisiblePreviewTargetBeforeRecordingStopRetarget(")

        val encoderStop = stop.indexOf("val path = gpuRecording.stop()")
        val offscreenDetach = stop.indexOf(
            "deferHardwareSourceRebuildUntilAfterEncoderStop = true"
        )
        val ownershipRelease = stop.indexOf(
            "sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()",
            encoderStop
        )

        assertTrue(encoderStop >= 0)
        assertTrue(offscreenDetach >= 0)
        // EGL must release its read ownership before MediaCodec EOS, while the RAW demosaic owner
        // remains retained until both boundaries have completed.
        assertTrue(encoderStop > offscreenDetach)
        assertTrue(ownershipRelease > encoderStop)
    }

    @Test
    fun `completed NDR recording frame retains exact input read before acting on preview failure`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val publish = runtime
            .substringAfter("private fun publishCompletedSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun publishCompletedRawDemosaicFrame(")
        val enqueue = runtime
            .substringAfter("private fun enqueueSensorLinearNdrRecordingPreview(")
            .substringBefore("private fun processSensorLinearNdrRecordingPreview(")

        val encoder = publish.indexOf("submitLatestSensorLinearNdrFrame(")
        val preview = publish.indexOf("enqueueSensorLinearNdrRecordingPreview(frame, renderPacket)")
        val failureLatch = publish.indexOf(
            "sensorLinearNdrRecordingPreviewWorker.recordTerminalFailure(",
            preview
        )
        val accepted = publish.indexOf("return true", failureLatch)
        assertTrue(encoder >= 0)
        assertTrue(preview > encoder)
        assertTrue(failureLatch > preview)
        assertTrue(accepted > failureLatch)
        assertTrue(
            runtime.contains(
                "if (!pacingDecision.submit) return SensorLinearNdrEncoderSubmission(submitted = false)"
            )
        )
        assertTrue(enqueue.contains("bridge.acquireRawDemosaicPreviewOutput()"))
        assertTrue(enqueue.contains("sensorLinearNdrRecordingPreviewWorker.submit("))
        assertTrue(enqueue.contains("output.retire(bridge::acknowledgeRawDemosaicPreviewOutput)"))

        val retirement = runtime
            .substringAfter("private fun retireSensorLinearNdrEncoderInputReadsLocked(")
            .substringBefore("private fun publishOrRetireSensorLinearNdrRecordingFrameLocked(")
        assertTrue(
            retirement.indexOf("pollProjectOwnedInputReadFence(") <
                retirement.indexOf("acknowledgeProjectOwnedInputReadFence(")
        )
        assertTrue(
            retirement.indexOf("acknowledgeProjectOwnedInputReadFence(") <
                retirement.indexOf(
                    "acknowledgeRawDemosaicEncoderOutput(pending.vulkanToken)"
                )
        )
    }

    @Test
    fun `live render revision drains old RAW identity then retries the same incoming image`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt"
        )
        val commit = runtime
            .substringAfter("internal fun commitNdrRenderProfilePacket(")
            .substringBefore("/** Applies one already-coherent")
        val recording = runtime
            .substringAfter("private fun submitSensorLinearNdrRecordingImageLocked(")
            .substringBefore("/** Polls the final recorder-owned dispatch")
        val mutationGate = recording.indexOf(
            "if (ndrRenderProfileMutationDeferral.pendingIdentity() != null)"
        )
        val oldCompletion = recording.indexOf(
            "publishOrRetireSensorLinearNdrRecordingFrameLocked(completed)",
            mutationGate
        )
        val retry = recording.indexOf(
            "SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING",
            oldCompletion
        )
        val newDispatch = recording.indexOf("cinemaDebayer.submitRawBuffer(", retry)

        assertTrue(commit.contains("deferNdrRenderProfileWhileRawOutputOwnedLocked("))
        assertTrue(commit.contains("NdrRenderProfileCommitResult.DEFERRED_IN_FLIGHT"))
        val mutationOwnership = runtime
            .substringAfter("private fun deferNdrRenderProfileWhileRawOutputOwnedLocked(")
            .substringBefore("private fun admitNdrRenderProfileRevisionLocked(")
        val drainPublication = runtime
            .substringAfter("private fun publishDeferredNdrRenderProfileIfRawDrainedLocked()")
            .substringBefore("private fun publishCompletedRawDemosaicFrame(")
        assertTrue(
            mutationOwnership.contains(
                "sensorLinearNdrRecordingPreviewWorker.snapshot().outstandingTasks != 0"
            )
        )
        assertTrue(
            drainPublication.contains(
                "sensorLinearNdrRecordingPreviewWorker.snapshot().outstandingTasks == 0"
            )
        )
        assertTrue(runtime.contains("onTaskRetired = ::onSensorLinearNdrRecordingPreviewTaskRetired"))
        assertTrue(mutationGate >= 0)
        assertTrue(oldCompletion > mutationGate)
        assertTrue(retry > oldCompletion)
        assertTrue(newDispatch > retry)
        assertTrue(
            recording.substring(mutationGate, newDispatch)
                .contains("Waiting for exact RAW output retirement before NDR profile replacement")
        )
        assertTrue(runtime.contains("_ndrRenderProfileMutationReadyIdentity.value = ready"))
        assertTrue(viewModel.contains("vulkanRuntime.ndrRenderProfileMutationReadyIdentity"))
        assertTrue(viewModel.contains("pushVulkanPreviewRenderConfig(force = true)"))
        assertTrue(recording.contains("SensorLinearNdrDemosaicRetryCause.RENDER_PROFILE_TRANSITION"))
        assertTrue(coordinator.contains("SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION"))
    }

    @Test
    fun `recording NDR preview sink preserves exact completion identity and visible orientation path`() {
        val coordinator = source(
            "src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt"
        )
        val sink = coordinator
            .substringAfter("private class SensorLinearNdrRecordingPreviewSink(")
            .substringBefore("private class CompletedRawFramePresentationSink(")

        assertTrue(coordinator.contains("setSensorLinearNdrRecordingPreviewListener("))
        assertTrue(coordinator.contains("recordingNdrPreviewSink::present"))
        assertTrue(sink.contains("val completed = submission.frame"))
        assertTrue(sink.contains("completed.cameraSessionGeneration"))
        assertTrue(sink.contains("presenter.present(submission)"))

        val presenter = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/SensorLinearNdrPreviewPresenter.kt"
        )
        assertTrue(presenter.contains("registerProjectOwnedNdrSubmission("))
        assertTrue(presenter.contains("submitLatestSensorLinearNdrPreviewFrame("))
        assertTrue(presenter.contains("submitSensorLinearNdrRecordingPreviewFrame(submission)"))
        val recordingPresent = presenter
            .substringAfter("fun present(submission: SensorLinearNdrRecordingPreviewSubmission)")
            .substringBefore("fun close(): Boolean")
        val runtimeSubmit = recordingPresent.indexOf(
            "submitSensorLinearNdrRecordingPreviewFrame(submission)"
        )
        val throwableCancel = recordingPresent.indexOf(
            "compositor.cancelProjectOwnedNdrSubmission(",
            runtimeSubmit
        )
        assertTrue(runtimeSubmit >= 0)
        assertTrue(throwableCancel > runtimeSubmit)
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        assertTrue(runtime.contains("source = \"raw-preview-surface-texture-y-parity\""))
    }

    @Test
    fun `recording preview and encoder lineage are emitted at separate accepted boundaries`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val lineage = runtime
            .substringAfter("private fun emitFrameLineageSubmission(")
            .substringBefore("internal fun submitRawSensorImage(")
        val previewWorker = runtime
            .substringAfter("private fun processSensorLinearNdrRecordingPreview(")
            .substringBefore("private fun acknowledgeRawDemosaicCompletion(")
        val encoder = runtime
            .substringAfter("private fun submitLatestSensorLinearNdrFrame(")
            .substringBefore("fun submitRawSensorBytes(")

        assertTrue(lineage.contains("previewSubmitted: Boolean"))
        assertTrue(lineage.contains("encoderSubmitted: Boolean"))
        assertFalse(lineage.contains("previewSubmitted = boundPreviewSurface != null"))
        assertTrue(previewWorker.contains("submission.output.retire("))
        assertTrue(previewWorker.contains("ndrNativeFrameBoundary.acquireRetirementLease()"))
        assertTrue(previewWorker.contains("retirementLease.close()"))
        assertTrue(previewWorker.contains("previewSubmitted = true"))
        assertTrue(previewWorker.contains("encoderSubmitted = false"))
        assertTrue(
            previewWorker.indexOf("submission.output.retire(") <
                previewWorker.indexOf("previewSubmitted = true")
        )
        assertTrue(encoder.contains("previewSubmitted = false"))
        assertTrue(encoder.contains("encoderSubmitted = true"))
    }

    @Test
    fun `transition timeout retires only the uncommitted ownership reservation`() {
        val lifecycle = source(
            "src/main/java/com/neoproc/neoproccamx/ui/SensorLinearNdrRecordingLifecycleCoordinator.kt"
        )
        val await = lifecycle
            .substringAfter("suspend fun awaitDemosaicTransition(): Boolean")
            .substringBefore("suspend fun startIngress(): Boolean")
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )

        assertTrue(await.contains("abortSensorLinearNdrRecordingDemosaicTransition()"))
        assertFalse(await.contains("endSensorLinearNdrRecordingDemosaic()"))
        assertTrue(runtime.contains("rawDemosaicCompletionHandoff.hasOutstandingIdentity()"))
        assertTrue(runtime.contains("SensorLinearNdrDemosaicOwnerPhase.TRANSITION"))
        assertTrue(runtime.contains("SensorLinearNdrDemosaicOwnerPhase.RETIRING_FAILED"))
    }

    @Test
    fun `failed recorder tail is quarantined and never reported ready to a new clip`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val begin = runtime
            .substringAfter("internal fun beginSensorLinearNdrRecordingDemosaicTransition()")
            .substringBefore("internal fun abortSensorLinearNdrRecordingDemosaicTransition()")
        val retirement = runtime
            .substringAfter("internal fun retireFailedSensorLinearNdrRecordingTail()")
            .substringBefore("private fun prepareRawPreviewOwnershipLocked()")

        assertTrue(begin.contains("SensorLinearNdrDemosaicOwnerPhase.RECORDING"))
        assertTrue(begin.contains("SensorLinearNdrDemosaicTransitionStatus.FAILED"))
        assertTrue(begin.contains("SensorLinearNdrDemosaicOwnerPhase.RETIRING_FAILED"))
        assertTrue(begin.contains("SensorLinearNdrDemosaicTransitionStatus.PENDING"))
        assertTrue(retirement.contains("RawDemosaicCompletionPoll.PENDING"))
        assertTrue(retirement.contains("RawDemosaicCompletionPoll.COMPLETE"))
        assertTrue(retirement.contains("rawDemosaicCompletionHandoff.quarantine("))
        assertTrue(retirement.contains("retireAfterNativeRetirementProof()"))
        assertFalse(retirement.contains("rawDemosaicCompletionHandoff.reset()"))
        assertTrue(retirement.contains("ENCODER_STILL_BOUND"))
    }

    @Test
    fun `terminal native ownership failure is sticky and release requires encoder detach`() {
        val ownership = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/SensorLinearNdrDemosaicOwnership.kt"
        )
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val end = runtime
            .substringAfter("internal fun endSensorLinearNdrRecordingDemosaic()")
            .substringBefore("internal fun retireFailedSensorLinearNdrRecordingTail()")
        val retirement = runtime
            .substringAfter("private fun retireFailedSensorLinearNdrTailLocked()")
            .substringBefore("private fun isSensorLinearNdrEncoderDetached()")

        assertTrue(ownership.contains("TERMINAL_NATIVE_FAILURE"))
        assertTrue(end.contains("if (!isSensorLinearNdrEncoderDetached())"))
        assertTrue(end.contains("RawDemosaicCompletionPoll.FAILED"))
        assertTrue(end.contains("SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE"))
        assertFalse(
            end.substringAfter("RawDemosaicCompletionPoll.FAILED").substringBefore("return@synchronized false")
                .contains("SensorLinearNdrDemosaicOwnerPhase.PREVIEW")
        )
        assertTrue(retirement.contains("SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE"))
        assertTrue(retirement.contains("SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE"))
    }

    @Test
    fun `ingress stop joins recorder worker even after logical lifecycle becomes inactive`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val stop = manager
            .substringAfter("internal fun stopSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun isSensorLinearNdrRecordingIngressActive()")

        assertTrue(stop.contains("if (stopContext == null)"))
        assertTrue(stop.contains("awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)"))
        assertTrue(stop.contains("barrier.get()"))
        assertTrue(stop.contains("holding encoder resources until worker returns"))
        assertFalse(stop.contains("stopContext.second ?: return false"))
        val missingLatch = stop.indexOf("if (latch == null)")
        val missingLatchFailure = stop.indexOf(
            "Ordered NDR recording ingress lost its stop latch",
            missingLatch
        )
        val missingLatchFinish = stop.indexOf(
            "finishSensorLinearNdrRecordingIngress(",
            missingLatchFailure
        )
        val mandatoryJoin = stop.indexOf(
            "awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)",
            missingLatchFinish
        )
        assertTrue(missingLatch >= 0)
        assertTrue(missingLatchFailure > missingLatch)
        assertTrue(missingLatchFinish > missingLatchFailure)
        assertTrue(mandatoryJoin > missingLatchFinish)
    }

    @Test
    fun `lifecycle teardown request is signal only and releases UI callback`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val request = manager
            .substringAfter("internal fun requestSensorLinearNdrRecordingIngressTeardown()")
            .substringBefore("internal fun stopSensorLinearNdrRecordingIngress(")

        val closeAdmission = request.indexOf("sensorLinearNdrRecordingAccepting.set(false)")
        val clearUiCapture = request.indexOf("sensorLinearNdrRecordingFailureListener = null")
        assertTrue(closeAdmission >= 0)
        assertTrue(clearUiCapture > closeAdmission)
        assertFalse(request.contains("withLock"))
        assertFalse(request.contains("await"))
        assertFalse(request.contains("get("))
        assertFalse(request.contains("takeAllSensorLinearNdrRecordingImages"))
        assertFalse(request.contains("releaseSensorLinearNdrImage"))
        assertFalse(request.contains("finishSensorLinearNdrRecordingIngress"))
        assertFalse(request.contains("awaitSensorLinearNdrRecordingWorkerIdle"))
    }

    @Test
    fun `RAW manager destruction joins idle preview worker before dependent owners retire`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val barrier = manager
            .substringAfter("private fun awaitIdleRawPreviewWorkerIdleForTeardown(")
            .substringBefore("private fun processIdleRawPreview(")
        val destroy = manager.substringAfter("fun destroy(): Boolean")
        val processorOff = destroy.indexOf("rawPreviewProcessor = null")
        val pendingClose = destroy.indexOf("clearIdleRawPreviewFrames()")
        val workerJoin = destroy.indexOf("awaitIdleRawPreviewWorkerIdleForTeardown")
        val executorClose = destroy.indexOf("idleRawPreviewExecutor.shutdownNow()")

        assertTrue(barrier.contains("idleRawPreviewExecutor.submit { Unit }"))
        assertTrue(barrier.contains("barrier.get()"))
        assertTrue(processorOff >= 0)
        assertTrue(pendingClose > processorOff)
        assertTrue(workerJoin > pendingClose)
        assertTrue(executorClose > workerJoin)
    }

    @Test
    fun `teardown quiescence probe is nonblocking and fails closed`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val probe = manager
            .substringAfter("internal fun isSensorLinearNdrRecordingWorkerQuiescentForTeardown()")
            .substringBefore("internal fun isSensorLinearNdrRecordingIngressActive()")

        assertTrue(probe.contains("sensorLinearNdrRecordingLifecycleLock.tryLock()"))
        assertTrue(probe.contains("resultLock.tryLock()"))
        assertTrue(probe.contains("sensorLinearNdrRecordingWorkerScheduled.get()"))
        assertTrue(probe.contains("sensorLinearNdrRecordingNativePending.get()"))
        assertTrue(probe.contains("sensorLinearNdrRecordingOwnedFrames.get() == 0"))
        assertTrue(probe.contains("sensorLinearNdrRecordingRetryFrame.get() == null"))
        assertTrue(probe.contains("sensorLinearNdrPendingImages.isEmpty()"))
        assertTrue(probe.contains("sensorLinearNdrReadyBySequence.isEmpty()"))
        assertFalse(probe.contains("await"))
        assertFalse(probe.contains("sensorLinearNdrRecordingExecutor.submit"))
        assertFalse(probe.contains("barrier.get"))
    }

    @Test
    fun `recording exposure reduction is isolated from protected recorder submission`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val worker = manager
            .substringAfter("private fun drainSensorLinearNdrRecordingFrames()")
            .substringBefore("private fun processSensorLinearNdrRecordingFrame(")
        val analytics = manager
            .substringAfter("private fun queueSensorLinearExposureMetrics(")
            .substringBefore("private fun publishSensorLinearExposureMetrics(")

        val admission = worker.indexOf("SensorLinearNdrRecordingSubmissionStatus.ACCEPTED")
        val snapshot = worker.indexOf("queueSensorLinearExposureMetrics(", admission)
        assertTrue(admission >= 0)
        assertTrue(snapshot > admission)
        assertFalse(worker.contains("RawSensorExposureAnalyzer.analyze("))
        assertTrue(analytics.contains("SensorLinearNdrSecondarySnapshot.capture("))
        assertTrue(analytics.contains("RawSensorExposureAnalyzer.snapshot("))
        assertTrue(analytics.contains("sensorLinearExposureAnalyticsExecutor"))
        assertTrue(analytics.contains("getAndSet(null)"))
    }

    @Test
    fun `deadline and join failures claim exact image ownership before release`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val pair = manager
            .substringAfter("private fun pairOrHoldSensorLinearNdrRecording(")
            .substringBefore("private fun flushSensorLinearNdrReadyFramesLocked()")
        val retryDeadline = manager
            .substringAfter("private fun sensorLinearNdrRetryDelayOrFailure(")
            .substringBefore("private fun failSensorLinearNdrRecording(")

        val joinLock = pair.indexOf("resultLock.withLock")
        val joinClaim = pair.indexOf("SensorLinearNdrJoinFailureOwnership.claimForRelease(")
        val joinUnlock = pair.indexOf("if (admissionClosed)")
        assertTrue(joinLock >= 0)
        assertTrue(joinClaim > joinLock)
        assertTrue(joinUnlock > joinClaim)
        assertFalse(pair.contains("val tracked = resultLock.withLock"))

        val retryClaim = retryDeadline.indexOf("SensorLinearNdrExactOwnership.claim(")
        val retryRelease = retryDeadline.indexOf("releaseSensorLinearNdrRecordingFrame")
        assertTrue(retryClaim >= 0)
        assertTrue(retryRelease > retryClaim)
        assertFalse(retryDeadline.contains("compareAndSet(frame, null)"))
    }

    @Test
    fun `preview epoch cleanup closes stale joins before admission publication`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val commit = manager
            .substringAfter("fun commitPreviewPresentationEpoch(")
            .substringBefore("fun currentPreviewPresentationEpoch()")
        val lock = commit.indexOf("resultLock.withLock")
        val cleanup = commit.indexOf("clearNonAuthoritativeCaptureResultsLocked()")
        val publication = commit.indexOf("previewPresentationGate.commitOutcome(")

        assertTrue(lock >= 0)
        assertTrue(cleanup > lock)
        assertTrue(publication > cleanup)
    }

    @Test
    fun `deferred demosaic diagnostics publish only after exact completion acknowledgement`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val acknowledge = runtime
            .substringAfter("private fun acknowledgeRawDemosaicCompletion(")
            .substringBefore("private fun publishCompletedRawDemosaicFrame(")

        assertTrue(acknowledge.contains("rawDemosaicCompletionHandoff.acknowledgeCompleted(frame)"))
        assertTrue(acknowledge.contains("if (acknowledged && frame.demosaicCompletionStatsExternallyOwned)"))
        assertTrue(acknowledge.contains("cinemaDebayer.recordDeferredCompletion("))
    }

    @Test
    fun `recording acquisition is ordered and stop still accepts exact metadata joins`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val rawCallback = manager
            .substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldSensorLinearNdrRecording")
        val resultCallback = manager
            .substringAfter("fun onCaptureResult(")
            .substringBefore("private fun buildRawBufferDescriptor")

        assertTrue(rawCallback.contains("sensorLinearNdrRecordingLifecycleLock.withLock"))
        assertTrue(rawCallback.contains("rawWriterAdmissionLock.withLock"))
        assertTrue(rawCallback.contains("rawCaptureIngress = snapshotRawCaptureIngressLocked("))
        val reservation = rawCallback.indexOf(
            "SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame("
        )
        val acquire = rawCallback.indexOf("reader.acquireNextImage()", reservation)
        assertTrue(reservation >= 0)
        assertTrue(acquire > reservation)
        assertTrue(rawCallback.contains("reader.acquireNextImage()"))
        assertFalse(rawCallback.contains("reader.acquireLatestImage()"))
        assertTrue(resultCallback.contains("if (sensorLinearNdrRecordingLifecycleActive.get())"))
        assertTrue(resultCallback.contains("callbackMayCompleteUnpublishedExactJoin"))
        assertTrue(resultCallback.contains("else if (!callbackOwnsOpenAdmission)"))
        assertTrue(resultCallback.contains("sensorLinearNdrPendingImages.remove(timestamp)"))
    }

    @Test
    fun `recording ingress uses a Camera2 serial timestamp cutover before image sequencing`() {
        val lifecycle = source(
            "src/main/java/com/neoproc/neoproccamx/ui/SensorLinearNdrRecordingLifecycleCoordinator.kt"
        )
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val arm = lifecycle
            .substringAfter("suspend fun startIngress(): Boolean")
            .substringBefore("fun stopIngress(")
        val start = manager
            .substringAfter("internal fun startSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun requestSensorLinearNdrRecordingIngressTeardown()")
        val imageJoin = manager
            .substringAfter("private fun pairOrHoldSensorLinearNdrRecording(")
            .substringBefore("private fun flushSensorLinearNdrReadyFramesLocked()")

        assertTrue(arm.contains("camera.backgroundHandler"))
        assertTrue(arm.contains("cameraHandler.post(cutoverAction)"))
        assertTrue(arm.contains("withTimeoutOrNull(CUTOVER_ARM_TIMEOUT_MS)"))
        assertTrue(start.contains("latestCamera2ResultSessionGeneration == expectedSessionGeneration"))
        assertTrue(start.contains("cutoverTimestampFloorNs = cutoverTimestampFloorNs"))
        assertTrue(
            start.indexOf("cutoverTimestampFloorNs = cutoverTimestampFloorNs") <
                start.indexOf("sensorLinearNdrRecordingIngressAdmission.set(ingressToken)")
        )

        val cutoverCheck = imageJoin.indexOf("timestamp <= ingressToken.cutoverTimestampFloorNs")
        val sequenceAllocation = imageJoin.indexOf("sensorLinearNdrNextImageSequence++")
        assertTrue(cutoverCheck >= 0)
        assertTrue(sequenceAllocation > cutoverCheck)
        assertTrue(imageJoin.contains("RETIRED_PRE_RECORD_CUTOVER"))
        assertTrue(imageJoin.contains("releaseSensorLinearNdrImage(image, ownershipReservationGeneration)"))
    }
}
