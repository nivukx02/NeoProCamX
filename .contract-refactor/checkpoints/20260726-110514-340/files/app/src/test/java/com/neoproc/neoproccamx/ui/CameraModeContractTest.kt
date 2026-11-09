package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeContractTest {

    @Test
    fun appMode_surface_isBackCameraOnlyThreeModeShell() {
        assertEquals(listOf(AppMode.VIDEO, AppMode.PRO), AppMode.entries)
    }

    @Test
    fun cinemaVideoMode_surface_onlyExposesProLogRaw() {
        assertEquals(listOf(CinemaVideoMode.PRO, CinemaVideoMode.LOG, CinemaVideoMode.RAW), CinemaVideoMode.entries)
    }
}
