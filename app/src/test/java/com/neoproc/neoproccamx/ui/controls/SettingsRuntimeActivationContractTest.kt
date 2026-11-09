package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRuntimeActivationContractTest {

    @Test
    fun previewAspectUsesImmediatePresentationWithoutSessionReconfiguration() {
        val metadata = SettingsOptionMetaCatalog.all.single { it.id == "camera.previewAspect" }
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val applyAspect = viewModel
            .substringAfter("private fun applyPreviewAspectRatio(")
            .substringBefore("fun setDeviceRotationDegrees")

        assertTrue(metadata.activationClass == SettingsActivationClass.IMMEDIATE_STATE)
        assertTrue(applyAspect.contains("syncPreviewAspectPresentationTargets()"))
        assertFalse(applyAspect.contains("pushVulkanPreviewRenderConfig("))
        assertFalse(applyAspect.contains("refreshPreviewPipelineIfNeeded("))
    }

    @Test
    fun customLutImporterIsReachableFromVideoColorSettings() {
        val factory = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        ).readText()
        val colorSection = factory
            .substringAfter("private fun buildColorSection")
            .substringBefore("private fun buildFocusSection")

        assertTrue(colorSection.contains("uiFactory.actionRow("))
        assertTrue(colorSection.contains("title = \"Custom LUT\""))
        assertTrue(colorSection.contains("onClick = openLutImporter"))
    }

    @Test
    fun customLutReplacementIsValidatedBeforeAtomicInstall() {
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val installer = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CustomLutInstaller.kt"
        ).readText()
        val importFlow = activity
            .substringAfter("private val lutImportLauncher")
            .substringBefore("override fun onCreate")

        assertTrue(importFlow.contains("CustomLutInstaller.install(contentResolver, uri, filesDir)"))
        assertTrue(installer.contains("validateCubeLUTBlocking(staged)"))
        assertTrue(installer.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(installer.contains("ColorScience.MAX_CUSTOM_LUT_FILE_BYTES"))
        assertFalse(installer.contains("target.outputStream()"))
    }

    @Test
    fun stabilizationOptionsAreFilteredByActiveCameraCapabilities() {
        val factory = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        ).readText()
        val systemSection = factory
            .substringAfter("private fun buildAdvancedSection")
            .substringBefore("companion object")

        assertTrue(systemSection.contains("stabilizationChoicesForCapabilities("))
        assertTrue(systemSection.contains("selectedStabilizationForCapabilities("))
        assertTrue(systemSection.contains("supportsOis = viewModel.camera.supportsOIS"))
        assertTrue(
            systemSection.contains(
                "supportsVideoStabilization = viewModel.camera.supportsVideoStabilization"
            )
        )
        assertTrue(
            systemSection.contains(
                "supportsPreviewStabilization = viewModel.camera.supportsPreviewStabilization"
            )
        )
        assertTrue(systemSection.contains("requestSettingsRebuild()"))
        assertTrue(systemSection.contains("SensorLinearNdrModePolicy.isActive("))
        assertTrue(systemSection.contains("rawVideoCaptureEnabled = rawSensorCaptureEnabled"))
    }

    @Test
    fun lensSwitchRebuildsCapabilityFilteredSettings() {
        val factory = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        ).readText()
        val lensRow = factory
            .substringAfter("title = \"Lens\"")
            .substringBefore("title = \"Flash\"")

        assertTrue(lensRow.contains("viewModel.switchFacing()"))
        assertTrue(lensRow.contains("requestSettingsRebuild()"))
        assertTrue(lensRow.contains("viewModel.camera.facing != lensFacing"))
        assertFalse(lensRow.contains("viewModel.frameGeometry.value?.lensFacing != lensFacing"))

        val imageSection = factory
            .substringAfter("private fun buildImageSection")
            .substringBefore("private fun buildVideoSection")
        assertTrue(imageSection.contains("val currentFacing = viewModel.camera.facing"))
        assertFalse(imageSection.contains("viewModel.frameGeometry.value?.lensFacing ?:"))

        val fragment = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt"
        ).readText()
        assertTrue(fragment.contains("viewModel.frameGeometry.collect"))
        assertTrue(fragment.contains("viewModel.videoHdrMode.collect"))
        assertTrue(fragment.contains("rebuildForCapabilityChangeIfNeeded()"))
        assertTrue(fragment.contains("supportsPreviewStabilization"))
    }

    @Test
    fun aiMotionCompensationIsAnImmediateConsumedFlowNotADeadStore() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val pipeline = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").readText()
        val renderCombine = viewModel
            .substringAfter("combine(\n                sceneAnalysis")
            .substringBefore(".distinctUntilChanged()")

        assertTrue(pipeline.contains("val aiMotionCompensationEnabled: StateFlow<Boolean>"))
        assertTrue(pipeline.contains("_aiMotionCompensationEnabled.value = enabled"))
        assertTrue(renderCombine.contains("pipeline.aiMotionCompensationEnabled"))
        assertTrue(renderCombine.contains("aiMotionCompensationEnabled = values[16] as? Boolean ?: false"))
    }

    @Test
    fun explicitNoiseReductionOffRemainsAuthoritativeInEveryProcessedRequestLayer() {
        val cinePolicy = File(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraCineProcessingPolicy.kt"
        ).readText()
        val processedPolicy = File(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraProcessedVideoQualityRequestPolicy.kt"
        ).readText()

        assertFalse(cinePolicy.contains("noiseReductionEnabled || strictHighEndEnabled()"))
        assertFalse(cinePolicy.contains("@Suppress(\"UNUSED_PARAMETER\") noiseReductionEnabled"))
        assertTrue(cinePolicy.countOccurrences("noiseReductionMode = if (noiseReductionEnabled)") >= 3)
        assertTrue(processedPolicy.contains("noiseReductionMode = if (!noiseReductionEnabled)"))
    }

    @Test
    fun eyeAutofocusDoesNotEraseFacePreferenceAndSurvivesHighLoadWhenExplicitlyEnabled() {
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val loadPolicy = File("src/main/java/com/neoproc/neoproccamx/ui/RealtimeAiLoadPolicy.kt").readText()
        val setter = viewModel
            .substringAfter("fun setEyeAutofocusEnabled(enabled: Boolean)")
            .substringBefore("fun setAiEnhancementEnabled")
        val highLoad = loadPolicy
            .substringAfter("if (recordingOrHighLoad)")
            .substringBefore("val realtimeAiAssistEnabled")

        assertFalse(setter.contains("prefs.faceDetectionEnabled = false"))
        assertTrue(setter.contains("settingsController.setEyeAutofocusEnabled(enabled)"))
        assertTrue(setter.contains("applyAiTrackingPreferences()"))
        assertTrue(highLoad.contains("faceDetectionEnabled = faceTrackingEnabled"))
        assertTrue(highLoad.contains("eyeTrackingEnabled = eyeTrackingEnabled"))
        assertTrue(highLoad.contains("enhancementEnabled = false"))
    }

    @Test
    fun manualBitrateSliderContainsTheProductionDefaultExactly() {
        assertTrue(SettingsOptionCatalog.bitrateStepsMbps.contains(240))
        assertTrue(SettingsOptionCatalog.bitrateStepsMbps.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun customRecordingNameUpdatesItsDependentRowWithoutRebuildingTheSettingsPanel() {
        val factory = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        ).readText()
        val customNameRows = factory
            .substringAfter("val defaultRecordingName")
            .substringBefore("card.addView(uiFactory.infoRow(\"Save Location\"")

        assertTrue(customNameRows.contains("val recordingNameRow = uiFactory.textInputRow("))
        assertTrue(customNameRows.contains("recordingNameRow.visibility = if (enabled)"))
        assertTrue(customNameRows.contains("onValue = viewModel::setCustomRecordingName"))
        assertFalse(customNameRows.contains("requestSettingsRebuild()"))
    }

    @Test
    fun framingGuidesAreRecomputedWhenThePreviewSurfaceLayoutReturns() {
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val layoutListener = activity
            .substringAfter("binding.glSurface.addOnLayoutChangeListener")
            .substringBefore("private fun setupControls")
        val overlay = File(
            "src/main/java/com/neoproc/neoproccamx/ui/overlays/UIOverlay.kt"
        ).readText()

        assertTrue(layoutListener.contains("syncOverlayPreviewArea()"))
        assertTrue(layoutListener.contains("vm.refreshGeometry()"))
        assertTrue(layoutListener.contains("refreshSafeAreaOverlays()"))
        assertTrue(overlay.contains("fun hasActiveSafeAreaGuides(): Boolean"))
    }

    @Test
    fun sameCameraReopenRepublishesGeometryClearedByCameraClose() {
        val controller = File(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        ).readText()
        val targetAspectSetter = controller
            .substringAfter("override var targetOutputAspectRatio: Float? = null")
            .substringBefore("@set:JvmName(\"applyDeviceRotationDegreesField\")")

        assertTrue(targetAspectSetter.contains("targetUnchanged"))
        assertTrue(targetAspectSetter.contains("frameGeometryFlow.value != null"))
        assertTrue(targetAspectSetter.contains("refreshFrameGeometry()"))
    }

    @Test
    fun flashRowTracksTheAuthoritativeAsynchronousCameraControlState() {
        val factory = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        ).readText()
        val fragment = File(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt"
        ).readText()

        assertTrue(factory.contains("valueTag = FLASH_VALUE_TAG"))
        assertTrue(fragment.contains("viewModel.cameraParams.collect"))
        assertTrue(fragment.contains("SettingsSectionFactory.FLASH_VALUE_TAG"))
    }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length, step = 1).count { it == token }
}
