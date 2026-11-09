package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrRawPreviewArchitectureContractTest {
    private val compositor by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt").readText()
    }
    private val coordinator by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt").readText()
    }
    private val presenter by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/SensorLinearNdrPreviewPresenter.kt").readText()
    }
    private val vulkanRuntime by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    }
    private val viewModel by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val lifecycle by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/PersistentPreviewLifecycleCoordinator.kt").readText()
    }

    @Test
    fun `Camera2 and RAW bridge own separate producer queues`() {
        assertTrue(compositor.contains("inputSurface = Surface(st)"))
        assertTrue(compositor.contains("projectOwnedInputSurface = Surface(projectSt)"))
        assertFalse(compositor.contains("projectOwnedInputSurface = inputSurface"))
        assertTrue(presenter.contains("compositor.projectOwnedNdrInputSurface()"))
    }

    @Test
    fun `final raw geometry is primed before the first sensor linear ndr session rebuild`() {
        val sync = viewModel
            .substringAfter("private fun syncCameraHdrDynamicRangeMode()")
            .substringBefore("private fun primeSensorLinearNdrSessionGraph()")
        val prime = viewModel
            .substringAfter("private fun primeSensorLinearNdrSessionGraph()")
            .substringBefore("fun setColorProfile")

        assertTrue(sync.contains("sensorLinearNdr && !primeSensorLinearNdrSessionGraph()"))
        assertTrue(
            sync.indexOf("primeSensorLinearNdrSessionGraph()") <
                sync.indexOf("camera.setSensorLinearNdrCaptureEnabled(sensorLinearNdr)")
        )
        assertTrue(prime.contains("resolveSensorLinearNdrRawSize(encoderSize)"))
        assertTrue(prime.contains("camera.setRecordingReadoutOverrideSize(rawSize)"))
        assertTrue(prime.contains("camera.setRecordingEncoderTargetSize(encoderSize)"))
    }

    @Test
    fun `visible NDR presentation is sourced from submitted RAW demosaic output`() {
        val configureProcessors = coordinator.indexOf("private fun configureProcessors()")
        val rawProcessor = coordinator.substring(
            coordinator.indexOf("rawVideoManager.rawPreviewProcessor", configureProcessors),
            coordinator.indexOf("rawVideoManager.recordingPreviewBytesProcessor", configureProcessors)
        )
        val submitRaw = rawProcessor.indexOf("previewRuntime.submitRawSensorImage")
        val completedFrameCallback =
            rawProcessor.indexOf("completedFrameSink.present(completed, submissionIdentity)")
        val completedFramePresenter = coordinator
            .substringAfter("private class CompletedRawFramePresentationSink(")
        assertTrue(submitRaw >= 0)
        assertTrue(completedFrameCallback > submitRaw)
        assertTrue(completedFramePresenter.contains("ndrPresenter.present"))
        assertTrue(presenter.contains("submitLatestSensorLinearNdrPreviewFrame"))
    }

    @Test
    fun `deferred RAW completion retains dispatch render packet and original callback route`() {
        val idleDispatch = vulkanRuntime
            .substringAfter("internal fun submitRawSensorImage(")
            .substringBefore("private fun prepareRawPreviewOwnershipLocked(")
        val completion = vulkanRuntime
            .substringAfter("private fun publishCompletedRawDemosaicFrame(")
            .substringBefore("private fun submitLatestSensorLinearNdrFrame(")
        val previewEnqueue = vulkanRuntime
            .substringAfter("private fun enqueueSensorLinearNdrRecordingPreview(")
            .substringBefore("private fun processSensorLinearNdrRecordingPreview(")
        val previewProcess = vulkanRuntime
            .substringAfter("private fun processSensorLinearNdrRecordingPreview(")
            .substringBefore("private fun acknowledgeRawDemosaicCompletion(")

        assertTrue(idleDispatch.contains("val dispatchRenderBundle = currentNdrRenderBundleSnapshot()"))
        assertTrue(idleDispatch.contains("renderProfileIdentity = dispatchRenderBundle.identity"))
        assertTrue(idleDispatch.contains("renderEnhancementUniforms = dispatchRenderBundle.enhancementUniforms"))
        assertTrue(idleDispatch.contains("idleCompletionRoute = RawDemosaicCompletionRoute(onCompletedFrame)"))
        assertTrue(completion.contains("val renderPacket = frame.dispatchRenderPacketSnapshot()"))
        assertTrue(completion.contains("val completionRoute = frame.idleCompletionRoute"))
        assertTrue(completion.contains("completionRoute.publish(frame)"))
        assertFalse(completion.contains("currentNdrRenderBundleSnapshot()?.enhancementUniforms"))
        assertTrue(previewEnqueue.contains("renderProfileRevision = renderPacket.renderProfileRevision"))
        assertTrue(
            previewProcess.filterNot(Char::isWhitespace).contains(
                "currentNdrRenderBundleSnapshot()?.identity==submission.renderProfileIdentity"
            )
        )
    }

    @Test
    fun `committed NDR route never presents an unadmitted project texture`() {
        val admission = compositor.indexOf("projectOwnedAdmission.admit(timestampNs)")
        val unsafeReset = compositor.indexOf("projectOwnedFrameReady = false", admission)
        val committedHold = compositor.indexOf("if (projectRouteCommitted && !useProjectOwned) {")
        val draw = compositor.indexOf("GLES30.glDrawArrays", committedHold)
        assertTrue(admission >= 0)
        assertTrue(unsafeReset > admission)
        assertTrue(committedHold > unsafeReset)
        assertTrue(compositor.indexOf("if (surfaceHasPresentedProjectOwnedFrame)", committedHold) > committedHold)
        assertTrue(draw > committedHold)
    }

    @Test
    fun `RAW NDR readiness follows successful child swap rather than texture acceptance`() {
        val acceptedInput = lifecycle
            .substringAfter("fun onInputFrame(")
            .substringBefore("/** Successful HDR child-surface swap proof")
        val sensorLinearAcceptedInput = acceptedInput
            .substringAfter("if (inputOwner == PersistentPreviewOwner.HDR && sensorLinearNdrMode())")
            .substringBefore("if (targetOwner != inputOwner)")
        val successfulSwap = lifecycle
            .substringAfter("fun onProjectOwnedFramePresented(")
            .substringBefore("fun close()")

        assertTrue(acceptedInput.contains("candidateEpoch = completedEpoch"))
        assertFalse(sensorLinearAcceptedInput.contains("readyEpoch ="))
        assertFalse(sensorLinearAcceptedInput.contains("recordDistinctWarmFrame(timestampNs)"))
        assertTrue(successfulSwap.contains("readyEpoch = presentationEpoch"))
        assertTrue(successfulSwap.contains("recordDistinctWarmFrame(timestampNs)"))
        assertTrue(viewModel.contains("persistentPreviewLifecycle.onProjectOwnedFramePresented"))
    }

    @Test
    fun `RAW NDR readiness is epoch and surface generation scoped`() {
        val successfulSwap = lifecycle
            .substringAfter("fun onProjectOwnedFramePresented(")
            .substringBefore("fun close()")
        val handoff = lifecycle
            .substringAfter("private fun completeHandoff(")
            .substringBefore("private fun validEpoch(")

        assertTrue(successfulSwap.contains("observedSurfaceGeneration != surfaceGeneration"))
        assertTrue(successfulSwap.contains("presentationEpoch != candidateEpoch"))
        assertTrue(successfulSwap.contains("!validEpoch(presentationEpoch)"))
        assertTrue(handoff.contains("readyEpoch != epoch"))
        assertTrue(handoff.contains("readySurfaceGeneration != surfaceGeneration"))
    }

    @Test
    fun `new RAW epoch retains the last validated owner while current RAW output warms`() {
        val epochReset = lifecycle
            .substringAfter("fun onPresentationEpochBegan(")
            .substringBefore("fun beginHandoff(")
        val compositorEpoch = compositor
            .substringAfter("fun beginProjectOwnedNdrPresentationEpoch(")
            .substringBefore("fun commitProjectOwnedNdrPresentationEpoch(")

        assertTrue(epochReset.contains("resetNdrProofForEpoch()"))
        assertTrue(epochReset.contains("owner == PersistentPreviewOwner.HDR"))
        assertTrue(epochReset.contains("updatePresentation(PersistentPreviewOwner.HDR, null)"))
        assertTrue(epochReset.contains("selectOutput(PersistentPreviewOwner.HDR)"))
        assertTrue(epochReset.contains("retained project-owned HDR output"))
        assertFalse(epochReset.contains("beginHandoff(PersistentPreviewOwner.SDR)"))
        assertTrue(compositorEpoch.contains("Retain the last completed RAW frame"))
        assertFalse(compositorEpoch.contains("projectOwnedFrameReady = false"))
    }

    @Test
    fun `raw producer corrects surface texture Y parity exactly once`() {
        val previewBinding = vulkanRuntime
            .substringAfter("fun prepareSensorLinearNdrPreviewSurface(")
            .substringBefore("fun submitLatestSensorLinearNdrPreviewFrame(")
        assertTrue(previewBinding.contains("flipX = false"))
        assertTrue(previewBinding.contains("flipY = true"))
        assertTrue(previewBinding.contains("raw-preview-surface-texture-y-parity"))
    }

    @Test
    fun `project owned NDR consumes the shared inverse sampling orientation`() {
        val rotationUpdate = viewModel
            .substringAfter("fun setDeviceRotationDegrees(degrees: Int)")
            .substringBefore("fun refreshGeometry()")
        val geometryUpdate = viewModel
            .substringAfter("private fun applyFrameGeometry(")
            .substringBefore("private fun enqueueVulkanPreviewRenderConfig(")
        val orientationPublisher = viewModel
            .substringAfter("private fun publishProjectOwnedNdrOrientation(")
            .substringBefore("private fun enqueueVulkanPreviewRenderConfig(")
        val ndrOrientation = orientationPublisher
            .substringAfter("hdr10PqPreviewCompositor.setProjectOwnedNdrOrientation(")
            .substringBefore(")")

        assertTrue(rotationUpdate.contains("latestDeviceRotationDegrees = normalizedDegrees"))
        assertTrue(
            rotationUpdate.indexOf("publishProjectOwnedNdrOrientation(") <
                rotationUpdate.indexOf("camera.setDeviceRotationDegrees(degrees)")
        )
        assertTrue(geometryUpdate.contains("latestDeviceRotationDegrees = currentRotationDegrees"))
        assertTrue(orientationPublisher.contains("ProjectOwnedNdrPreviewTransformPolicy.resolve"))
        assertTrue(ndrOrientation.contains("rotationDegrees = ndrTransform.rotationDegrees"))
        assertTrue(ndrOrientation.contains("mirrored = ndrTransform.mirrored"))
        assertTrue(geometryUpdate.contains("setFrameGeometry(geometry)"))
    }

    @Test
    fun `project owned NDR keeps producer transform and display orientation ownership separate`() {
        assertTrue(compositor.contains("cameraTexture.getTransformMatrix(texMatrix)"))
        assertTrue(compositor.contains("rawTexture.getTransformMatrix(projectOwnedTexMatrix)"))
        assertTrue(
            compositor.contains(
                "val sourceMatrix = if (useProjectOwned) projectOwnedTexMatrix else texMatrix"
            )
        )
        assertTrue(
            compositor.contains(
                "val sourceOrientation = if (useProjectOwned) projectOwnedOrient else IDENTITY_ORIENT"
            )
        )
        assertFalse(compositor.contains("uDisplayTransform"))
        assertFalse(compositor.contains("setProjectOwnedNdrDisplayTransform"))
        assertFalse(compositor.contains("projectOwnedUsesCameraTransform -> texMatrix"))
    }
}
