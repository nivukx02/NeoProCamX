package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraModeMeteringDefaultPolicyTest {

    @Test
    fun `subject metering does not auto enable face detection for presets`() {
        CameraModePreset.entries.forEach { preset ->
            assertFalse(
                "$preset should leave face detection user-controlled",
                CameraModeMeteringDefaultPolicy.shouldEnableForPreset(preset)
            )
        }
    }

    @Test
    fun `subject metering does not auto enable face detection for video log hdr raw modes`() {
        val modes = listOf(
            Triple(AppMode.VIDEO, CinemaVideoMode.PRO, "OFF"),
            Triple(AppMode.PRO, CinemaVideoMode.PRO, "OFF"),
            Triple(AppMode.VIDEO, CinemaVideoMode.LOG, "OFF"),
            Triple(AppMode.PRO, CinemaVideoMode.LOG, "OFF"),
            Triple(AppMode.VIDEO, CinemaVideoMode.PRO, "HDR10"),
            Triple(AppMode.PRO, CinemaVideoMode.PRO, "HDR10"),
            Triple(AppMode.VIDEO, CinemaVideoMode.RAW, "OFF"),
            Triple(AppMode.PRO, CinemaVideoMode.RAW, "OFF")
        )

        modes.forEach { (uiMode, cinemaMode, hdrMode) ->
            assertFalse(
                "$uiMode/$cinemaMode/$hdrMode should leave face detection user-controlled",
                CameraModeMeteringDefaultPolicy.shouldEnableForVideoMode(
                    uiMode = uiMode,
                    cinemaMode = cinemaMode,
                    hdrMode = hdrMode
                )
            )
        }
    }
}
