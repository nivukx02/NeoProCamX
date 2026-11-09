package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.ui.overlays.GridMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f04 (CP-U4) — unit test for the extracted composition policy: all 16 domain/legacy fields map
 * from the inputs, the 4 telemetry fields are preserved from `previous`, and re-composition with the
 * same domain inputs is idempotent.
 */
class CameraUiStateCompositionPolicyTest {

    @Test
    fun composeMapsAllDomainAndLegacyFields() {
        val composed = CameraUiStateCompositionPolicy.compose(
            mode = ModeDomainState(rawEnabled = true),
            preview = PreviewDomainState(aspect = PreviewAspectRatio.SIXTEEN_NINE),
            recording = RecordingDomainState(gpuRecordState = GpuRecordingState.Recording),
            settings = SettingsDomainState(
                gridMode = GridMode.THIRDS,
                showHistogram = false,
                showWaveform = false,
                showZebra = false,
                zebraThreshold = 0.70f,
                peakingEnabled = false,
                peakingIntensity = 0.90f,
                aiEnhancement = true,
                manualControlsVisible = true
            ),
            logEnabled = true,
            videoHdrMode = "NDR10+",
            colorProfile = "Cinema",
            previous = CameraUiState()
        )

        assertEquals(AppMode.VIDEO, composed.uiMode)
        assertEquals(true, composed.rawEnabled)
        assertEquals(GridMode.THIRDS, composed.gridMode)
        assertEquals(true, composed.logEnabled)
        assertEquals("NDR10+", composed.videoHdrMode)
        assertEquals("Cinema", composed.colorProfile)
        assertEquals(true, composed.aiEnhancement)
        assertEquals(false, composed.showHistogram)
        assertEquals(false, composed.showWaveform)
        assertEquals(false, composed.showZebra)
        assertEquals(0.70f, composed.zebraThreshold, 0f)
        assertEquals(false, composed.peakingEnabled)
        assertEquals(0.90f, composed.peakingIntensity, 0f)
        assertEquals(true, composed.manualControlsVisible)
        assertEquals(PreviewAspectRatio.SIXTEEN_NINE, composed.previewAspect)
        assertEquals(GpuRecordingState.Recording, composed.gpuRecordState)
    }

    @Test
    fun composePreservesTelemetryFromPrevious() {
        val previous = CameraUiState(
            hardwareWarning = "HW",
            shutterGuidance = "SG",
            thermalThrottled = true
        )
        val composed = CameraUiStateCompositionPolicy.compose(
            mode = ModeDomainState(rawEnabled = true),
            preview = PreviewDomainState(),
            recording = RecordingDomainState(),
            settings = SettingsDomainState(manualControlsVisible = true),
            logEnabled = true,
            videoHdrMode = "HLG",
            colorProfile = "Natural",
            previous = previous
        )
        // Telemetry comes from previous, domain fields from inputs.
        assertEquals("HW", composed.hardwareWarning)
        assertEquals("SG", composed.shutterGuidance)
        assertEquals(previous.thermalTier, composed.thermalTier)
        assertEquals(true, composed.thermalThrottled)
        assertEquals(true, composed.rawEnabled)
        assertEquals(true, composed.manualControlsVisible)
    }

    @Test
    fun composeIsIdempotentUnderConstantDomainInputs() {
        val mode = ModeDomainState(rawEnabled = true)
        val preview = PreviewDomainState(aspect = PreviewAspectRatio.FOUR_THREE)
        val recording = RecordingDomainState()
        val settings = SettingsDomainState(aiEnhancement = true)
        val seed = CameraUiState(hardwareWarning = "HW", thermalThrottled = true)

        val first = CameraUiStateCompositionPolicy.compose(mode, preview, recording, settings, true, "OFF", "Natural", seed)
        val second = CameraUiStateCompositionPolicy.compose(mode, preview, recording, settings, true, "OFF", "Natural", first)
        assertEquals(first, second)
    }
}
