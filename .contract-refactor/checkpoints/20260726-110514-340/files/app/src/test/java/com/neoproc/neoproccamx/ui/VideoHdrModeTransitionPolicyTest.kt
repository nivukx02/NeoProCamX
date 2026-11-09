package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoHdrModeTransitionPolicyTest {
    @Test
    fun resolve_switchesRawToProBeforeHdrSoNdrDoesNotUseRawSensorMonitor() {
        val result = VideoHdrModeTransitionPolicy.resolve(
            requestedMode = "NIVUKX Vision",
            uiMode = AppMode.VIDEO,
            currentCinemaMode = CinemaVideoMode.RAW
        )

        assertEquals("NIVUKXVISION", result.normalizedHdrMode)
        assertEquals(CinemaVideoMode.PRO, result.cinemaModeOverride)
    }

    @Test
    fun resolve_normalizesLogToProBeforeHdr() {
        val result = VideoHdrModeTransitionPolicy.resolve(
            requestedMode = "HDR10",
            uiMode = AppMode.VIDEO,
            currentCinemaMode = CinemaVideoMode.LOG
        )

        assertEquals("NDR10+", result.normalizedHdrMode)
        assertEquals(CinemaVideoMode.PRO, result.cinemaModeOverride)
    }

    @Test
    fun resolve_keepsHdrRequestInProMode() {
        val result = VideoHdrModeTransitionPolicy.resolve(
            requestedMode = "hlg",
            uiMode = AppMode.VIDEO,
            currentCinemaMode = CinemaVideoMode.PRO
        )

        assertEquals("NDR10+", result.normalizedHdrMode)
        assertNull(result.cinemaModeOverride)
    }
}
