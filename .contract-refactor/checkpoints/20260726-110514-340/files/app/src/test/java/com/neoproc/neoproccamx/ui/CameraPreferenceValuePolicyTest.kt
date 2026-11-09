package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.RAW_MODE_MAX_FPS
import com.neoproc.neoproccamx.ui.overlays.GridMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreferenceValuePolicyTest {

    @Test
    fun `audio source preference values preserve legacy aliases and fallback`() {
        assertEquals(AudioSrc.MIC, CameraPreferenceValuePolicy.audioSourceFromPref("BUILTIN_MIC"))
        assertEquals(AudioSrc.MIC, CameraPreferenceValuePolicy.audioSourceFromPref("MIC"))
        assertEquals(AudioSrc.UNPROCESSED, CameraPreferenceValuePolicy.audioSourceFromPref("UNPROCESSED"))
        assertEquals(AudioSrc.NONE, CameraPreferenceValuePolicy.audioSourceFromPref("NONE"))
        assertEquals(AudioSrc.CAMCORDER, CameraPreferenceValuePolicy.audioSourceFromPref("unknown"))
    }

    @Test
    fun `audio source index values preserve settings spinner mapping and fallback`() {
        assertEquals(AudioSrc.CAMCORDER, CameraPreferenceValuePolicy.audioSourceFromIndex(0))
        assertEquals(AudioSrc.MIC, CameraPreferenceValuePolicy.audioSourceFromIndex(1))
        assertEquals(AudioSrc.UNPROCESSED, CameraPreferenceValuePolicy.audioSourceFromIndex(2))
        assertEquals(AudioSrc.NONE, CameraPreferenceValuePolicy.audioSourceFromIndex(3))
        assertEquals(AudioSrc.CAMCORDER, CameraPreferenceValuePolicy.audioSourceFromIndex(-1))
        assertEquals(AudioSrc.CAMCORDER, CameraPreferenceValuePolicy.audioSourceFromIndex(99))
    }

    @Test
    fun `grid mode preference accepts enum names case insensitively and falls back to thirds`() {
        assertEquals(GridMode.CENTER, CameraPreferenceValuePolicy.gridModeFromPref("CENTER"))
        assertEquals(GridMode.NONE, CameraPreferenceValuePolicy.gridModeFromPref("none"))
        assertEquals(GridMode.THIRDS, CameraPreferenceValuePolicy.gridModeFromPref("not_a_grid"))
    }

    @Test
    fun `requested fps sanitizes before applying raw mode cap`() {
        assertEquals(
            RAW_MODE_MAX_FPS,
            CameraPreferenceValuePolicy.resolveContractRequestedFps(
                requestedFps = 120,
                rawVideoModeActive = true,
                rawModeMaxFps = RAW_MODE_MAX_FPS
            )
        )
        assertEquals(
            48,
            CameraPreferenceValuePolicy.resolveContractRequestedFps(
                requestedFps = 120,
                rawVideoModeActive = true,
                rawModeMaxFps = 48
            )
        )
        assertEquals(
            60,
            CameraPreferenceValuePolicy.resolveContractRequestedFps(
                requestedFps = 120,
                rawVideoModeActive = false,
                rawModeMaxFps = 48
            )
        )
    }

    @Test
    fun `raw compression normalizes only for raw cinema mode`() {
        assertEquals(
            RawCompressionMode.HIGH_COMPRESSION,
            CameraPreferenceValuePolicy.normalizeRawCompressionForMode(
                mode = RawCompressionMode.LOSSLESS,
                cinemaMode = CinemaVideoMode.RAW
            )
        )
        assertEquals(
            RawCompressionMode.LOSSLESS,
            CameraPreferenceValuePolicy.normalizeRawCompressionForMode(
                mode = RawCompressionMode.LOSSLESS,
                cinemaMode = CinemaVideoMode.LOG
            )
        )
        assertEquals(
            RawCompressionMode.NEO_FINE_RAW,
            CameraPreferenceValuePolicy.normalizeRawCompressionForMode(
                mode = RawCompressionMode.NEO_FINE_RAW,
                cinemaMode = CinemaVideoMode.PRO
            )
        )
    }
}
