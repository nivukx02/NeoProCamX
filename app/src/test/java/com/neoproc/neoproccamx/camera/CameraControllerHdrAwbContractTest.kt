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
        assertTrue(source.contains("applyStillCaptureAutoWhiteBalanceLock(builder, it.awbLock)"))
        assertTrue(source.contains("applyStillCaptureAutoWhiteBalanceLock(builder, overrides.awbLock)"))
        assertTrue(source.contains("shouldApplyStillCaptureAutoLock("))
        assertTrue(!source.contains("if (it.awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)"))
        assertTrue(!source.contains("if (overrides.awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)"))
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
        assertTrue(source.contains("rawRequestContract.recordingActive || previewContinuityRecordSurfaceEnabled"))
        assertTrue(source.contains("VideoColorStabilityPolicy.resolve("))
        assertTrue(source.contains("videoRecordingActive = videoRecordingRequestActive"))
        assertTrue(source.contains("sensorMasterActive = rawSensorRequestActive"))
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

    @Test
    fun `manual white balance is fully validated before awb is disabled`() {
        val resolveIndex = source.indexOf("val whiteBalancePlan = CinemaAutoWhiteBalanceRequestPolicy.resolve(")
        val applyAwbModeIndex = source.indexOf("builder[CaptureRequest.CONTROL_AWB_MODE] = whiteBalancePlan.awbMode")
        assertTrue(resolveIndex >= 0)
        assertTrue(applyAwbModeIndex > resolveIndex)
        assertTrue(source.contains("manualTransformReady = manualWhiteBalanceTransform != null"))
        assertTrue(source.contains("CinemaManualWhiteBalanceTransformPolicy.fromDeviceCalibration("))
        assertTrue(source.contains("supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_GAINS)"))
        assertTrue(source.contains("supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_TRANSFORM)"))
        assertTrue(!source.contains("?: latestMeasuredColorTransform"))
        assertTrue(!source.contains("WhiteBalanceConversion.kelvinToRggb("))
    }

    @Test
    fun `every request rebuild clears mutually exclusive white balance payloads`() {
        assertTrue(source.contains("clearWhiteBalanceRequestOverrides(builder)"))
        assertTrue(source.contains("builder.set(CaptureRequest.COLOR_CORRECTION_MODE, null)"))
        assertTrue(source.contains("builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, null)"))
        assertTrue(source.contains("builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, null)"))
        assertTrue(source.contains("builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, null)"))
        assertTrue(source.contains("builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, null)"))
        assertTrue(source.contains("if (requestedKelvin != null) clearAutoWhiteBalanceRegions(builder)"))
    }

    @Test
    fun `capture result color correction is one current provenance snapshot`() {
        val enqueue = source.substringAfter("private fun enqueueCaptureResultDiagnostics(")
            .substringBefore("private fun scheduleCaptureResultDiagnostics()")
        val deferredRunner = source.substringAfter("private val captureResultDiagnosticsRunnable = Runnable {")
            .substringBefore("// Edge-trigger for the active-override engage/disengage log.")
        val deferredProcessor = source.substringAfter(
            "private fun processDeferredCaptureResultDiagnostics(work: DeferredCaptureResultDiagnostics)"
        ).substringBefore("private fun processResult(")

        assertTrue(source.contains("private data class MeasuredColorCorrectionSnapshot("))
        assertTrue(enqueue.contains("captureSessionGeneration = captureSessionGeneration"))
        assertTrue(
            deferredRunner.contains(
                "work.captureSessionGeneration == sessionGeneration.get()"
            )
        )
        assertTrue(deferredProcessor.contains("sessionGeneration = work.captureSessionGeneration"))
        assertTrue(deferredProcessor.contains("frameNumber = result.frameNumber"))
        assertTrue(!deferredProcessor.contains("sessionGeneration = sessionGeneration.get()"))
        assertTrue(source.contains("latestMeasuredColorSnapshot = measuredColorSnapshot"))
        assertTrue(source.contains("measuredColorSnapshot.provenance.isNewerThan("))
        assertTrue(
            deferredProcessor.contains(
                "transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)"
            )
        )
        assertTrue(source.contains("it.provenance.isCurrent(activeCameraId, sessionGeneration.get())"))
        assertTrue(!source.contains("latestMeasuredColorTransform"))
        assertTrue(!source.contains("latestMeasuredColorGains"))
    }

    @Test
    fun `automatic drift hold preserves vendor color rendering`() {
        val override = source.substringAfter("private fun maybeApplyColorDriftOverride(")
            .substringBefore("@SuppressLint(\"NewApi\", \"InlinedApi\")")
        assertTrue(override.contains("builder[CaptureRequest.CONTROL_AWB_LOCK] = true"))
        assertTrue(override.contains("mode=AWB_LOCK(vendor_color_preserved)"))
        assertTrue(!override.contains("builder[CaptureRequest.COLOR_CORRECTION_TRANSFORM]"))
        assertTrue(!override.contains("builder[CaptureRequest.COLOR_CORRECTION_GAINS]"))
    }

    @Test
    fun `debug white balance evidence compares the submitted request with the exact frame result`() {
        val probe = source.substringAfter("private fun maybeLogWhiteBalanceResult(")
            .substringBefore("/** Verify that the HAL honored")
        assertTrue(probe.contains("if (!BuildConfig.DEBUG"))
        assertTrue(probe.contains("val total = result as? TotalCaptureResult ?: return"))
        assertTrue(probe.contains("val request = total.request"))
        assertTrue(probe.contains("captureSessionGeneration"))
        assertTrue(probe.contains("CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE"))
        assertTrue(probe.contains("CaptureResult.COLOR_CORRECTION_COLOR_TINT"))
        assertTrue(probe.contains("CaptureResult.COLOR_CORRECTION_GAINS"))
        assertTrue(probe.contains("CaptureResult.COLOR_CORRECTION_TRANSFORM"))
        assertTrue(probe.contains("WB_RESULT"))
    }
}
