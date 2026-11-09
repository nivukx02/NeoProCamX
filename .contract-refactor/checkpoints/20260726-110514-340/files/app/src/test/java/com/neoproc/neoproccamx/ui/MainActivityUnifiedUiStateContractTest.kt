package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MainActivityUnifiedUiStateContractTest {
    // Normalize CRLF -> LF so the multi-line source assertions are robust on Windows working trees
    // (git checks the files out with CRLF; File.readText keeps them, which broke the \n-based asserts).
    private fun read(path: String): String = File(path).readText().replace("\r\n", "\n")

    private val source = read("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
    private val layout = read("src/main/res/layout/activity_main.xml")
    private val theme = read("src/main/res/values/themes.xml")
    private val dimensions = read("src/main/res/values/dimens.xml")
    private val colors = read("src/main/res/values/colors.xml")
    private val styles = read("src/main/res/values/styles.xml")
    private val bottomCapturePanel = read("src/main/res/drawable/bg_bottom_capture_panel.xml")
    private val bottomRoundButton = read("src/main/res/drawable/bg_bottom_round_button.xml")
    private val previewHudChip = read("src/main/res/drawable/bg_preview_hud_chip.xml")
    private val photoCaptureIcon = read("src/main/res/drawable/ic_photo_capture.xml")
    private val settingsIcon = read("src/main/res/drawable/ic_glass_settings.xml")
    private val settingsSectionFactory = read("src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt")

    @Test
    fun mainActivityHasSingleCompositeRenderEntryPointWithDiffing() {
        assertTrue(source.contains("private var lastState = CameraUiState()"))
        assertTrue(source.contains("private fun renderState(prev: CameraUiState, next: CameraUiState)"))
        assertTrue(source.contains("vm.uiState.collect { nextState ->"))
        assertTrue(source.contains("renderState(lastState, nextState)"))
        assertTrue(source.contains("CameraUiStateDiffPolicy.booleanChanged(prev.rawEnabled, next.rawEnabled)"))
        assertTrue(source.contains("CameraUiStateDiffPolicy.floatChanged(prev.peakingIntensity, next.peakingIntensity)"))
    }

    @Test
    fun primaryButtonsDispatchActionsInsteadOfDoingSynchronousViewModelWork() {
        assertTrue(source.contains("vm.dispatch(UiAction.ToggleRaw)"))
        assertTrue(source.contains("vm.dispatch(UiAction.TogglePeaking)"))
        assertTrue(source.contains("vm.dispatch(UiAction.ToggleRecording)"))
        assertTrue(source.contains("vm.dispatch(UiAction.PauseRecording)"))
        assertTrue(source.contains("vm.dispatch(UiAction.ResumeRecording)"))
    }

    @Test
    fun legacyUiStateCollectorsAreRemovedFromMainActivity() {
        listOf(
            "vm.showHistogramFlow.collect",
            "vm.showWaveformFlow.collect",
            "vm.showZebraFlow.collect",
            "vm.zebraThreshold.collect",
            "vm.peakingEnabledFlow.collect",
            "vm.peakingIntensityFlow.collect",
            "vm.gridMode.collect",
            "vm.previewAspect.collect",
            "vm.videoHdrEnabledFlow.collect",
            "vm.gpuRecordState.collect"
        ).forEach { legacyCollector ->
            assertFalse(
                "$legacyCollector duplicates CameraUiState rendering and can cause layout thrash",
                source.contains(legacyCollector)
            )
        }
    }

    @Test
    fun highFrequencyTelemetryCollectorsAreVsyncGatedInsteadOfTimeSampled() {
        listOf(
            "vm.zebraMask.sample(",
            "vm.subjectMask.sample(",
            "vm.peakingMask.sample(",
            "vm.liveResult.sample(",
            "vm.cameraParams.sample(",
            "vm.histogram.sample(",
            "vm.waveformColumns.sample(",
            "vm.faces.sample("
        ).forEach { sampledFlow ->
            assertFalse(
                "$sampledFlow uses fixed time sampling instead of display-paced VSync gating",
                source.contains(sampledFlow)
            )
        }

        assertTrue(source.contains("pendingZebraMaskTelemetry"))
        assertTrue(source.contains("pendingSubjectMaskTelemetry"))
        assertTrue(source.contains("pendingPeakingMaskTelemetry"))
        assertTrue(source.contains("pendingFacesTelemetry"))
        assertTrue(source.contains("telemetryVsyncGate.requestFrame()"))
    }

    @Test
    fun previewFpsUsesDedicatedCaptureTimestampStreamNotConflatedLiveResult() {
        assertTrue(source.contains("vm.previewFrameTimestamps.collect { timestampNs ->"))
        assertTrue(source.contains("binding.glSurface.notifyFramePresented(timestampNs)"))

        val liveResultCollector = source
            .substringAfter("vm.liveResult.collect { result ->")
            .substringBefore("vm.cameraParams.collect")
        assertFalse(liveResultCollector.contains("notifyFramePresented"))
    }

    @Test
    fun recordingStartResetsPreviewFpsTrackerBeforeMeasuringRecordingPreview() {
        val viewModelSource = read("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val startBlock = viewModelSource
            .substringAfter("gpuRecording.setRealtimeSurfaceCadenceEnabled(")
            .substringBefore("val vulkanImportStatsAtStart")

        assertTrue(startBlock.contains("updatePreviewViewAwait { resetPreviewFpsTracking() }"))
    }

    @Test
    fun videoModeRailIsHiddenForTheCompleteRecordingLifecycle() {
        assertTrue(layout.contains("""android:id="@+id/modeGroup""""))
        assertTrue(layout.contains("""android:clipChildren="false""""))
        val captureDockTag = layout.substringAfter("""android:id="@+id/bottomBar"""")
            .substringBefore(">")
        assertTrue(captureDockTag.contains("""app:layout_constraintBottom_toBottomOf="parent""""))
        assertFalse(captureDockTag.contains("""app:layout_constraintTop_toTopOf="parent""""))
        assertFalse(captureDockTag.contains("""app:layout_constraintVertical_bias"""))
        assertTrue(source.contains("binding.modeGroup.visibility = if (visible) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("next.gpuRecordState is GpuRecordingState.Finalizing"))
        assertTrue(source.contains("binding.modeRailScroll.isEnabled = visible"))
        assertFalse(source.contains("animatePanelVisibility(\n            binding.modeGroup"))
    }

    @Test
    fun referenceCameraDockUsesApprovedBottomGeometryAndGlassBorder() {
        val captureDockTag = layout.substringAfter("""android:id="@+id/bottomBar"""")
            .substringBefore(">")
        val captureRowTag = layout.substringAfter("""android:id="@+id/captureControlsRow"""")
            .substringBefore(">")

        assertTrue(captureDockTag.contains("""android:layout_width="@dimen/camera_dock_panel_width""""))
        assertTrue(captureDockTag.contains("""android:layout_marginBottom="@dimen/camera_dock_bottom_margin""""))
        assertTrue(captureRowTag.contains("""android:layout_height="@dimen/camera_dock_panel_height""""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_panel_width">280dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_panel_height">86dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_panel_radius">43dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_bottom_margin">76dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_button">50dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_icon_padding">6dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="shutter_outer_size">68dp</dimen>"""))
        assertTrue(bottomCapturePanel.contains("""android:radius="@dimen/camera_dock_panel_radius""""))
        assertTrue(bottomCapturePanel.contains("""android:width="1dp""""))
        assertFalse(bottomCapturePanel.contains("""android:width="2dp""""))
        assertTrue(bottomRoundButton.contains("""android:width="1dp""""))
        assertFalse(bottomRoundButton.contains("""android:width="1.5dp""""))
    }

    @Test
    fun referenceModePillUsesWhiteVideoTextAtRuntime() {
        assertTrue(styles.contains("""<item name="android:textColor">#FFFFFFFF</item>"""))
        assertTrue(source.contains("val selectedColor = ContextCompat.getColor(this, R.color.text_primary)"))
        assertFalse(source.contains("val selectedColor = ContextCompat.getColor(this, R.color.pure_black)"))
    }

    @Test
    fun referenceDockUsesDedicatedPhotoCaptureAndSettingsOnlyLensSwitching() {
        assertTrue(layout.contains("""android:id="@+id/btnPhotoCapture""""))
        assertTrue(layout.contains("""android:src="@drawable/ic_photo_capture""""))
        assertTrue(layout.contains("""android:src="@drawable/ic_glass_settings""""))
        assertFalse(layout.contains("btnFlipCamera"))
        assertFalse(layout.contains("btnTopFlipCamera"))
        assertTrue(source.contains("binding.btnPhotoCapture.imageTintList = null"))
        assertTrue(source.contains("binding.btnModePro.imageTintList = null"))
        assertTrue(photoCaptureIcon.contains("Dedicated video-snapshot icon"))
        assertTrue(settingsIcon.contains("Reference-style cog with dial ring"))
        assertTrue(photoCaptureIcon.contains("""android:width="38dp""""))
        assertTrue(settingsIcon.contains("""android:width="38dp""""))
        assertTrue(photoCaptureIcon.contains("""android:viewportWidth="64""""))
        assertTrue(settingsIcon.contains("""android:viewportWidth="64""""))
        assertTrue(settingsSectionFactory.contains("title = \"Lens\""))
        assertTrue(settingsSectionFactory.contains("viewModel.switchFacing()"))
    }

    @Test
    fun referenceCameraSurfaceRestoresSensorSizeRecordingSizeAndAspectChip() {
        val topStatusTag = layout.substringAfter("""android:id="@+id/topStatusPanel"""")
            .substringBefore(">")
        val topStatusTextTag = layout.substringAfter("""android:id="@+id/tvStatusPrimary"""")
            .substringBefore("/>")

        assertTrue(topStatusTag.contains("""android:layout_height="@dimen/top_sensor_dock_height""""))
        assertTrue(topStatusTag.contains("""android:minWidth="@dimen/top_sensor_dock_min_width""""))
        assertTrue(topStatusTag.contains("""android:layout_marginTop="@dimen/top_sensor_dock_margin_top""""))
        assertTrue(topStatusTag.contains("""android:paddingHorizontal="@dimen/top_sensor_dock_padding_horizontal""""))
        assertTrue(topStatusTextTag.contains("""android:textSize="11sp""""))
        assertTrue(layout.contains("""android:background="@drawable/bg_preview_hud_chip""""))
        assertTrue(dimensions.contains("""<dimen name="top_sensor_dock_height">28dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="top_sensor_dock_min_width">224dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="top_sensor_dock_margin_top">16dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="top_sensor_dock_padding_horizontal">8dp</dimen>"""))
        assertTrue(previewHudChip.contains("""@color/glass_frost_top"""))
        assertTrue(previewHudChip.contains("""@color/glass_frost_bottom"""))
        assertTrue(previewHudChip.contains("""@color/glass_border"""))
        assertTrue(previewHudChip.contains("""android:width="1dp""""))
        assertTrue(source.contains("animatePanelVisibility(\n            binding.topStatusPanel,\n            true"))
        assertFalse(source.contains("binding.topStatusPanel.visibility = View.GONE"))
        assertTrue(source.contains("binding.topWarningPanel.visibility = View.GONE"))
    }

    @Test
    fun cameraDocksAnchorToResolvedPreviewBoundsWithApprovedSpacing() {
        assertTrue(dimensions.contains("""<dimen name="sensor_dock_preview_gap">8dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="mode_rail_preview_gap">24dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_mode_gap">8dp</dimen>"""))
        assertTrue(dimensions.contains("""<dimen name="camera_dock_lower_stack_lift">24dp</dimen>"""))
        assertTrue(source.contains("PreviewContentBoundsResolver.resolve("))
        assertTrue(source.contains("CameraDockLayoutPolicy.resolveAnchoredTranslations("))
        assertTrue(source.contains("binding.topStatusPanel.translationY = plan.statusTranslationY"))
        assertTrue(source.contains("binding.modeGroup.translationY = plan.modeTranslationY"))
        assertTrue(source.contains("binding.bottomBar.translationY = plan.captureTranslationY"))
    }

    @Test
    fun modeAndShutterDocksAnchorToFixedFourThreeBottomAcrossAspects() {
        // The mode + shutter docks must hold their 4:3 position in 16:9 (not slide to the bottom),
        // so their anchor comes from a fixed 4:3 preview rectangle clamped to the actual bottom.
        assertTrue(source.contains("val fourThreeAnchorAspect = if (targetAspect < 1f) 3f / 4f else 4f / 3f"))
        assertTrue(source.contains("targetAspect = fourThreeAnchorAspect"))
        assertTrue(source.contains("val dockAnchorBottomPx = minOf("))
        assertTrue(source.contains("previewBottomPx = dockAnchorBottomPx"))
        // Lower stack lifted upward off the 4:3 bottom, and the sensor dock seated inside the preview.
        assertTrue(source.contains("- lowerStackLiftPx"))
        assertTrue(source.contains("statusPreviewOverlapPx = statusOutsidePreviewPx"))
    }

    @Test
    fun referenceGlassDocksUseStrongerFrostedBlurFillWithFlatBorder() {
        assertTrue(colors.contains("""<color name="glass_frost_top">#5AFFFFFF</color>"""))
        assertTrue(colors.contains("""<color name="glass_frost_bottom">#40FFFFFF</color>"""))
        assertTrue(colors.contains("""<color name="glass_button_top">#5CFFFFFF</color>"""))
        assertTrue(colors.contains("""<color name="glass_button_bottom">#44FFFFFF</color>"""))
        assertTrue(colors.contains("""<color name="glass_border">#4AFFFFFF</color>"""))
        assertTrue(bottomCapturePanel.contains("""android:width="1dp""""))
        assertTrue(previewHudChip.contains("""android:width="1dp""""))
        assertTrue(bottomRoundButton.contains("""android:width="1dp""""))
    }

    @Test
    fun videoModeRailStaysAsTransparentFloatingPillWithoutAnExtraGlassPanel() {
        // The video-mode rail is a floating text pill, not a paneled dock — no glass background,
        // so it doesn't read as an extra dock stacked above the capture panel.
        assertTrue(layout.contains("""android:id="@+id/modeGroup""""))
        assertTrue(layout.contains("""android:background="@android:color/transparent""""))
    }

    @Test
    fun zoomReadoutIsPlainTextAboveAndMovesWithTheModeRail() {
        val zoom = layout.substringAfter("""android:id="@+id/btnZoomToggle"""")
            .substringBefore("/>")
        assertFalse(layout.contains("tvZoomTransient"))
        assertTrue(zoom.contains("android:visibility=\"gone\""))
        assertTrue(zoom.contains("android:clickable=\"false\""))
        assertTrue(zoom.contains("android:focusable=\"false\""))
        assertTrue(zoom.contains("""android:background="@null""""))
        assertTrue(zoom.contains("""app:layout_constraintBottom_toTopOf="@id/modeGroup""""))
        assertFalse(zoom.contains("bg_glass_panel"))
        assertFalse(source.contains("binding.tvZoomTransient"))
        val transientZoom = source.substringAfter("private fun showTransientZoom(zoom: Float)")
            .substringBefore("private fun updateZoomToggleLabel")
        assertTrue(transientZoom.contains("binding.btnZoomToggle.visibility = View.VISIBLE"))
        assertTrue(transientZoom.contains("binding.btnZoomToggle.visibility = View.GONE"))
        assertTrue(source.contains("binding.btnZoomToggle.translationY = plan.modeTranslationY"))
        assertTrue(source.contains("binding.btnZoomToggle.translationY = binding.modeGroup.translationY"))
    }

    @Test
    fun videoModeSelectorKeepsOnlyActivePresetVisibleAndSwipeSelectable() {
        val selectorLayout = layout.substringAfter("""android:id="@+id/modeRailContent"""")
            .substringBefore("</FrameLayout>")
        assertFalse(selectorLayout.contains("""android:orientation="horizontal""""))
        assertTrue(selectorLayout.contains("""android:layout_gravity="center""""))
        assertTrue(selectorLayout.contains("""android:visibility="gone""""))
        assertTrue(source.contains("binding.btnModeRawPreset.text = getString(R.string.control_raw)"))
        assertTrue(source.contains("private fun installModeRotarySwipeControl()"))
        assertTrue(source.contains("CameraModeRotarySwipePolicy.resolveStep("))
        val renderSelection = source.substringAfter("private fun renderPresetSelection()")
            .substringBefore("private fun setMode")
        assertTrue(renderSelection.contains("view.visibility = if (selected) View.VISIBLE else View.GONE"))
        assertFalse(renderSelection.contains("view.visibility = View.VISIBLE"))
        assertTrue(source.contains("postModePresetActivation(preset)"))
    }

    @Test
    fun modeRailTouchDispatchDoesNotRunHeavyModeSwitchWorkInsideMotionEvent() {
        val touchHandler = source.substringAfter("private fun installModeRotarySwipeControl()")
            .substringBefore("private fun postModePresetShift")
        assertTrue(touchHandler.contains("postModePresetShift(step)"))
        assertTrue(touchHandler.contains("postModePresetActivation(preset)"))
        assertFalse(touchHandler.contains("shiftModePreset(step)"))
        assertFalse(touchHandler.contains("activateModePreset(preset)"))
    }

    @Test
    fun startupModeRailSyncDoesNotActivateDefaultVideoAndClearRestoredHdr() {
        val deferredStartupBody = source.substringAfter("// DEFERRED - truly non-critical")
            .substringBefore("// VERIFY MAXIMUM FIDELITY")

        assertFalse(
            "Deferred UI setup must not activate the default VIDEO preset; activating it calls " +
                "setVideoHdrMode(\"OFF\") and downgrades restored NDR/NIVUKX to SDR.",
            deferredStartupBody.contains("activateModePreset(CameraModePresetOrderPolicy.defaultPreset())")
        )
        assertTrue(
            "Deferred UI setup should passively sync the rail to the restored ViewModel state.",
            deferredStartupBody.contains("syncPresetFromState()")
        )
    }

    @Test
    fun normalSdrModePresetsDoNotOverrideManualColorSelection() {
        val activation = source.substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun updatePresetTapTargets")
        val videoBlock = activation.substringAfter("CameraModePreset.VIDEO ->")
            .substringBefore("CameraModePreset.PRO ->")
        val proBlock = activation.substringAfter("CameraModePreset.PRO ->")
            .substringBefore("CameraModePreset.HDR ->")

        assertFalse(
            "VIDEO preset must leave color space under Settings/manual control",
            videoBlock.contains("setVideoColorSpace(")
        )
        assertFalse(
            "PRO preset must leave color space under Settings/manual control",
            proBlock.contains("setVideoColorSpace(")
        )
        assertFalse(
            "VIDEO preset must not force color science/profile defaults",
            videoBlock.contains("setColorProfile(")
        )
        assertFalse(
            "PRO preset must not force color science/profile defaults",
            proBlock.contains("setColorProfile(")
        )
    }

    @Test
    fun normalAndProSdrPresetsDispatchDistinctQualityIntentWithoutChangingAppMode() {
        val activation = source.substringAfter("private fun activateModePreset(preset: CameraModePreset)")
            .substringBefore("private fun updatePresetTapTargets")
        val videoBlock = activation.substringAfter("CameraModePreset.VIDEO ->")
            .substringBefore("CameraModePreset.PRO ->")
        val proBlock = activation.substringAfter("CameraModePreset.PRO ->")
            .substringBefore("CameraModePreset.HDR ->")

        assertTrue(source.contains("import com.neoproc.neoproccamx.color.SdrQualityIntent"))
        assertTrue(videoBlock.contains("vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)"))
        assertTrue(proBlock.contains("vm.setSdrQualityIntent(SdrQualityIntent.PRO)"))
        assertTrue(videoBlock.contains("setMode(AppMode.VIDEO)"))
        assertTrue(proBlock.contains("setMode(AppMode.VIDEO)"))
    }

    @Test
    fun previewGesturesDoNotRotateRecordingModes() {
        val gestures = source.substringAfter("private fun setupGestures()")
            .substringBefore("private fun installPreviewTouchListener")
        assertFalse(gestures.contains("shiftModePreset("))
    }

    @Test
    fun cameraViewportBackingIsPureBlackForUnusedAspectSpace() {
        val rootTag = layout.substringAfter("<androidx.constraintlayout.widget.ConstraintLayout")
            .substringBefore(">")
        val previewTag = layout.substringAfter("<com.neoproc.neoproccamx.rendering.CameraVulkanPreviewView")
            .substringBefore("/>")
        assertTrue(rootTag.contains("""android:id="@+id/rootLayout""""))
        assertTrue(rootTag.contains("""android:background="@color/pure_black""""))
        assertFalse(
            "Opaque SurfaceView background masks live camera frames",
            previewTag.contains("""android:background="@color/pure_black"""")
        )
        assertTrue(theme.contains("""<item name="android:windowBackground">@color/pure_black</item>"""))
    }
}
