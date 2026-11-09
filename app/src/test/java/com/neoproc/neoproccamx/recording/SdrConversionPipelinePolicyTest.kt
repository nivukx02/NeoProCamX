package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrConversionPipelinePolicyTest {

    @Test
    fun `plain processed sdr follows the user destination preference`() {
        assertTrue(resolve(enabled = true))
        assertFalse(resolve(enabled = false))
        assertFalse(direct(enabled = true))
        assertTrue(direct(enabled = false))
    }

    @Test
    fun `raw log ndr and nivukx ignore the sdr destination preference`() {
        assertTrue(
            resolve(
                enabled = false,
                cinemaMode = CinemaVideoMode.RAW,
                rawEnabled = true
            )
        )
        assertTrue(
            resolve(
                enabled = false,
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true
            )
        )
        listOf("NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA")
            .forEach { hdrMode ->
                assertTrue(hdrMode, resolve(enabled = false, hdrMode = hdrMode))
            }
        assertFalse(
            direct(
                enabled = false,
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true
            )
        )
    }

    @Test
    fun `sdr classification is strict and accepts normalized off token`() {
        assertTrue(
            SdrConversionPipelinePolicy.isPlainSdrVideo(
                cinemaMode = CinemaVideoMode.PRO,
                rawEnabled = false,
                logEnabled = false,
                hdrMode = " off "
            )
        )
        assertFalse(
            SdrConversionPipelinePolicy.isPlainSdrVideo(
                cinemaMode = CinemaVideoMode.PRO,
                rawEnabled = true,
                logEnabled = false,
                hdrMode = "OFF"
            )
        )
    }

    private fun resolve(
        enabled: Boolean,
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawEnabled: Boolean = false,
        logEnabled: Boolean = false,
        hdrMode: String = "OFF"
    ): Boolean = SdrConversionPipelinePolicy.allowsPrivateConversionMaster(
        sdrConversionPipelineEnabled = enabled,
        cinemaMode = cinemaMode,
        rawEnabled = rawEnabled,
        logEnabled = logEnabled,
        hdrMode = hdrMode
    )

    private fun direct(
        enabled: Boolean,
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawEnabled: Boolean = false,
        logEnabled: Boolean = false,
        hdrMode: String = "OFF"
    ): Boolean = SdrConversionPipelinePolicy.usesDirectGalleryDelivery(
        sdrConversionPipelineEnabled = enabled,
        cinemaMode = cinemaMode,
        rawEnabled = rawEnabled,
        logEnabled = logEnabled,
        hdrMode = hdrMode
    )
}
