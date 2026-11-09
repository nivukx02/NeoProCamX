package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ModeDomainState(
    val activeMode: AppMode = AppMode.VIDEO,
    val cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
    val rawCompression: RawCompressionMode = RawCompressionMode.HIGH_COMPRESSION,
    val rawEnabled: Boolean = false
)

internal class CameraModeController(
    private val cameraController: CameraController,
    private val pipelineManager: FramePipelineManager,
    private val prefs: PreferencesManager
) {
    private val _domainState = MutableStateFlow(
        ModeDomainState(
            activeMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.fromName(prefs.videoMode),
            rawCompression = RawCompressionMode.fromName(prefs.rawVideoCompression),
            rawEnabled = CinemaVideoMode.fromName(prefs.videoMode) == CinemaVideoMode.RAW
        )
    )
    val domainState: StateFlow<ModeDomainState> = _domainState.asStateFlow()

    fun setMode(@Suppress("UNUSED_PARAMETER") mode: AppMode) {
        // Photo modes were removed; all callers are normalized to the video surface.
        if (_domainState.value.activeMode != AppMode.VIDEO) {
            prefs.lastUiMode = AppMode.VIDEO.name
            _domainState.update { it.copy(activeMode = AppMode.VIDEO) }
        }
    }

    fun setCinemaVideoMode(mode: CinemaVideoMode) {
        val rawEnabled = mode == CinemaVideoMode.RAW
        prefs.videoMode = mode.name
        cameraController.setRawVideoCaptureEnabled(rawEnabled)
        if (!rawEnabled) {
            pipelineManager.clearPeakingFocusRegion()
        }
        _domainState.update {
            it.copy(
                activeMode = AppMode.VIDEO,
                cinemaMode = mode,
                rawEnabled = rawEnabled
            )
        }
    }

    fun toggleRawMode(rawSupported: Boolean, logEnabled: Boolean) {
        if (!rawSupported) {
            setCinemaVideoMode(CinemaVideoMode.PRO)
            return
        }
        val enableRawVideo =
            _domainState.value.cinemaMode != CinemaVideoMode.RAW || !_domainState.value.rawEnabled
        setCinemaVideoMode(
            when {
                enableRawVideo -> CinemaVideoMode.RAW
                logEnabled -> CinemaVideoMode.LOG
                else -> CinemaVideoMode.PRO
            }
        )
    }

    fun setRawVideoCompressionMode(mode: RawCompressionMode) {
        prefs.rawVideoCompression = mode.name
        _domainState.update { it.copy(rawCompression = mode) }
    }

    fun syncFromLegacy(
        activeMode: AppMode,
        cinemaMode: CinemaVideoMode,
        rawCompression: RawCompressionMode,
        rawEnabled: Boolean
    ) {
        _domainState.update {
            it.copy(
                activeMode = activeMode,
                cinemaMode = cinemaMode,
                rawCompression = rawCompression,
                rawEnabled = rawEnabled
            )
        }
    }
}
