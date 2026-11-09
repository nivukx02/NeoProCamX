package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerHdrAwbContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun `awb lock follows white balance plan so camera stability policy controls it`() {
        assertTrue(
            "CameraController.applyAll must write CONTROL_AWB_LOCK from the resolved " +
                "CinemaAutoWhiteBalanceRequestPlan so recording stability and manual WB " +
                "are not overridden by a separate direct request.",
            source.contains("builder[CaptureRequest.CONTROL_AWB_LOCK] = whiteBalancePlan.awbLock")
        )
        assertTrue(
            "The old direct AE/AWB lock coupling must stay removed for AWB.",
            !source.contains("builder[CaptureRequest.CONTROL_AWB_LOCK] = aeAwbLocked")
        )
    }

    @Test
    fun `hdr request path applies subject metering fallback and clears stale regions`() {
        assertTrue(
            "HDR mode needs a center-weighted AE/AWB fallback when AI or tap subject " +
                "metering is unavailable, otherwise distant skin is exposed from the " +
                "whole frame while close skin looks correct.",
            source.contains("HdrSubjectMeteringFallbackPolicy.resolve")
        )
        assertTrue(
            "The repeating request must clear old AE metering when no region is valid " +
                "so mode switches cannot keep stale HDR or tap metering.",
            source.contains("builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)")
        )
        assertTrue(
            "The repeating request must clear old AWB metering when no region is valid " +
                "so stale red-biased subject windows do not survive mode switches.",
            source.contains("builder.set(CaptureRequest.CONTROL_AWB_REGIONS, null)")
        )
    }

    @Test
    fun `recording request resolves video color stability before white balance`() {
        assertTrue(source.contains("val videoRecordingRequestActive = recordingActive || previewContinuityRecordSurfaceEnabled"))
        assertTrue(source.contains("VideoColorStabilityPolicy.resolve("))
        assertTrue(source.contains("videoRecordingActive = videoRecordingRequestActive"))
        assertTrue(source.contains("sensorMasterActive = rawVideoCaptureEnabled"))
        assertTrue(source.contains("normalSdrVideoPreviewActive = normalSdrVideoPreviewActive"))
        assertTrue(source.contains("autoExposureConvergedOrLocked = autoExposureConvergedOrLocked"))
        assertTrue(source.contains("autoWhiteBalanceConvergedOrLocked = autoWhiteBalanceConvergedOrLocked"))
        assertTrue(source.contains("awbLocked = colorStabilityPlan.awbLockRequested"))
        assertTrue(source.contains("requireLockableAutoWhiteBalance = colorStabilityPlan.requiresLockableAutoWhiteBalance"))
        assertTrue(source.contains("maybeLogVideoColorStabilityRequest("))
    }

    @Test
    fun `preview continuity recording uses the same video request quality path as recording`() {
        assertTrue(source.contains("isRecording = videoRecordingRequestActive"))
        assertTrue(source.contains("recordingActive = videoRecordingRequestActive"))
        assertTrue(source.contains("if (!videoRecordingRequestActive) return"))
    }

    @Test
    fun `video color stability log carries the drift budget and adaptive color state`() {
        assertTrue(source.contains("maxFrameToFrameColorDriftUv"))
        assertTrue(source.contains("sceneAdaptiveColorAllowed"))
        assertTrue(source.contains("requiresLockableAutoWhiteBalance"))
    }

    @Test
    fun `ndr and nivukx do not discard manual white balance by forcing live awb`() {
        assertTrue(source.contains("manualKelvin = effectiveManualKelvin"))
        assertTrue(source.contains("forceAutoForHdr = false"))
        assertTrue(!source.contains("forceAutoForHdr = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR"))
    }
}
