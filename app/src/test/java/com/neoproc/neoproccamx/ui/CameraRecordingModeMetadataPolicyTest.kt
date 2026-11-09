package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecordingModeMetadataPolicyTest {

    @Test
    fun `normal video is labelled VIDEO when pro pipeline is used without manual controls`() {
        val label = CameraRecordingModeMetadataPolicy.resolveModeLabel(
            cinemaMode = CinemaVideoMode.PRO,
            rawEnabled = false,
            logEnabled = false,
            hdrMode = "OFF",
            manualControlsVisible = false
        )

        assertEquals("VIDEO", label)
    }

    @Test
    fun `manual pro video is labelled PRO`() {
        val label = CameraRecordingModeMetadataPolicy.resolveModeLabel(
            cinemaMode = CinemaVideoMode.PRO,
            rawEnabled = false,
            logEnabled = false,
            hdrMode = "OFF",
            manualControlsVisible = true
        )

        assertEquals("PRO", label)
    }

    @Test
    fun `hdr video is labelled HDR before generic pro`() {
        val label = CameraRecordingModeMetadataPolicy.resolveModeLabel(
            cinemaMode = CinemaVideoMode.PRO,
            rawEnabled = false,
            logEnabled = false,
            hdrMode = "HDR10",
            manualControlsVisible = false
        )

        assertEquals("HDR", label)
    }

    @Test
    fun `log and raw modes keep explicit labels`() {
        assertEquals(
            "LOG",
            CameraRecordingModeMetadataPolicy.resolveModeLabel(
                cinemaMode = CinemaVideoMode.LOG,
                rawEnabled = false,
                logEnabled = true,
                hdrMode = "OFF",
                manualControlsVisible = false
            )
        )
        assertEquals(
            "RAW",
            CameraRecordingModeMetadataPolicy.resolveModeLabel(
                cinemaMode = CinemaVideoMode.RAW,
                rawEnabled = true,
                logEnabled = false,
                hdrMode = "OFF",
                manualControlsVisible = false
            )
        )
    }
}
