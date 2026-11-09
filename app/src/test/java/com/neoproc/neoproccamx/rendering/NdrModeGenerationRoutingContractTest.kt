package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrModeGenerationRoutingContractTest {

    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    private val camera by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
    }
    private val runtime by lazy {
        source("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt")
    }
    private val viewModelTypes by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelTypes.kt")
    }
    private val renderRequestSnapshot by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/VulkanRenderRequestSnapshot.kt")
    }
    private val sensorLinearPolicy by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/SensorLinearNdrModePolicy.kt")
    }
    private val rawPreviewCoordinator by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/RawPreviewPresentationCoordinator.kt")
    }
    private val renderProfileLifecycleCoordinator by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/NdrRenderProfileLifecycleCoordinator.kt")
    }
    private val renderPublicationPolicy by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/VulkanRenderPublicationPolicy.kt")
    }
    private val colorGainPolicy by lazy {
        source("src/main/java/com/neoproc/neoproccamx/ui/PreviewColorGainPolicy.kt")
    }

    @Test
    fun `deferred render packets carry a monotonic profile generation`() {
        val request = viewModelTypes
            .substringAfter("internal data class DeferredVulkanPreviewConfigRequest(")
            .substringBefore("internal enum class PreviewPipelineOwner")
        val coordinator = camera
            .substringAfter("private val vulkanPreviewConfigCoordinator")
            .substringBefore("private val previewLutCoordinator")
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")

        assertTrue(request.contains("val renderState: VulkanRenderRequestSnapshot"))
        assertTrue(request.contains("val requestSequence: Long"))
        assertTrue(request.contains("get() = renderState.requestSequence"))
        assertTrue(request.contains("get() = renderState.authority.generation"))
        assertTrue(request.contains("get() = renderState.authority.hdrMode"))
        assertTrue(coordinator.contains("incoming.profileGeneration > pending.profileGeneration"))
        assertTrue(coordinator.contains("incoming.profileGeneration < pending.profileGeneration"))
        assertTrue(coordinator.contains("incoming.requestSequence >= pending.requestSequence"))
        assertTrue(coordinator.contains("expectedRenderState = request.renderState"))
        assertTrue(renderPush.contains("expectedRenderState: VulkanRenderRequestSnapshot? = null"))
        assertTrue(renderPush.contains("val renderState = expectedRenderState"))
        assertTrue(renderPush.contains("val profileGeneration = renderState.authority.generation"))
        assertTrue(renderPush.contains("val hdrModeAuthority = renderState.authority.hdrMode"))
        assertTrue(renderPush.contains("NDR_RENDER_CONFIG_STALE_REJECTED"))
        assertTrue(
            renderPush.indexOf("isVulkanRenderProfileGenerationCurrent(profileGeneration, hdrModeAuthority)") <
                renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(")
        )
        assertTrue(renderProfileLifecycleCoordinator.contains("runtime.commitNdrRenderProfilePacket(packet)"))
    }

    @Test
    fun `render authority mutations and native publication are atomic`() {
        val authorityAdvance = camera
            .substringAfter("private fun advanceVulkanRenderProfileGeneration(")
            .substringBefore("private fun isVulkanRenderProfileGenerationCurrent(")
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val stagingCommit = renderPush
            .substringAfter("val stagingAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(")
            .substringBefore("if (!stagingAuthorityCommitted || !renderProfilePacketStaged)")
        val finalizationCommit = renderPush
            .substringAfter("val finalizationAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(")
            .substringBefore("if (!finalizationAuthorityCommitted || !renderProfilePacketFinalized)")

        assertTrue(authorityAdvance.contains("authorityMutation: (Long) -> Unit"))
        assertTrue(authorityAdvance.contains("commitAuthorityState = {"))
        assertTrue(authorityAdvance.contains("authorityMutation(advancingGeneration)"))
        assertTrue(camera.contains("authorityMutation = { _uiMode.value = committedMode }"))
        assertTrue(camera.contains("authorityMutation = { _sdrQualityIntent.value = intent }"))
        assertTrue(camera.contains("authorityMutation = { _colorProfile.value = normalized }"))
        assertTrue(camera.contains("_videoColorSpace.value = effectiveColorSpace"))
        assertTrue(camera.contains("authorityMutation = { syncSettingsLegacyFromDomain() }"))

        assertTrue(renderPush.contains("nativeRenderConfig = VulkanNativeRenderProfileConfig("))
        assertTrue(renderPush.contains("hdrDynamicRangeMode = renderContract.swapchainDynamicRangeMode"))
        assertTrue(renderPush.contains("preferTenBitSdrDisplay ="))
        assertTrue(renderPush.contains("renderConfig = VulkanRenderConfig("))
        assertTrue(renderPush.contains("subjectDetailMask = subjectDetailMask"))
        assertTrue(stagingCommit.contains("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress("))
        assertTrue(finalizationCommit.contains("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress("))
        assertTrue(finalizationCommit.contains("gpuRecording.setLiveEnhanceUniforms("))
        assertFalse(stagingCommit.contains("vulkanRuntime.updateSubjectDetailMask(subjectDetailMask)"))
        assertTrue(
            renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(") <
                renderPush.indexOf("revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)") &&
                renderPush.indexOf("revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)") <
                renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress(")
        )
        assertFalse(
            renderPush.substringBefore(
                "val stagingAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration("
            )
                .contains("vulkanRuntime.setHdrDynamicRangeMode(")
        )
        assertTrue(renderPush.contains("key = renderState.deliveryKey"))
        assertTrue(renderPush.contains("characteristics = renderState.cameraCharacteristics"))
        assertTrue(renderPush.contains("renderState = renderState"))
        assertTrue(renderPush.contains("deliveryState = authoritativeDeliveryState"))
    }

    @Test
    fun `deferred resolution is bound to one captured delivery key and camera characteristics`() {
        val capture = camera
            .substringAfter("private fun captureDeferredVulkanPreviewConfigRequest(")
            .substringBefore("private fun enqueueVulkanPreviewRenderConfig(")
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val deliveryResolver = camera
            .substringAfter("private fun resolveVideoFormatDeliveryState(")
            .substringBefore("private fun activeVideoPipelineContract(")
        val renderResolver = camera
            .substringAfter("private fun resolveVulkanPreviewRenderContract(")
            .substringBefore("private fun isWideGamutVideoSpace(")

        assertTrue(capture.contains("vulkanRenderProfileAuthority.captureIfCurrent("))
        assertTrue(capture.contains("val characteristics = camera.characteristics"))
        assertTrue(capture.contains("characteristicsIdentity = System.identityHashCode(characteristics)"))
        assertTrue(capture.contains("deliveryKey = deliveryKey"))
        assertTrue(capture.contains("cameraCharacteristics = characteristics"))
        assertTrue(capture.contains("nativePreviewLut = nativePreviewLutState.get()"))

        assertTrue(renderPush.contains("resolveVideoFormatDeliveryState("))
        assertTrue(renderPush.contains("key = renderState.deliveryKey"))
        assertTrue(renderPush.contains("characteristics = renderState.cameraCharacteristics"))
        assertTrue(renderPush.contains("resolveVulkanPreviewRenderContract("))
        assertFalse(renderPush.contains("activeVideoFormatDeliveryState("))
        assertFalse(renderPush.contains("activeVulkanPreviewRenderContract("))
        assertFalse(renderPush.contains("camera.characteristics"))

        assertTrue(deliveryResolver.contains("deliveryContractCache.resolve(key)"))
        assertTrue(deliveryResolver.contains("uiMode = key.appMode"))
        assertTrue(deliveryResolver.contains("requestedHdrMode = key.requestedHdrMode"))
        assertTrue(deliveryResolver.contains("characteristics = characteristics"))
        assertTrue(renderResolver.contains("deliveryKey = renderState.deliveryKey"))
        assertTrue(renderResolver.contains("val lut = renderState.nativePreviewLut"))
        assertTrue(renderResolver.contains("colorProfile = renderState.colorProfile"))
        assertTrue(renderResolver.contains("cameraPreviewHdrTransportMode = renderState.cameraPreviewHdrTransportMode"))
        assertFalse(renderResolver.contains("camera.characteristics"))
        assertFalse(renderResolver.contains("nativePreviewLutState.get()"))
    }

    @Test
    fun `rejected render work cannot publish evidence apex or color gain state`() {
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val beforeCommit = renderPush
            .substringBefore("val stagingAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(")
        val stagingCommit = renderPush
            .substringAfter("val stagingAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(")
            .substringBefore("if (!stagingAuthorityCommitted || !renderProfilePacketStaged)")
        val finalizationCommit = renderPush
            .substringAfter("val finalizationAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(")
            .substringBefore("if (!finalizationAuthorityCommitted || !renderProfilePacketFinalized)")
        val colorGainBuilder = colorGainPolicy

        listOf(
            "lastPreviewGradeSha256 =",
            "lastRecordingGradeSha256 =",
            "lastPreviewColorUniformSha256 =",
            "lastRecordingColorUniformSha256 =",
            "lastMonitorDeliveryColorUniformDifferenceAllowed =",
            "previewColorGainState =",
            "apexPipelineState.publishCommittedMode(",
            "lastGpuEnhancementUniforms =",
            "gpuRecording.setLiveEnhanceUniforms("
        ).forEach { sideEffect ->
            assertFalse("pre-commit side effect found: $sideEffect", beforeCommit.contains(sideEffect))
            assertFalse("staging side effect found: $sideEffect", stagingCommit.contains(sideEffect))
            assertTrue("committed publication missing: $sideEffect", finalizationCommit.contains(sideEffect))
        }
        assertTrue(renderPush.contains("subjectDetailMask = subjectDetailMask"))
        assertFalse(stagingCommit.contains("vulkanRuntime.updateSubjectDetailMask("))
        assertTrue(
            renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(") <
                renderPush.indexOf("revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)") &&
                renderPush.indexOf("revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)") <
                renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress(") &&
                renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress(") <
                renderPush.indexOf("lastPreviewGradeSha256 =")
        )
        assertTrue(colorGainBuilder.contains("val nextState = renderState.previousColorGain.copyOf()"))
        assertTrue(colorGainBuilder.contains("return PreviewColorGainCandidate("))
        assertFalse(colorGainBuilder.contains("previewColorGainState ="))
        assertTrue(renderRequestSnapshot.contains("val previousColorGain: FloatArray"))
    }

    @Test
    fun `preview lut identity and native upload advance one render generation`() {
        val applyLut = camera
            .substringAfter("private fun applyResolvedPreviewLut(")
            .substringBefore("private fun refreshPreviewCompositorState(")
        val runtimeUpdate = runtime
            .substringAfter("fun updatePreviewLut(")
            .substringBefore("fun setFrameLineageSubmissionListener(")

        assertTrue(camera.contains("private val nativePreviewLutState = AtomicReference(NativePreviewLutSnapshot())"))
        assertFalse(camera.contains("@Volatile private var nativePreviewLutEnabled"))
        assertFalse(camera.contains("@Volatile private var nativePreviewLutSize"))
        assertTrue(applyLut.contains("advanceVulkanRenderProfileGeneration("))
        assertTrue(applyLut.contains("reason = \"preview-lut-publication\""))
        assertTrue(applyLut.contains("nativePreviewLutState.set("))
        assertTrue(applyLut.contains("NativePreviewLutPublicationPolicy.resolve("))
        assertTrue(applyLut.contains("nativeUploadAccepted = nativeAccepted"))
        assertTrue(applyLut.contains("publication.softwareFallbackRequired"))
        assertTrue(applyLut.contains("PREVIEW_LUT_NATIVE_REFRESH_REJECTED"))
        assertTrue(applyLut.contains("vulkanRuntime.updatePreviewLut("))
        assertTrue(applyLut.contains("renderProfileGeneration = generation"))
        assertTrue(renderPublicationPolicy.contains("val softwareFallbackSafe = desiredEnabled && !nativeUploadAccepted"))
        assertTrue(renderPublicationPolicy.contains("val preservePreviousNativeIdentity = !nativeUploadAccepted"))
        assertTrue(renderPublicationPolicy.contains("previousNativeSnapshot: NativePreviewLutSnapshot? = null"))
        assertTrue(runtimeUpdate.contains("renderProfileGeneration: Long? = null"))
        assertTrue(runtimeUpdate.contains("withCurrentRenderProfileGeneration(renderProfileGeneration, false)"))
        assertTrue(runtimeUpdate.contains("val updated = runCatching"))
        assertTrue(runtimeUpdate.contains("bridge.updatePreviewLut(lutData = lutData, size = size)"))
        assertTrue(runtimeUpdate.contains("updated"))
    }

    @Test
    fun `runtime rejects stale generation before native profile and render writes`() {
        assertTrue(runtime.contains("private val renderProfileGenerationLock = Any()"))
        assertTrue(runtime.contains("internal fun advanceRenderProfileGeneration("))
        assertTrue(runtime.contains("withCurrentRenderProfileGeneration(renderProfileGeneration, false)"))

        val ndrUpdate = runtime
            .substringAfter("internal fun updateNdrNivukxMeasuredCorrection(")
            .substringBefore("private fun applyNdrNivukxMeasuredCorrectionIfNeeded")
        val renderUpdate = runtime
            .substringAfter("fun updateRenderConfig(")
            .substringBefore("internal fun updateNdrNivukxMeasuredCorrection")

        assertTrue(ndrUpdate.contains("renderProfileGeneration: Long? = null"))
        assertTrue(ndrUpdate.contains("withCurrentRenderProfileGeneration"))
        assertFalse(ndrUpdate.contains("applyNdrNivukxMeasuredCorrectionIfNeeded()"))
        assertTrue(renderUpdate.contains("renderProfileGeneration: Long? = null"))
        assertTrue(renderUpdate.contains("withCurrentRenderProfileGeneration"))
        assertTrue(runtime.contains("internal fun commitNdrRenderProfilePacket("))
        assertTrue(runtime.contains("private val appliedNdrRenderBundle = AtomicNdrRenderBundleState()"))
        assertTrue(runtime.contains("private val stagedNdrRenderBundle = AtomicNdrRenderBundleState()"))
        assertTrue(runtime.contains("NDR_PROFILE_PACKET_COMMITTED"))
        assertTrue(runtime.contains("NdrRenderProfileCommitResult.INCOHERENT_PACKET"))
        assertTrue(runtime.contains("private fun currentNdrRenderBundleSnapshot()"))
        assertTrue(runtime.contains("val renderBundle = currentNdrRenderBundleSnapshot() ?: return false"))
    }

    @Test
    fun `commit and finalize consume mutation isolated packet snapshots`() {
        val commit = runtime
            .substringAfter("internal fun commitNdrRenderProfilePacket(")
            .substringBefore("/** Applies one already-coherent")
        val finalize = runtime
            .substringAfter("internal fun finalizeNdrRenderProfilePacket(")
            .substringBefore("/** Consumes the latest compatible cold-start packet")

        val commitSnapshot = commit.indexOf("val retainedPacket = packet.immutableSnapshot()")
        val coherenceRead = commit.indexOf("retainedPacket.coherence")
        val retainedAdmission = commit.indexOf("intent = retainedPacket")
        assertTrue(commitSnapshot >= 0)
        assertTrue(coherenceRead > commitSnapshot)
        assertTrue(retainedAdmission > coherenceRead)
        assertTrue(finalize.contains("val retainedPacket = packet.immutableSnapshot()"))
        assertTrue(finalize.contains("val stagedBundle = stagedNdrRenderBundle.snapshot()"))
        assertTrue(finalize.contains("stagedBundle?.matches(retainedPacket) != true"))
    }

    @Test
    fun `native render bundle fails closed before packet or evidence publication`() {
        val applyPacket = runtime
            .substringAfter("private fun applyNdrRenderProfilePacketNow(")
            .substringBefore("/** Consumes the latest compatible cold-start packet")
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val stagingCommit = renderPush
            .substringAfter("val stagingAuthorityCommitted = commitIfCurrentVulkanRenderProfileGeneration(")
            .substringBefore("if (!stagingAuthorityCommitted || !renderProfilePacketStaged)")

        assertTrue(applyPacket.contains("VulkanNativeRenderCommitAdmission("))
        assertTrue(applyPacket.contains("hdrDynamicRangeAccepted = setHdrDynamicRangeMode("))
        assertTrue(applyPacket.contains("tenBitSdrDisplayPreferenceAccepted = setPreferTenBitSdrDisplay("))
        assertTrue(applyPacket.contains("renderConfigAccepted = updateRenderConfig("))
        assertTrue(applyPacket.contains("subjectDetailMaskAccepted = updateSubjectDetailMask("))
        assertTrue(applyPacket.contains("correctionAccepted ="))
        assertTrue(applyPacket.contains("enhancementUniformsAccepted ="))
        assertTrue(applyPacket.contains("if (!nativeAdmission.accepted)"))
        assertTrue(applyPacket.contains("NDR_RENDER_CONFIG_NATIVE_REJECTED"))
        assertTrue(
            applyPacket.indexOf("if (!nativeAdmission.accepted)") <
                applyPacket.indexOf("stagedNdrRenderBundle.publish(")
        )
        assertTrue(renderPush.contains("if (!stagingAuthorityCommitted || !renderProfilePacketStaged)"))
        assertTrue(stagingCommit.contains("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress("))
        assertTrue(renderPush.contains("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress("))
        assertTrue(
            renderPush.indexOf("if (!stagingAuthorityCommitted || !renderProfilePacketStaged)") <
                renderPush.indexOf("revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)") &&
                renderPush.indexOf("revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)") <
                renderPush.indexOf("lastPreviewGradeSha256 =")
        )
    }

    @Test
    fun `cold-start readiness is deferred and replayed without a false terminal rejection`() {
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val close = runtime.substringAfter("override fun close()")

        assertTrue(runtime.contains("NdrRenderProfileReadinessAdmission<NdrRenderProfilePacket>()"))
        assertTrue(runtime.contains("nativeConfig = packet.nativeRenderConfig"))
        assertTrue(runtime.contains("consumersReady = initializationCompleted.get()"))
        assertTrue(runtime.contains("replayDeferredNdrRenderProfileIfReady(\"runtime-initialized\")"))
        assertTrue(runtime.contains("internal val ndrRenderProfileReadyGeneration: StateFlow<Long>"))
        assertTrue(runtime.contains("internal val ndrRenderProfileStagedGeneration: StateFlow<Long>"))
        assertTrue(runtime.contains("_ndrRenderProfileReadyGeneration.value = retainedPacket.profileGeneration"))
        assertTrue(runtime.contains("_ndrRenderProfileReadyGeneration.value = 0L"))
        assertTrue(runtime.contains("NdrRenderProfileCommitResult.DEFERRED_UNREADY"))
        assertTrue(renderProfileLifecycleCoordinator.contains("NDR_RENDER_CONFIG_DEFERRED"))
        assertTrue(
            renderProfileLifecycleCoordinator.indexOf("NdrRenderProfileCommitResult.DEFERRED_UNREADY") <
                renderProfileLifecycleCoordinator.indexOf("NDR_RENDER_CONFIG_REJECTED")
        )
        assertTrue(renderProfileLifecycleCoordinator.contains("readyGenerations.collect { readyGeneration ->"))
        assertTrue(renderProfileLifecycleCoordinator.contains("onReady(readyGeneration)"))
        assertTrue(camera.contains("NdrRenderProfileLifecycleCoordinator.observeReadyGenerations("))
        assertTrue(camera.contains("NdrRenderProfileLifecycleCoordinator.observeStagedGenerations("))
        assertTrue(camera.contains("vulkanRuntime.ndrRenderProfileReadyGeneration"))
        assertTrue(camera.contains("vulkanRuntime.ndrRenderProfileStagedGeneration"))
        assertTrue(camera.contains("rawPreviewPresentationCoordinator.onNdrRenderProfileReady(it)"))
        assertTrue(rawPreviewCoordinator.contains("fun onNdrCompositorReady()"))
        assertTrue(rawPreviewCoordinator.contains("fun onNdrRenderProfileReady(profileGeneration: Long)"))
        assertTrue(rawPreviewCoordinator.contains("ndrRouteReadinessReplay.close()"))
        assertTrue(close.contains("ndrRenderProfileReadinessAdmission.close()"))
    }

    @Test
    fun `sensor linear surface preparation accepts only the coherent committed packet`() {
        val prepare = runtime
            .substringAfter("fun prepareSensorLinearNdrPreviewSurface(")
            .substringBefore("fun submitLatestSensorLinearNdrPreviewFrame(")

        assertTrue(prepare.contains("synchronized(renderProfileGenerationLock)"))
        assertTrue(prepare.contains("val bundle = currentNdrRenderBundleSnapshot()"))
        assertTrue(prepare.contains("appliedNdrNivukxProfileGeneration != bundle.profileGeneration"))
        assertTrue(prepare.contains("appliedNdrNivukxMeasuredCorrection != bundle.correction"))
        assertTrue(prepare.contains("val profileGeneration = bundle.profileGeneration"))
        assertTrue(prepare.contains("bundle.correction,"))
        assertTrue(prepare.contains("bundle.liveProfile"))
        assertFalse(prepare.contains("pendingNdrNivukxMeasuredCorrection"))
        assertFalse(prepare.contains("NdrLiveProfilePolicy.resolve"))
    }

    @Test
    fun `raw log off and non video paths cannot inherit ndr state`() {
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val sensorLinearGate = camera
            .substringAfter("private fun isSensorLinearNdrMode(")
            .substringBefore("private fun analysisFeedRequested")

        assertTrue(renderPush.contains("val ndrRuntimeEligible ="))
        assertTrue(renderPush.contains("renderState.deliveryKey.appMode == AppMode.VIDEO"))
        assertTrue(renderPush.contains("renderState.deliveryKey.cinemaMode == CinemaVideoMode.PRO"))
        assertTrue(renderPush.contains("!renderState.deliveryKey.rawVideoActive"))
        assertTrue(renderPush.contains("!renderState.deliveryKey.logEnabled"))
        assertTrue(renderPush.contains("!renderContract.rawVideoActive"))
        assertTrue(renderPush.contains("!renderContract.logCaptureActive"))
        assertTrue(renderPush.contains("VideoHdrRuntimeAuthorityPolicy.resolve("))
        assertTrue(renderPush.contains("runtimeEligible = ndrRuntimeEligible"))
        assertTrue(renderPush.contains("activeHdrMode = hdrModeAuthority"))
        assertFalse(renderPush.contains("prefs.videoHdrMode"))
        assertTrue(renderPush.contains("NdrNivukxMeasuredCorrectionPolicy.resolve(\"OFF\")"))

        assertTrue(sensorLinearGate.contains("SensorLinearNdrModePolicy.isActive("))
        assertTrue(sensorLinearGate.contains("_uiMode.value"))
        assertTrue(sensorLinearGate.contains("_cinemaVideoMode.value"))
        assertTrue(sensorLinearGate.contains("_rawOn.value"))
        assertTrue(sensorLinearGate.contains("_logOn.value"))
        assertTrue(sensorLinearPolicy.contains("uiMode != AppMode.VIDEO"))
        assertTrue(sensorLinearPolicy.contains("cinemaMode != CinemaVideoMode.PRO"))
        assertTrue(sensorLinearPolicy.contains("rawEnabled || logEnabled"))
    }
}
