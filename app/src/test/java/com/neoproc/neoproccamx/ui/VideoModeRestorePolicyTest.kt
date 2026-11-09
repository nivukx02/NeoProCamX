package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModeRestorePolicyTest {
    @Test
    fun resolve_keepsNivukxVisionActiveWhenPersistedModeWasRaw() {
        val decision = VideoModeRestorePolicy.resolve(
            requestedHdrMode = "NIVUKX Vision",
            hdrRequested = true,
            uiMode = AppMode.VIDEO,
            requestedCinemaMode = CinemaVideoMode.RAW,
            rawSupported = true
        )

        assertEquals("NIVUKXVISION", decision.hdrMode)
        assertTrue(decision.hdrEnabled)
        assertEquals(CinemaVideoMode.PRO, decision.cinemaMode)
        assertFalse(decision.rawEnabled)
        assertFalse(decision.logEnabled)
    }

    @Test
    fun resolve_keepsNdrActiveWhenPersistedModeWasLog() {
        val decision = VideoModeRestorePolicy.resolve(
            requestedHdrMode = "HDR10",
            hdrRequested = true,
            uiMode = AppMode.VIDEO,
            requestedCinemaMode = CinemaVideoMode.LOG,
            rawSupported = true
        )

        assertEquals("NDR10+", decision.hdrMode)
        assertTrue(decision.hdrEnabled)
        assertEquals(CinemaVideoMode.PRO, decision.cinemaMode)
        assertFalse(decision.rawEnabled)
        assertFalse(decision.logEnabled)
    }

    @Test
    fun resolve_preservesRawWhenHdrIsOff() {
        val decision = VideoModeRestorePolicy.resolve(
            requestedHdrMode = "OFF",
            hdrRequested = false,
            uiMode = AppMode.VIDEO,
            requestedCinemaMode = CinemaVideoMode.RAW,
            rawSupported = true
        )

        assertEquals("OFF", decision.hdrMode)
        assertFalse(decision.hdrEnabled)
        assertEquals(CinemaVideoMode.RAW, decision.cinemaMode)
        assertTrue(decision.rawEnabled)
        assertFalse(decision.logEnabled)
    }

    @Test
    fun resolve_rejectsUnsupportedRawWithoutRewritingRequestedIdentity() {
        val decision = VideoModeRestorePolicy.resolve(
            requestedHdrMode = "OFF",
            hdrRequested = false,
            uiMode = AppMode.VIDEO,
            requestedCinemaMode = CinemaVideoMode.RAW,
            rawSupported = false
        )

        assertEquals(CinemaVideoMode.RAW, decision.requestedCinemaMode)
        assertEquals(CinemaVideoMode.RAW, decision.cinemaMode)
        assertFalse(decision.cinemaModeAdmission.accepted)
        assertFalse(decision.rawEnabled)
        assertFalse(decision.logEnabled)
    }
}
