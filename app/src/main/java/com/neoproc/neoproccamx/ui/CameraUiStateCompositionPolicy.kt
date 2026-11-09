package com.neoproc.neoproccamx.ui

/**
 * s8-f04 (CP-U4) — Extract-Class slice of the CameraViewModel god object. The composite CameraUiState
 * was built by two textually-duplicated mapping sites (the combine-collector and
 * publishCompositeUiState): both map the 16 domain/legacy fields and PRESERVE the 4 telemetry fields
 * (hardwareWarning/shutterGuidance/thermalTier/thermalThrottled) from the previous state. This pure
 * object is the single source of that mapping — the behaviour-preserving seam the characterization
 * test locks (the ViewModel itself is not JVM-instantiable).
 *
 * Behaviour-preserving by construction: it is a byte-identical transcription of the two sites, so the
 * emitted CameraUiState is bit-for-bit unchanged.
 */
internal object CameraUiStateCompositionPolicy {
    fun compose(
        mode: ModeDomainState,
        preview: PreviewDomainState,
        recording: RecordingDomainState,
        settings: SettingsDomainState,
        logEnabled: Boolean,
        videoHdrMode: String,
        colorProfile: String,
        previous: CameraUiState
    ): CameraUiState = CameraUiState(
        uiMode = mode.activeMode,
        rawEnabled = mode.rawEnabled,
        gridMode = settings.gridMode,
        logEnabled = logEnabled,
        videoHdrMode = videoHdrMode,
        colorProfile = colorProfile,
        aiEnhancement = settings.aiEnhancement,
        manualControlsVisible = settings.manualControlsVisible,
        previewAspect = preview.aspect,
        gpuRecordState = recording.gpuRecordState,
        // Telemetry fields are owned by other code paths and preserved across composition.
        hardwareWarning = previous.hardwareWarning,
        shutterGuidance = previous.shutterGuidance,
        thermalTier = previous.thermalTier,
        thermalThrottled = previous.thermalThrottled
    )
}
