package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.ui.overlays.GridMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiCompositeStateContractTest {
    @Test
    fun cameraUiStateCarriesAllUiVisibleCaptureState() {
        val state = CameraUiState(
            uiMode = AppMode.PRO,
            rawEnabled = true,
            gridMode = GridMode.THIRDS,
            logEnabled = true,
            videoHdrMode = "NDR10+",
            colorProfile = "Neo Natural",
            aiEnhancement = true,
            showHistogram = false,
            showWaveform = false,
            showZebra = true,
            zebraThreshold = 0.92f,
            peakingEnabled = true,
            peakingIntensity = 0.72f,
            manualControlsVisible = true,
            previewAspect = PreviewAspectRatio.SIXTEEN_NINE,
            gpuRecordState = GpuRecordingState.Recording,
            hardwareWarning = "Thermal",
            shutterGuidance = "1/48",
            thermalTier = HighEndRuntimeTier.RECOVERY,
            thermalThrottled = true
        )

        assertEquals(AppMode.PRO, state.uiMode)
        assertTrue(state.rawEnabled)
        assertEquals(GridMode.THIRDS, state.gridMode)
        assertTrue(state.logEnabled)
        assertEquals("NDR10+", state.videoHdrMode)
        assertEquals("Neo Natural", state.colorProfile)
        assertTrue(state.aiEnhancement)
        assertFalse(state.showHistogram)
        assertFalse(state.showWaveform)
        assertTrue(state.showZebra)
        assertEquals(0.92f, state.zebraThreshold, 0.0001f)
        assertTrue(state.peakingEnabled)
        assertEquals(0.72f, state.peakingIntensity, 0.0001f)
        assertTrue(state.manualControlsVisible)
        assertEquals(PreviewAspectRatio.SIXTEEN_NINE, state.previewAspect)
        assertTrue(state.gpuRecordState is GpuRecordingState.Recording)
        assertEquals("Thermal", state.hardwareWarning)
        assertEquals("1/48", state.shutterGuidance)
        assertEquals(HighEndRuntimeTier.RECOVERY, state.thermalTier)
        assertTrue(state.thermalThrottled)
    }

    @Test
    fun cameraUiStateCarriesGroupedImmutableSubStatesForUiDomains() {
        val state = CameraUiState(
            showHistogram = false,
            showWaveform = true,
            showZebra = false,
            zebraThreshold = 0.91f,
            peakingEnabled = false,
            peakingIntensity = 0.32f,
            manualControlsVisible = true,
            hardwareWarning = "Storage slow",
            shutterGuidance = "Use 1/48",
            thermalTier = HighEndRuntimeTier.RECOVERY,
            thermalThrottled = true
        )

        assertEquals(
            DiagnosticOverlayState(
                showHistogram = false,
                showWaveform = true,
                showZebra = false,
                zebraThreshold = 0.91f,
                peakingEnabled = false,
                peakingIntensity = 0.32f
            ),
            state.diagnosticOverlay
        )
        assertEquals(ManualCaptureState(manualControlsVisible = true), state.manualCapture)
        assertEquals(
            SystemTelemetryState(
                hardwareWarning = "Storage slow",
                shutterGuidance = "Use 1/48",
                thermalTier = HighEndRuntimeTier.RECOVERY,
                thermalThrottled = true
            ),
            state.systemTelemetry
        )
    }
}
