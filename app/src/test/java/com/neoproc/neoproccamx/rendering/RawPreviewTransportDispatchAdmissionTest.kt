package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewTransportDispatchAdmissionTest {
    @Test
    fun `native admission codes are exhaustive and unknown values fail closed`() {
        assertEquals(
            RawDemosaicPreviewDispatchAdmission.UNAVAILABLE,
            RawDemosaicPreviewDispatchAdmission.fromNative(-1)
        )
        assertEquals(
            RawDemosaicPreviewDispatchAdmission.NOT_REQUIRED,
            RawDemosaicPreviewDispatchAdmission.fromNative(0)
        )
        assertEquals(
            RawDemosaicPreviewDispatchAdmission.AVAILABLE,
            RawDemosaicPreviewDispatchAdmission.fromNative(1)
        )
        assertEquals(
            RawDemosaicPreviewDispatchAdmission.SATURATED,
            RawDemosaicPreviewDispatchAdmission.fromNative(2)
        )
        assertEquals(
            RawDemosaicPreviewDispatchAdmission.UNAVAILABLE,
            RawDemosaicPreviewDispatchAdmission.fromNative(Int.MAX_VALUE)
        )
    }

    @Test
    fun `saturation retains exact frame before JNI and preserves any older native tail`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val submit = runtime
            .substringAfter("internal fun submitSensorLinearNdrRecordingImage(")
            .substringBefore("internal fun pollSensorLinearNdrRecordingCompletion(")
        val admission = submit
            .substringAfter("when (bridge.rawDemosaicPreviewDispatchAdmission())")
            .substringBefore("val hardwareBuffer =")
        val saturated = admission
            .substringAfter("RawDemosaicPreviewDispatchAdmission.SATURATED")
            .substringBefore("RawDemosaicPreviewDispatchAdmission.UNAVAILABLE")

        assertTrue(saturated.contains("SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING"))
        assertTrue(saturated.contains("completionPending = outputRetirementPending"))
        assertTrue(saturated.contains("SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT"))
        assertTrue(saturated.contains("SensorLinearNdrDemosaicRetryCause.PREVIEW_TRANSPORT"))
        assertFalse(admission.contains("await"))
        assertTrue(
            submit.indexOf("bridge.rawDemosaicPreviewDispatchAdmission()") in
                0 until submit.indexOf("cinemaDebayer.submitRawBuffer(")
        )
    }

    @Test
    fun `retry ownership uses processor tail proof and never fabricates a native dispatch`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val retry = manager
            .substringAfter("private fun processSensorLinearNdrRecordingFrame(")
            .substringAfter("SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING ->")
            .substringBefore("SensorLinearNdrRecordingSubmissionStatus.FAILED ->")

        assertTrue(
            retry.contains(
                "sensorLinearNdrRecordingNativePending.set(submission.completionPending)"
            )
        )
        assertFalse(retry.contains("sensorLinearNdrRecordingNativePending.set(true)"))
        assertTrue(retry.contains("sensorLinearNdrRecordingRetryFrame.set(frame)"))
    }

    @Test
    fun `native readiness proves ring existence and keeps reservation failure as final backstop`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val preflight = native
            .substringAfter("static jint rawPreviewTransportDispatchAdmission(")
            .substringBefore("static void abandonReservedRawPreviewTransportSlot(")
        val dispatch = native
            .substringAfter("const int transportSlot = reserveFreeRawTransportSlot(r);")
            .substringBefore("const VkImage outputImage = rawOutputImageForSlot")

        assertTrue(preflight.contains("!r.previewTransportRingReady"))
        assertTrue(preflight.contains("kRawTransportFree"))
        assertTrue(preflight.contains("kRawPreviewDispatchSaturated"))
        assertTrue(dispatch.contains("reserveFreeRawPreviewTransportSlot(r)"))
        assertTrue(dispatch.contains("abandonReservedRawTransportSlot(r, transportSlot)"))
        assertTrue(dispatch.contains("return false"))
        assertTrue(native.contains("kRawPreviewTransportSlotCount = 3u"))
    }

    @Test
    fun `dispatch admission requires healthy encoder preview worker and project surfaces`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val submit = runtime
            .substringAfter("internal fun submitSensorLinearNdrRecordingImage(")
            .substringBefore("syncRawSceneLogTransportState()")

        assertTrue(submit.contains("sensorLinearNdrCaptureEnabled"))
        assertTrue(submit.contains("offscreenAhbEglSubmissionEnabled.get()"))
        assertTrue(submit.contains("boundOffscreenEncoderSurface == null"))
        assertTrue(submit.contains("boundSensorLinearNdrPreviewSurface == null"))
        assertTrue(submit.contains("sensorLinearNdrRecordingPreviewListener == null"))
        assertTrue(submit.contains("OrderedNdrPreviewTransportWorker.State.RUNNING"))
        assertTrue(submit.contains("previewWorker.lastFailure != null"))
    }

    private fun source(path: String): String = listOf(File(path), File("app/$path"))
        .first(File::isFile)
        .readText()
}
