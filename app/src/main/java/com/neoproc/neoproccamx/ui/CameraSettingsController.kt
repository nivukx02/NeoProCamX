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
    val aiEnhancement: Boolean = false,
    val aiMotionCompensation: Boolean = false,
    val uncompressedCinemaPipeline: Boolean = false,
    val sdrConversionPipeline: Boolean = false,
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
            aiEnhancement = prefs.aiEnhancementEnabled,
            aiMotionCompensation = prefs.aiMotionCompensationEnabled,
            uncompressedCinemaPipeline = prefs.uncompressedCinemaPipelineEnabled,
            sdrConversionPipeline = prefs.sdrConversionPipelineEnabled,
            videoDetailProfile = prefs.videoDetailProfile
        )
    )
    val domainState: StateFlow<SettingsDomainState> = _domainState.asStateFlow()

    init {
        pipelineManager.setAiMotionCompensationEnabled(_domainState.value.aiMotionCompensation)
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

    fun setSdrConversionPipelineEnabled(enabled: Boolean) {
        prefs.sdrConversionPipelineEnabled = enabled
        _domainState.update { it.copy(sdrConversionPipeline = enabled) }
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

    fun toggleManualControls() {
        _domainState.update { it.copy(manualControlsVisible = !it.manualControlsVisible) }
    }

    fun syncFromLegacy(
        gridMode: GridMode,
        aiEnhancement: Boolean,
        aiMotionCompensation: Boolean,
        uncompressedCinemaPipeline: Boolean,
        sdrConversionPipeline: Boolean,
        videoDetailProfile: VideoDetailProfile,
        manualControlsVisible: Boolean
    ) {
        _domainState.update {
            it.copy(
                gridMode = gridMode,
                aiEnhancement = PreferencesManager.AI_ENHANCEMENT_SETTINGS_ENABLED && aiEnhancement,
                aiMotionCompensation = aiMotionCompensation,
                // Strict custom packet capture is mandatory; never sync a legacy false into runtime.
                uncompressedCinemaPipeline = true,
                sdrConversionPipeline = sdrConversionPipeline,
                videoDetailProfile = videoDetailProfile,
                manualControlsVisible = manualControlsVisible
            )
        }
    }

}
