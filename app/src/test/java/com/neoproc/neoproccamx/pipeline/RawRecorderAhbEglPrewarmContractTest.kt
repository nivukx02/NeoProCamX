package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRecorderAhbEglPrewarmContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText().replace("\r\n", "\n")
    }

    @Test
    fun `both stable recorder transports prewarm before ordered RAW ingress`() {
        val lifecycle = source(
            "src/main/java/com/neoproc/neoproccamx/ui/SensorLinearNdrRecordingLifecycleCoordinator.kt"
        )
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val awaitTransition = lifecycle
            .substringAfter("suspend fun awaitDemosaicTransition(): Boolean")
            .substringBefore("suspend fun startIngress(): Boolean")
        val prepare = runtime
            .substringAfter("internal fun prepareSensorLinearNdrRecordingDemosaicResources(")
            .substringBefore("internal fun isRawDemosaicPipelineReady(): Boolean")

        assertTrue(awaitTransition.contains("prepareSensorLinearNdrRecordingDemosaicResources("))
        assertFalse(awaitTransition.contains("startSensorLinearNdrRecordingIngress("))
        assertTrue(prepare.contains("bridge.prepareRawRecordingDemosaicResources(width, height)"))
        assertTrue(prepare.contains("repeat(RAW_RECORDING_TRANSPORT_RING_SLOTS)"))
        assertTrue(runtime.contains("RAW_RECORDING_TRANSPORT_RING_SLOTS = 2"))
        assertTrue(prepare.contains("acquireRawRecordingDemosaicPrewarmHardwareBuffer(slot)"))
        assertTrue(prepare.contains("prewarmProjectOwnedRawTransport("))
        assertTrue(prepare.contains("output.close()"))
        val resourceReady = prepare.indexOf(
            "val prepared = bridge.prepareRawRecordingDemosaicResources(width, height)"
        )
        val compatibilityReturn = Regex("""\n\s+prepared\n""")
            .find(prepare, resourceReady.coerceAtLeast(0))
            ?.range
            ?.first
            ?: -1
        assertTrue(resourceReady >= 0)
        assertTrue(compatibilityReturn > resourceReady)
        assertFalse(prepare.substring(resourceReady, compatibilityReturn).contains("return@synchronized false"))
    }

    @Test
    fun `Vulkan prewarm export never reserves dispatches or acknowledges a transport`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val prewarmExport = native
            .substringAfter("nativeAcquireRawRecordingDemosaicPrewarmHardwareBuffer(")
            .substringBefore("nativeCopyRawDemosaicPreviewInto(")

        assertTrue(prewarmExport.contains("recordingTransportRingReady"))
        assertTrue(prewarmExport.contains("r.dispatchInFlight"))
        assertTrue(prewarmExport.contains("r.dispatchTransportSlot != -1"))
        assertTrue(prewarmExport.contains("kRawTransportFree"))
        assertTrue(prewarmExport.contains("AHardwareBuffer_toHardwareBuffer(env, transport)"))
        assertFalse(prewarmExport.contains("reserveFreeRawTransportSlot("))
        assertFalse(prewarmExport.contains("acknowledgeOldestCompletedRawTransport("))
        assertFalse(
            prewarmExport.contains("transportOwnership[static_cast<size_t>(slot)] =")
        )
    }

    @Test
    fun `EGL prewarm decodes and finishes without producing an encoder frame`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val prewarm = native
            .substringAfter("nativePrewarmProjectOwnedRawTransport(")
            .substringBefore("nativeSubmitEncoderReadinessFrame(")

        assertTrue(prewarm.contains("eglImageCacheEnabled"))
        assertTrue(prewarm.contains("ahardwareBufferGetId"))
        assertTrue(prewarm.contains("configureHistoryTexture("))
        assertTrue(prewarm.contains("glEGLImageTargetTexture2DOES"))
        assertTrue(prewarm.contains("glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)"))
        assertTrue(prewarm.contains("glFinish()"))
        assertTrue(prewarm.contains("state->decodeHistoryValid = false"))
        assertFalse(prewarm.contains("eglSwapBuffers("))
        assertFalse(prewarm.contains("eglPresentationTimeANDROID("))
        assertFalse(prewarm.contains("++state->submittedFrames"))
        assertFalse(prewarm.contains("++state->failedFrames"))
        assertFalse(prewarm.contains("state->historyValid = true"))
    }

    @Test
    fun `prewarm preserves full extent and exact ten-bit fallback formats`() {
        val bridge = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        )
        val vulkan = source("src/main/cpp/vulkan_bridge.cpp")
        val prewarm = bridge
            .substringAfter("internal fun prewarmProjectOwnedRawTransport(")
            .substringBefore("internal fun submitEncoderReadinessFrame()")
        val resourceAllocation = vulkan
            .substringAfter("bool ensureRawDemosaicResources(")
            .substringBefore("bool importRawStorageImage(")

        assertTrue(prewarm.contains("HardwareBuffer.RGBA_FP16"))
        assertTrue(prewarm.contains("HardwareBuffer.RGBA_1010102"))
        assertFalse(prewarm.contains("resize"))
        assertFalse(prewarm.contains("downsample"))
        assertTrue(resourceAllocation.contains("createRawDemosaicExportableOutput"))
        assertTrue(resourceAllocation.contains("createRawDemosaicSceneLogTransport"))
        assertTrue(resourceAllocation.contains("recordingTransportRingReady"))
        assertFalse(resourceAllocation.contains("width /"))
        assertFalse(resourceAllocation.contains("height /"))
    }

    @Test
    fun `live RAW recorder submit retains exact cross API ownership until its fence retires`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val submit = native
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativeGetSummary(")
        val createFence = submit.indexOf("state->eglCreateSyncKHR(")
        val flush = submit.indexOf("glFlush();", createFence)
        val swap = submit.indexOf("eglSwapBuffers(state->display, state->encoderSurface)")
        val swapFailure = submit.indexOf("if (swapped != EGL_TRUE)", swap)
        val receiptCommit = submit.indexOf("inputReadReceiptSlot->id =", swapFailure)

        assertTrue(createFence >= 0)
        assertTrue(flush > createFence)
        assertTrue(swap > flush)
        assertTrue(swapFailure > swap)
        assertTrue(receiptCommit > swapFailure)
        assertTrue(submit.substring(swapFailure, receiptCommit).contains("eglDestroySyncKHR"))
        assertTrue(submit.contains("inputReadFenceRequested && state->inputReadFenceAvailable"))
        assertTrue(submit.contains("!inputReadFenceRequested || inputReadSync == EGL_NO_SYNC_KHR"))
    }
}
