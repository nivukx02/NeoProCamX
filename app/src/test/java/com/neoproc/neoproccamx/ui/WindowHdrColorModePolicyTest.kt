package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowHdrColorModePolicyTest {

    @Test
    fun resolveColorMode_offMapsToSdr() {
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode("OFF", logEnabled = false)
        )
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode(null, logEnabled = false)
        )
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode("", logEnabled = false)
        )
    }

    @Test
    fun resolveColorMode_recognisesAllHdrVariants() {
        for (mode in listOf(
            "HDR10", "hdr10", "HDR", "HDR10+", "HDR10PLUS", "hdr10plus",
            "NDR10+", "NDR10PLUS", "NIVUKX Vision", "NIVUKXVISION",
            "HLG", "hlg"
        )) {
            assertEquals(
                "Mode $mode should resolve to HDR window",
                WindowHdrColorModePolicy.HdrColorMode.HDR,
                WindowHdrColorModePolicy.resolveColorMode(mode, logEnabled = false)
            )
        }
    }

    @Test
    fun resolveColorMode_rejectsLegacyVendorVisionText() {
        for (mode in listOf("Dolby Vision", "DOLBYVISION", "DV", "dv")) {
            assertEquals(
                "Mode $mode should stay SDR unless a NeoProCamX HDR profile is selected",
                WindowHdrColorModePolicy.HdrColorMode.SDR,
                WindowHdrColorModePolicy.resolveColorMode(mode, logEnabled = false)
            )
        }
    }

    @Test
    fun resolveColorMode_logEnabledKeepsSdrWindowWhenHdrIsOff() {
        // LOG recording is tagged as wide-gamut SDR, so the preview window
        // must not be promoted to HDR unless an HDR mode is actually active.
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode("OFF", logEnabled = true)
        )
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode(null, logEnabled = true)
        )
    }

    @Test
    fun resolveColorMode_explicitHdrTrumpsLogFlag() {
        // The two together should still resolve to HDR (a single state).
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.HDR,
            WindowHdrColorModePolicy.resolveColorMode("HDR10", logEnabled = true)
        )
    }

    @Test
    fun resolveColorMode_explicitOffWithLogFalseIsSdr() {
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode("OFF", logEnabled = false)
        )
    }

    @Test
    fun resolveColorMode_unknownStringIsSdr() {
        assertEquals(
            WindowHdrColorModePolicy.HdrColorMode.SDR,
            WindowHdrColorModePolicy.resolveColorMode("CUSTOM", logEnabled = false)
        )
    }
}
