package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.ui.overlays.GridMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsDomainState(
    val gridMode: GridMode = GridMode.NONE,
    val showHistogram: Boolean = false,
    val showWaveform: Boolean = false,
    val showZebra: Boolean = false,
    val zebraThreshold: Float = 0.88f,
    val peakingEnabled: Boolean = false,
    val peakingIntensity: Float = 0.55f,
    val aiEnhancement: Boolean = false,
    val aiMotionCompensation: Boolean = false,
    val uncompressedCinemaPipeline: Boolean = false,
    val videoDetailProfile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
    val manualControlsVisible: Boolean = false
)

internal class CameraSettingsController(
    private val cameraController: CameraController,
    private val pipelineManager: FramePipelineManager,
    private val prefs: PreferencesManager
) {
    private val _domainState = MutableStateFlow(
        SettingsDomainState(
            gridMode = runCatching { GridMode.valueOf(prefs.gridMode) }.getOrDefault(GridMode.NONE),
            showHistogram = prefs.histogramVisible,
            showWaveform = prefs.waveformVisible,
            showZebra = prefs.zebraVisible,
            zebraThreshold = prefs.zebraThreshold,
            peakingEnabled = prefs.focusPeakingEnabled,
            peakingIntensity = prefs.focusPeakingIntensity,
            aiEnhancement = prefs.aiEnhancementEnabled,
            aiMotionCompensation = prefs.aiMotionCompensationEnabled,
            uncompressedCinemaPipeline = prefs.uncompressedCinemaPipelineEnabled,
            videoDetailProfile = prefs.videoDetailProfile
        )
    )
    val domainState: StateFlow<SettingsDomainState> = _domainState.asStateFlow()

    init {
        applyPipelineDiagnostics(_domainState.value)
    }

    fun setEyeAutofocusEnabled(enabled: Boolean) {
        prefs.eyeAutofocusEnabled = enabled
    }

    fun setAiEnhancementEnabled(enabled: Boolean) {
        prefs.aiEnhancementEnabled = enabled
        _domainState.update { it.copy(aiEnhancement = prefs.aiEnhancementEnabled) }
    }

    fun setAiMotionCompensationEnabled(enabled: Boolean) {
        prefs.aiMotionCompensationEnabled = enabled
        pipelineManager.setAiMotionCompensationEnabled(enabled)
        _domainState.update { it.copy(aiMotionCompensation = enabled) }
    }

    fun setNoiseReductionEnabled(enabled: Boolean) {
        prefs.noiseReductionEnabled = enabled
        cameraController.setNoiseReductionEnabled(enabled)
    }

    fun setUncompressedCinemaPipelineEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        prefs.uncompressedCinemaPipelineEnabled = true
        _domainState.update { it.copy(uncompressedCinemaPipeline = true) }
    }

    fun setVideoDetailProfile(profile: VideoDetailProfile) {
        prefs.videoDetailProfile = profile
        _domainState.update { it.copy(videoDetailProfile = profile) }
    }

    fun setGridMode(mode: GridMode) {
        prefs.gridMode = mode.name
        _domainState.update { it.copy(gridMode = mode) }
    }

    fun cycleGrid() {
        val values = GridMode.values()
        setGridMode(values[(_domainState.value.gridMode.ordinal + 1) % values.size])
    }

    fun toggleHist() {
        setHistogramEnabled(!_domainState.value.showHistogram)
    }

    fun setHistogramEnabled(enabled: Boolean) {
        prefs.histogramVisible = false
        _domainState.update { it.copy(showHistogram = false) }
    }

    fun toggleWaveform() {
        setWaveformEnabled(!_domainState.value.showWaveform)
    }

    fun setWaveformEnabled(enabled: Boolean) {
        prefs.waveformVisible = false
        pipelineManager.setWaveformEnabled(false)
        _domainState.update { it.copy(showWaveform = false) }
    }

    fun toggleZebra() {
        setZebraEnabled(!_domainState.value.showZebra)
    }

    fun setZebraEnabled(enabled: Boolean) {
        prefs.zebraVisible = false
        pipelineManager.setZebraEnabled(false)
        _domainState.update { it.copy(showZebra = false) }
    }

    fun togglePeaking() {
        setPeakingEnabled(!_domainState.value.peakingEnabled)
    }

    fun setPeakingEnabled(enabled: Boolean) {
        prefs.focusPeakingEnabled = false
        pipelineManager.setPeakingEnabled(false)
        pipelineManager.clearPeakingFocusRegion()
        _domainState.update { it.copy(peakingEnabled = false) }
    }

    fun setPeakingIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        prefs.focusPeakingIntensity = clamped
        pipelineManager.setPeakingIntensity(clamped)
        _domainState.update { it.copy(peakingIntensity = clamped) }
    }

    fun toggleManualControls() {
        _domainState.update { it.copy(manualControlsVisible = !it.manualControlsVisible) }
    }

    fun setZebraThresh(value: Float) {
        val clamped = value.coerceIn(0.55f, 0.99f)
        prefs.zebraThreshold = clamped
        pipelineManager.setZebraThreshold(clamped)
        _domainState.update { it.copy(zebraThreshold = clamped) }
    }

    fun syncFromLegacy(
        gridMode: GridMode,
        showHistogram: Boolean,
        showWaveform: Boolean,
        showZebra: Boolean,
        zebraThreshold: Float,
        peakingEnabled: Boolean,
        peakingIntensity: Float,
        aiEnhancement: Boolean,
        aiMotionCompensation: Boolean,
        uncompressedCinemaPipeline: Boolean,
        videoDetailProfile: VideoDetailProfile,
        manualControlsVisible: Boolean
    ) {
        _domainState.update {
            it.copy(
                gridMode = gridMode,
                showHistogram = false,
                showWaveform = false,
                showZebra = false,
                zebraThreshold = zebraThreshold,
                peakingEnabled = false,
                peakingIntensity = peakingIntensity,
                aiEnhancement = PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED && aiEnhancement,
                aiMotionCompensation = aiMotionCompensation,
                // Strict custom packet capture is mandatory; never sync a legacy false into runtime.
                uncompressedCinemaPipeline = true,
                videoDetailProfile = videoDetailProfile,
                manualControlsVisible = manualControlsVisible
            )
        }
    }

    private fun applyPipelineDiagnostics(state: SettingsDomainState) {
        pipelineManager.setWaveformEnabled(false)
        pipelineManager.setZebraEnabled(false)
        pipelineManager.setZebraThreshold(state.zebraThreshold)
        pipelineManager.setPeakingEnabled(false)
        pipelineManager.setPeakingIntensity(state.peakingIntensity)
        pipelineManager.setAiMotionCompensationEnabled(state.aiMotionCompensation)
        pipelineManager.clearPeakingFocusRegion()
    }
}
