package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanEncoderSurfaceDetachContractTest {
    private val runtime by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    }
    private val viewModel by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val retirement by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/NativeResourceRetirementCoordinator.kt")
            .readText()
    }
    private val nativeBridge by lazy {
        File("src/main/cpp/vulkan_bridge.cpp").readText()
    }

    @Test
    fun `core encoder detach always crosses JNI and commits state only after acknowledgement`() {
        val method = runtime
            .substringAfter("fun setEncoderSurface(surface: Surface?): Boolean {")
            .substringBefore("fun updateOmniClarityMotionTuning(")
        val sameSurfaceFastPath = method.indexOf(
            "if (surface != null && boundEncoderSurface === surface)"
        )
        val nativeCall = method.indexOf("bridge.setEncoderSurface(surface)")
        val acknowledgementGuard = method.indexOf("if (!nativeAcknowledged)")
        val committedSurface = method.indexOf("boundEncoderSurface = surface", acknowledgementGuard)
        val unboundPublication = method.indexOf(
            "surface == null -> VulkanEncoderSurfaceStatus.UNBOUND",
            acknowledgementGuard
        )

        assertTrue(
            runtime.substringBefore("fun setEncoderSurface(surface: Surface?): Boolean {")
                .trimEnd().endsWith("@Synchronized")
        )
        assertTrue(sameSurfaceFastPath >= 0)
        assertTrue(nativeCall > sameSurfaceFastPath)
        assertTrue(acknowledgementGuard > nativeCall)
        assertTrue(committedSurface > acknowledgementGuard)
        assertTrue(unboundPublication > committedSurface)
        assertFalse(method.take(nativeCall).contains("if (surface == null)"))
        assertFalse(method.take(acknowledgementGuard).contains("boundEncoderSurface = null"))
    }

    @Test
    fun `core encoder rejection preserves prior ownership status and telemetry`() {
        val method = runtime
            .substringAfter("fun setEncoderSurface(surface: Surface?): Boolean {")
            .substringBefore("fun updateOmniClarityMotionTuning(")
        val rejection = method
            .substringAfter("if (!nativeAcknowledged) {")
            .substringBefore("boundEncoderSurface = surface")

        assertTrue(method.contains("val previousSurface = boundEncoderSurface"))
        assertTrue(method.contains("val previousStatus = _encoderSurfaceStatus.value"))
        assertTrue(method.contains("val previousStatsBound ="))
        assertTrue(rejection.contains("boundEncoderSurface = previousSurface"))
        assertTrue(rejection.contains("_encoderSurfaceStatus.value = previousStatus"))
        assertTrue(rejection.contains("encoderSurfaceBound = previousStatsBound"))
        assertTrue(rejection.contains("internalEncoderDetachQuarantined"))
        assertTrue(rejection.contains("return false"))
        assertFalse(rejection.contains("VulkanEncoderSurfaceStatus.UNBOUND"))
    }

    @Test
    fun `HDR refresh failure cannot erase the core encoder ownership witness`() {
        val method = runtime
            .substringAfter("fun setHdrDynamicRangeMode(")
            .substringBefore("fun setPreferTenBitSdrDisplay(")

        assertTrue(method.contains("val previousEncoderStatus = _encoderSurfaceStatus.value"))
        assertTrue(method.contains("internalEncoderDetachQuarantined = true"))
        assertTrue(method.contains("_encoderSurfaceStatus.value = previousEncoderStatus"))
        assertFalse(method.contains("boundEncoderSurface = null"))
        assertFalse(method.contains("_encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND"))
    }

    @Test
    fun `CameraViewModel has a bounded atomic proof for both encoder bridges`() {
        val directNullCalls = Regex("vulkanRuntime\\.setEncoderSurface\\(null\\)")
            .findAll(viewModel)
            .count()
        val coreRuntimeProof = runtime
            .substringAfter("internal fun detachInternalEncoderSurfaceWithProof(): Boolean")
            .substringBefore("fun setEncoderSurface(surface: Surface?): Boolean")
        val atomicRuntimeProof = runtime
            .substringAfter("internal fun detachAllEncoderSurfacesWithProof(): Boolean")
            .substringBefore("fun setOffscreenAhbEglEncoderSurface(")
        val helper = viewModel
            .substringAfter("private fun detachAllGpuEncoderSurfacesWithProof(reason: String): Boolean")
            .substringBefore("private fun prearmOffscreenHardwareImportSource(")

        assertEquals(0, directNullCalls)
        assertTrue(coreRuntimeProof.contains("if (!hasInternalEncoderSurfaceOwnership()) return true"))
        assertTrue(coreRuntimeProof.contains("setEncoderSurface(null)"))
        assertTrue(atomicRuntimeProof.contains("val coreDetached = detachInternalEncoderSurfaceWithProof()"))
        assertTrue(atomicRuntimeProof.contains("val offscreenDetached = detachOffscreenAhbEglEncoderSurfaceWithProof()"))
        assertTrue(atomicRuntimeProof.contains("!hasInternalEncoderSurfaceOwnership()"))
        assertTrue(atomicRuntimeProof.contains("!hasOffscreenAhbEglEncoderOwnership()"))
        assertTrue(helper.contains("NativeResourceRetirementRetryPolicy.retire"))
        assertFalse(helper.contains("vulkanRuntime.hasInternalEncoderSurfaceOwnership()"))
        assertFalse(helper.contains("vulkanRuntime.hasOffscreenAhbEglEncoderOwnership()"))
        assertTrue(helper.contains("vulkanRuntime.detachAllEncoderSurfacesWithProof()"))
        assertTrue(helper.contains("codec and Surface ownership were retained"))
    }

    @Test
    fun `process retirement proves both encoder bridges before destroying recorder`() {
        val lease = retirement
            .substringAfter("internal class CameraNativeResourceRetirementLease(")
        val compositeProof = lease.indexOf("vulkanRuntime.detachAllEncoderSurfacesWithProof()")
        val failedProof = lease.indexOf("if (!encoderDetached)", compositeProof)
        val recorderDestroy = lease.indexOf(
            "retireSafely(\"GPU recorder\", destroyGpuRecorder)",
            compositeProof.coerceAtLeast(0)
        )

        assertTrue(compositeProof >= 0)
        assertTrue(failedProof > compositeProof)
        assertTrue(recorderDestroy > compositeProof)
        assertTrue(lease.substring(compositeProof, recorderDestroy).contains("if (!encoderDetached)"))
        assertTrue(lease.substring(compositeProof, recorderDestroy).contains("return"))
    }

    @Test
    fun `native recording state follows successful encoder binding only`() {
        val jni = nativeBridge
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetEncoderSurface(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetOmniClarityEnabled(")

        assertTrue(jni.contains("state->recordingActive = bound && (surface != nullptr);"))
        assertFalse(jni.contains("state->recordingActive = (surface != nullptr);"))
    }

    @Test
    fun `offscreen detach commits unbound state only after native acknowledgement`() {
        val detach = runtime
            .substringAfter("if (surface == null) {")
            .substringBefore("offscreenAhbEglEncoderBridge.setNdrPeakPlan(ndrPeakPlan)")
        val nativeCall = detach.indexOf(
            "offscreenAhbEglEncoderBridge.setEncoderSurface(null, 0, 0)"
        )
        val acknowledgementGuard = detach.indexOf("if (!nativeDetached)")
        val surfaceClear = detach.indexOf("boundOffscreenEncoderSurface = null")
        val activeClear = detach.indexOf("offscreenAhbEglEncoderActive = false")
        val unboundPublication = detach.lastIndexOf(
            "_encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND"
        )

        assertTrue(detach.contains("val nativeDetached = try"))
        assertTrue(
            runtime.substringBefore("fun setOffscreenAhbEglEncoderSurface(").trimEnd()
                .endsWith("@Synchronized")
        )
        assertTrue(nativeCall >= 0)
        assertTrue(acknowledgementGuard > nativeCall)
        assertTrue(surfaceClear > acknowledgementGuard)
        assertTrue(activeClear > acknowledgementGuard)
        assertTrue(unboundPublication > acknowledgementGuard)
        assertFalse(detach.take(acknowledgementGuard).contains("boundOffscreenEncoderSurface = null"))
        assertFalse(detach.take(acknowledgementGuard).contains("offscreenAhbEglEncoderActive = false"))
    }

    @Test
    fun `rejected or throwing native detach stays quarantined and cannot release RAW ownership`() {
        val detach = runtime
            .substringAfter("if (surface == null) {")
            .substringBefore("offscreenAhbEglEncoderBridge.setNdrPeakPlan(ndrPeakPlan)")
        val rejection = detach
            .substringAfter("if (!nativeDetached) {")
            .substringBefore("offscreenEncoderDetachQuarantined = false")
        val submissionGate = runtime
            .substringAfter("fun setOffscreenAhbEglSubmissionEnabled(enabled: Boolean)")
            .substringBefore("fun setSensorLinearNdrCaptureEnabled(")
        val ownershipGate = runtime
            .substringAfter("private fun isSensorLinearNdrEncoderDetached(): Boolean =")
            .substringBefore("/** Reaps a quarantined failed tail")

        assertTrue(detach.contains("catch (throwable: Throwable)"))
        assertTrue(rejection.contains("offscreenEncoderDetachQuarantined = true"))
        assertTrue(rejection.contains("return false"))
        assertFalse(rejection.contains("boundOffscreenEncoderSurface = null"))
        assertFalse(
            rejection.lineSequence().any {
                it.trim() == "_encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND"
            }
        )
        assertTrue(detach.contains("VulkanEncoderSurfaceStatus.UNBOUND.copy(bound = true)"))
        assertTrue(detach.contains("offscreenEglEncoderSurfaceBound = true"))
        assertTrue(submissionGate.contains("!offscreenEncoderDetachQuarantined"))
        assertTrue(
            runtime.substringBefore("fun setOffscreenAhbEglSubmissionEnabled(enabled: Boolean)")
                .trimEnd().endsWith("@Synchronized")
        )
        assertTrue(ownershipGate.contains("!offscreenEncoderDetachQuarantined"))
    }

    @Test
    fun `bind rejection and exception cleanup use the acknowledged detach transaction`() {
        val encoderSurfaceMethod = runtime
            .substringAfter("fun setOffscreenAhbEglEncoderSurface(")
            .substringBefore("fun setPreviewSurface(surface: Surface?)")
        val bind = encoderSurfaceMethod
            .substringAfter("return runCatching {")
        val rejected = bind
            .substringAfter("if (!proven) {")
            .substringBefore("} else {")
        val exceptionCleanup = bind
            .substringAfter("}.onFailure { throwable ->")
            .substringBefore("}.getOrDefault(false)")

        assertTrue(rejected.contains("if (!setOffscreenAhbEglEncoderSurface(null, 0, 0))"))
        assertFalse(rejected.contains("boundOffscreenEncoderSurface = null"))
        assertFalse(rejected.contains("offscreenAhbEglEncoderActive = false"))
        assertTrue(exceptionCleanup.contains("if (!setOffscreenAhbEglEncoderSurface(null, 0, 0))"))
        assertFalse(exceptionCleanup.contains("boundOffscreenEncoderSurface = null"))
        assertFalse(exceptionCleanup.contains("VulkanEncoderSurfaceStatus.UNBOUND"))
    }

    @Test
    fun `core and offscreen bridges reject dual ownership before native bind`() {
        val offscreenBind = runtime
            .substringAfter("fun setOffscreenAhbEglEncoderSurface(")
            .substringBefore("fun setPreviewSurface(surface: Surface?)")
        val coreBind = runtime
            .substringAfter("fun setEncoderSurface(surface: Surface?): Boolean {")
            .substringBefore("fun updateOmniClarityMotionTuning(")

        val offscreenConflict = offscreenBind.indexOf(
            "surface != null && hasInternalEncoderSurfaceOwnership()"
        )
        val offscreenNativeBind = offscreenBind.indexOf(
            "offscreenAhbEglEncoderBridge.setEncoderSurface("
        )
        val coreConflict = coreBind.indexOf(
            "surface != null && hasOffscreenEncoderSurfaceOwnershipWitness()"
        )
        val coreNativeBind = coreBind.indexOf("bridge.setEncoderSurface(surface)")

        assertTrue(offscreenConflict >= 0)
        assertTrue(offscreenNativeBind > offscreenConflict)
        assertTrue(coreConflict >= 0)
        assertTrue(coreNativeBind > coreConflict)
    }

    @Test
    fun `runtime final close preserves every surface and bound publication until native acknowledgement`() {
        val close = runtime.substringAfter("override fun close() {")
        val encoderClose = close.indexOf("offscreenAhbEglEncoderBridge.closeAcknowledged()")
        val encoderGuard = close.indexOf("if (!offscreenBridgeClosed)")
        val previewClose = close.indexOf("sensorLinearNdrPreviewBridge.closeAcknowledged()")
        val previewGuard = close.indexOf("if (!previewBridgeClosed)")
        val offscreenSurfaceClear = close.indexOf("boundOffscreenEncoderSurface = null")
        val previewSurfaceClear = close.indexOf("boundSensorLinearNdrPreviewSurface = null")
        val unboundPublication = close.indexOf(
            "_encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND"
        )
        val encoderRejection = close
            .substringAfter("if (!offscreenBridgeClosed) {")
            .substringBefore("val previewBridgeClosed")
        val previewRejection = close
            .substringAfter("if (!previewBridgeClosed) {")
            .substringBefore("runtimeCloseQuarantined = false")

        assertTrue(
            runtime.substringBefore("override fun close() {").trimEnd().endsWith("@Synchronized")
        )
        assertTrue(encoderClose >= 0)
        assertTrue(encoderGuard > encoderClose)
        assertTrue(previewClose > encoderGuard)
        assertTrue(previewGuard > previewClose)
        assertTrue(offscreenSurfaceClear > previewGuard)
        assertTrue(previewSurfaceClear > previewGuard)
        assertTrue(unboundPublication > previewGuard)
        assertTrue(encoderRejection.contains("runtimeCloseQuarantined = true"))
        assertTrue(encoderRejection.contains("return"))
        assertFalse(encoderRejection.contains("boundOffscreenEncoderSurface = null"))
        assertTrue(previewRejection.contains("runtimeCloseQuarantined = true"))
        assertTrue(previewRejection.contains("return"))
        assertFalse(previewRejection.contains("boundSensorLinearNdrPreviewSurface = null"))
        assertTrue(close.lastIndexOf("closeCompleted.set(true)") > unboundPublication)
    }

    @Test
    fun `deferred demosaic timing uses first native completion observation`() {
        val previewSubmission = runtime
            .substringAfter("fun submitRawSensorImage(")
            .substringBefore("internal fun beginSensorLinearNdrRecordingDemosaicTransition()")
        val recordingSubmission = runtime
            .substringAfter("internal fun submitSensorLinearNdrRecordingImage(")
            .substringBefore("internal fun pollSensorLinearNdrRecordingCompletion()")
        val acknowledgement = runtime
            .substringAfter("private fun acknowledgeRawDemosaicCompletion(")
            .substringBefore("private fun publishCompletedRawDemosaicFrame(")

        assertTrue(previewSubmission.contains("completionObservedElapsedRealtimeNs ="))
        assertTrue(recordingSubmission.contains("completionObservedElapsedRealtimeNs ="))
        assertTrue(recordingSubmission.contains("SystemClock.elapsedRealtimeNanos()"))
        assertTrue(acknowledgement.contains("completionElapsedRealtimeNs ="))
        assertTrue(acknowledgement.contains("frame.demosaicCompletionObservedElapsedRealtimeNs"))
        assertFalse(acknowledgement.contains("SystemClock.elapsedRealtimeNanos()"))
    }
}
