package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10RecordingRoutePolicyTest {

    @Test
    fun `proprietary ndr modes never use vendor camera2 p010 as their master`() {
        listOf("NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA")
            .forEach { hdrMode ->
                assertFalse(
                    hdrMode,
                    resolve(cinemaMode = CinemaVideoMode.PRO, hdrMode = hdrMode)
                )
            }
    }

    @Test
    fun `processed sdr and log retain the packed ten bit private master route`() {
        assertTrue(resolve(cinemaMode = CinemaVideoMode.PRO, hdrMode = "OFF"))
        assertTrue(
            resolve(
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true,
                hdrMode = "OFF"
            )
        )
    }

    @Test
    fun `sdr conversion preference gates only plain processed sdr`() {
        assertTrue(
            resolve(
                cinemaMode = CinemaVideoMode.PRO,
                hdrMode = "OFF",
                sdrConversionEnabled = true
            )
        )
        assertFalse(
            resolve(
                cinemaMode = CinemaVideoMode.PRO,
                hdrMode = "OFF",
                sdrConversionEnabled = false
            )
        )
        assertTrue(
            resolve(
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true,
                hdrMode = "OFF",
                sdrConversionEnabled = false
            )
        )
    }

    @Test
    fun `raw non video and disabled requests cannot enter the p010 master route`() {
        assertFalse(
            resolve(
                cinemaMode = CinemaVideoMode.RAW,
                rawEnabled = true,
                hdrMode = "OFF"
            )
        )
        assertFalse(resolve(uiMode = AppMode.PRO, hdrMode = "OFF"))
        assertFalse(resolve(enabled = false, hdrMode = "OFF"))
    }

    private fun resolve(
        uiMode: AppMode = AppMode.VIDEO,
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawEnabled: Boolean = false,
        logEnabled: Boolean = false,
        hdrMode: String,
        enabled: Boolean = true,
        sdrConversionEnabled: Boolean = true
    ): Boolean = Npv10RecordingRoutePolicy.shouldUseCamera2P010Master(
        uiMode = uiMode,
        cinemaMode = cinemaMode,
        rawEnabled = rawEnabled,
        logEnabled = logEnabled,
        hdrMode = hdrMode,
        uncompressedCinemaPipelineEnabled = enabled,
        sdrConversionPipelineEnabled = sdrConversionEnabled
    )
}
