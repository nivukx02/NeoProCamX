package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManualControlsLiveTelemetryContractTest {
    private val source: String
        get() = File("src/main/java/com/neoproc/neoproccamx/ui/controls/ManualControlsFragment.kt")
            .readText()
            .replace("\r\n", "\n")

    @Test
    fun liveCaptureResultsAreVisibilityGatedAndSampledAtTenHertz() {
        val observer = source
            .substringAfter("private fun observeLive()")
            .substringBefore("private fun renderLiveResult")

        assertTrue(source.contains("private const val LIVE_TELEMETRY_SAMPLE_MS = 100L"))
        assertTrue(observer.contains("vm.manualControlsVisibleFlow.collectLatest"))
        assertTrue(observer.contains("if (!visible) return@collectLatest"))
        assertTrue(observer.contains(".sample(LIVE_TELEMETRY_SAMPLE_MS)"))
        assertFalse(observer.contains("vm.liveResult.collectLatest"))
    }

    @Test
    fun liveRenderingDiffsTextProgressAndDrawableStateBeforeMutation() {
        val renderer = source
            .substringAfter("private fun renderLiveResult")
            .substringBefore("private fun refreshIsoSliderRange")

        assertFalse(renderer.contains(".text ="))
        assertFalse(renderer.contains(".progress ="))
        assertTrue(renderer.contains(".setTextIfChanged("))
        assertTrue(renderer.contains(".setProgressIfChanged("))
        assertTrue(source.contains("if (!TextUtils.equals(text, value)) text = value"))
        assertTrue(source.contains("if (progress != value) progress = value"))
        assertTrue(source.contains("if (isSelected != value) isSelected = value"))
        assertTrue(source.contains("if (isActivated != value) isActivated = value"))
    }

    @Test
    fun liveTelemetryUpdatesOnlyTheVisibleAdjustmentPanelAndReplaysOnOpen() {
        val renderer = source
            .substringAfter("private fun renderLiveResult")
            .substringBefore("private fun refreshIsoSliderRange")
        val selector = source
            .substringAfter("private fun selectTab")
            .substringBefore("private fun hideAdjustmentPanel")

        assertTrue(source.contains("private var latestLiveResult: LiveResult? = null"))
        assertTrue(renderer.contains("latestLiveResult = result"))
        assertTrue(renderer.contains("if (!controlStack.isVisible) return"))
        assertTrue(renderer.contains("panelISO.isVisible ->"))
        assertTrue(renderer.contains("panelSS.isVisible ->"))
        assertTrue(renderer.contains("panelWB.isVisible ->"))
        assertTrue(renderer.contains("panelMF.isVisible ->"))
        assertTrue(renderer.contains("panelEV.isVisible ->"))
        assertTrue(selector.contains("latestLiveResult?.let(::renderLiveResult)"))
    }

    @Test
    fun capabilityDerivedListsAreCachedBeforeTheyAreRebuilt() {
        val shutterRefresh = source
            .substringAfter("private fun refreshShutterSliderRange()")
            .substringBefore("private fun refreshWhiteBalanceSliderRange()")
        val cacheGuard = shutterRefresh.indexOf(
            "if (exposureRange == supportedShutterExposureRange && fps == supportedShutterFps) return"
        )
        val rebuild = shutterRefresh.indexOf("CinemaManualControlPolicy.shutterAngleEntries(")

        assertTrue(cacheGuard >= 0)
        assertTrue(rebuild > cacheGuard)
        assertTrue(source.contains("if (range == supportedIsoRange) return"))
        assertTrue(source.contains("if (range == supportedWhiteBalanceHardwareRange) return"))
        val bindViews = source
            .substringAfter("private fun bindViews(root: View)")
            .substringBefore("private fun bindListeners()")
        assertTrue(bindViews.contains("supportedIsoRange = null"))
        assertTrue(bindViews.contains("supportedShutterExposureRange = null"))
        assertTrue(bindViews.contains("supportedWhiteBalanceHardwareRange = null"))
    }
}
