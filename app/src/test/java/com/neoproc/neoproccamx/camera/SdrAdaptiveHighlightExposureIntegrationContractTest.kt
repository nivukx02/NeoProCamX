package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the live true-SDR highlight governor into the Camera2 request and session-continuity paths. */
class SdrAdaptiveHighlightExposureIntegrationContractTest {
    private val controller =
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    private val viewModel =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

    @Test
    fun `strict high end SDR requests metering but raw transports stay sensor neutral`() {
        val requestPolicy = viewModel
            .substringAfter("private fun analysisFeedRequested(): Boolean")
            .substringBefore("private fun syncPreviewAnalysisFeed")

        assertTrue(requestPolicy.contains("HighEndQualityPolicy.enabled"))
        assertTrue(requestPolicy.contains("HighEndQualityPolicy.strictHighEndOnly"))
        assertTrue(requestPolicy.contains("!_rawOn.value"))
        assertTrue(requestPolicy.contains("!isRawVideoModeActive()"))
    }

    @Test
    fun `valid SDR scene metrics drive only bounded highlight protection`() {
        val metricFlow = controller
            .substringAfter("override fun setHdrExposureSceneMetrics(")
            .substringBefore("private fun mutateControlState(")
        val sdrFlow = metricFlow
            .substringAfter("if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR && !sensorLinearNdrCaptureEnabled)")
            .substringBefore("if (!NdrAdaptiveHighlightFlag.enabled)")

        assertTrue(metricFlow.contains("if (!analysisValid) return"))
        assertTrue(sdrFlow.contains("SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops("))
        assertTrue(sdrFlow.contains("SdrAdaptiveHighlightExposurePolicy.smoothProtectionStops("))
        assertTrue(sdrFlow.contains("ExposureCompensationHysteresisPolicy.resolveIndex("))
        assertTrue(
            sdrFlow.contains(
                "if (smoothedIndex != lastRequestedSdrExposureCompensationIndex) {\n" +
                    "                updatePreviewFromAutonomousControlFeedback()"
            )
        )
        val autonomousRefresh = controller
            .substringAfter("private fun updatePreviewFromAutonomousControlFeedback(")
            .substringBefore("private fun releaseStaleHdrExposureCorrection(")
        assertTrue(autonomousRefresh.contains("updatePreview(immediate = immediate)"))
        assertFalse(sdrFlow.contains("roundToInt()"))
        assertFalse(sdrFlow.contains("NdrAdaptiveHighlightExposurePolicy"))
    }

    @Test
    fun `SDR protection reaches AE before tone mapping and never touches raw sensor requests`() {
        val requestAssembly = controller
            .substringAfter("val hdrHighlightProtectActive =")
            .substringBefore("// NDR10+/NIVUKX motion-blur elimination")

        assertTrue(requestAssembly.contains("!rawSensorRequestActive && sdrAdaptiveHighlightExposureEligible()"))
        assertTrue(requestAssembly.contains("SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops("))
        assertTrue(requestAssembly.contains("ExposureCompensationHysteresisPolicy.resolveIndex("))
        assertTrue(requestAssembly.contains("biasStops = adaptiveProtection"))
        assertTrue(requestAssembly.contains("builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION]"))
        assertTrue(requestAssembly.contains("lastRequestedSdrExposureCompensationIndex"))
    }

    @Test
    fun `SDR NDR transitions retain a shared analysis surface without duplicate rebuilds`() {
        val dynamicRangeSync = controller
            .substringAfter("override fun setHdrDynamicRangeMode(")
            .substringBefore("override fun describeHdrDynamicRangeState()")
        val analysisSync = controller
            .substringAfter("override fun setPreviewAnalysisRequested(enabled: Boolean)")
            .substringBefore("override fun setRecordingReadoutOverrideSize")

        assertTrue(dynamicRangeSync.contains("ndrExposureAnalysisRequested || clientPreviewAnalysisRequested"))
        assertTrue(dynamicRangeSync.contains("val analysisChanged = previewAnalysisRequested != effectiveAnalysisRequested"))
        assertTrue(dynamicRangeSync.contains("HdrDynamicRangeSessionMutationPolicy.resolve("))
        assertTrue(dynamicRangeSync.contains("rebuildReaders = analysisChanged"))
        assertTrue(dynamicRangeSync.contains("forceRebuild = analysisChanged"))
        assertTrue(dynamicRangeSync.contains("sessionMutation.retargetRepeatingRequest"))
        assertTrue(analysisSync.contains("enabled || (ndrExposureGovernorEnabled && !sensorLinearNdrCaptureEnabled)"))
        assertTrue(analysisSync.contains("if (previewAnalysisRequested == effectiveRequested) return"))
    }
}
