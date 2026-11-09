package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrModePolicyTest {
    @Test
    fun `pro video NDR families own a raw sensor target`() {
        listOf("NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA").forEach { mode ->
            assertTrue(
                SensorLinearNdrModePolicy.isActive(
                    AppMode.VIDEO,
                    CinemaVideoMode.PRO,
                    rawEnabled = false,
                    logEnabled = false,
                    hdrMode = mode
                )
            )
        }
    }

    @Test
    fun `non video raw log and SDR states do not claim sensor linear NDR`() {
        assertFalse(active(uiMode = AppMode.PRO, hdrMode = "NDR10+"))
        assertFalse(active(cinemaMode = CinemaVideoMode.RAW, hdrMode = "NDR10+"))
        assertFalse(active(rawEnabled = true, hdrMode = "NDR10+"))
        assertFalse(active(logEnabled = true, hdrMode = "NDR10+"))
        assertFalse(active(hdrMode = "OFF"))
    }

    private fun active(
        uiMode: AppMode = AppMode.VIDEO,
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawEnabled: Boolean = false,
        logEnabled: Boolean = false,
        hdrMode: String
    ): Boolean = SensorLinearNdrModePolicy.isActive(
        uiMode, cinemaMode, rawEnabled, logEnabled, hdrMode
    )
}
