package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f02 (CP-U2) — white balance Kelvin + CC tint round-trip within quantization, and endpoint
 * clamping. Locks the 50K Kelvin step and the exact ±150 tint axis. Pure JVM.
 */
class WbKelvinTintRoundTripTest {

    @Test
    fun kelvinRoundTripsExactlyOverFullRange() {
        val maxP = ManualControlValueMapper.whiteBalanceProgressMax
        for (p in 0..maxP) {
            val kelvin = ManualControlValueMapper.whiteBalanceKelvinForProgress(p)
            assertEquals("kelvin round-trip at $p", p, ManualControlValueMapper.progressForWhiteBalanceKelvin(kelvin))
        }
    }

    @Test
    fun kelvinClampsAtHardwareEndpoints() {
        assertEquals(ManualControlValueMapper.whiteBalanceMin, ManualControlValueMapper.whiteBalanceKelvinForProgress(-10))
        assertEquals(ManualControlValueMapper.whiteBalanceMax, ManualControlValueMapper.whiteBalanceKelvinForProgress(Int.MAX_VALUE))
        assertEquals(0, ManualControlValueMapper.progressForWhiteBalanceKelvin(ManualControlValueMapper.whiteBalanceMin - 5000))
        assertEquals(
            ManualControlValueMapper.whiteBalanceProgressMax,
            ManualControlValueMapper.progressForWhiteBalanceKelvin(ManualControlValueMapper.whiteBalanceMax + 5000)
        )
    }

    @Test
    fun tintRoundTripsExactlyMinCenterMax() {
        for (t in listOf(-150, -75, 0, 75, 150)) {
            assertEquals("tint round-trip $t", t, ManualControlValueMapper.tintValueForProgress(ManualControlValueMapper.progressForTintValue(t)))
        }
    }
}
