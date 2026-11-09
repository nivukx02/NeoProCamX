package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.ui.overlays.GridMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f04 (CP-U4) — behaviour-preservation lock for the god-object slice. CameraViewModel itself is
 * NOT JVM-instantiable (its constructor casts `app as NeoProCamXApp` and builds
 * CameraController/RecordingManager/VulkanRuntimeManager/AICameraPipeline, and there is no
 * Robolectric), so the characterization is expressed at the only pure seam: the extracted
 * CameraUiStateCompositionPolicy. A scripted sequence of composition inputs — each feeding the prior
 * output as `previous` — must reproduce a fixed inline golden of emitted CameraUiState snapshots. Any
 * drift in the mapping or telemetry-preservation trips this.
 */
class CameraViewModelCharacterizationTest {

    private data class Step(
        val mode: ModeDomainState,
        val preview: PreviewDomainState,
        val recording: RecordingDomainState,
        val settings: SettingsDomainState,
        val logEnabled: Boolean,
        val videoHdrMode: String,
        val colorProfile: String
    )

    @Test
    fun emittedUiStateSequenceMatchesGolden() {
        // Seed carries non-default telemetry that other code paths would have set; the policy must
        // preserve it across every subsequent composition.
        val seed = CameraUiState(hardwareWarning = "HW", shutterGuidance = "SG", thermalThrottled = true)

        val script = listOf(
            Step(
                mode = ModeDomainState(),
                preview = PreviewDomainState(),
                recording = RecordingDomainState(),
                settings = SettingsDomainState(),
                logEnabled = false,
                videoHdrMode = "OFF",
                colorProfile = "Natural"
            ),
            Step(
                mode = ModeDomainState(rawEnabled = true),
                preview = PreviewDomainState(aspect = PreviewAspectRatio.SIXTEEN_NINE),
                recording = RecordingDomainState(gpuRecordState = GpuRecordingState.Recording),
                settings = SettingsDomainState(gridMode = GridMode.THIRDS, showHistogram = false, aiEnhancement = true, manualControlsVisible = true),
                logEnabled = true,
                videoHdrMode = "NDR10+",
                colorProfile = "Cinema"
            )
        )

        val golden = listOf(
            // Step 1: all default domain inputs ⇒ default 16 fields; telemetry preserved from seed.
            CameraUiState(
                showHistogram = false,
                showWaveform = false,
                showZebra = false,
                peakingEnabled = false,
                hardwareWarning = "HW",
                shutterGuidance = "SG",
                thermalThrottled = true
            ),
            // Step 2: non-default domain inputs map through; telemetry still preserved.
            CameraUiState(
                rawEnabled = true,
                gridMode = GridMode.THIRDS,
                logEnabled = true,
                videoHdrMode = "NDR10+",
                colorProfile = "Cinema",
                aiEnhancement = true,
                showHistogram = false,
                showWaveform = false,
                showZebra = false,
                peakingEnabled = false,
                manualControlsVisible = true,
                previewAspect = PreviewAspectRatio.SIXTEEN_NINE,
                gpuRecordState = GpuRecordingState.Recording,
                hardwareWarning = "HW",
                shutterGuidance = "SG",
                thermalThrottled = true
            )
        )

        val emitted = mutableListOf<CameraUiState>()
        var previous = seed
        for (step in script) {
            previous = CameraUiStateCompositionPolicy.compose(
                mode = step.mode,
                preview = step.preview,
                recording = step.recording,
                settings = step.settings,
                logEnabled = step.logEnabled,
                videoHdrMode = step.videoHdrMode,
                colorProfile = step.colorProfile,
                previous = previous
            )
            emitted.add(previous)
        }

        assertEquals(golden, emitted)
    }
}
