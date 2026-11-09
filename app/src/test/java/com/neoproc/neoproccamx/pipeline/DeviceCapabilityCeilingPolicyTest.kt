package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for the RAM/core → runtime-tier ceiling ladder. Both a RAM AND a core threshold must be
 * met to climb a rung, so a lopsided device lands at the lower sustainable tier. Entry devices map to
 * STANDARD (native-sensor max + GLES); flagships map to FLAGSHIP (no cap).
 */
class DeviceCapabilityCeilingPolicyTest {

    @Test
    fun flagshipHardwareMapsToFlagshipCeiling() {
        assertEquals(HighEndRuntimeTier.FLAGSHIP, DeviceCapabilityCeilingPolicy.resolve(8192, 8))
        assertEquals(HighEndRuntimeTier.FLAGSHIP, DeviceCapabilityCeilingPolicy.resolve(16384, 12))
    }

    @Test
    fun highEndMapsToUltra() {
        assertEquals(HighEndRuntimeTier.ULTRA, DeviceCapabilityCeilingPolicy.resolve(6144, 6))
        assertEquals(HighEndRuntimeTier.ULTRA, DeviceCapabilityCeilingPolicy.resolve(7000, 7))
    }

    @Test
    fun midRangeMapsToCinema() {
        assertEquals(HighEndRuntimeTier.CINEMA, DeviceCapabilityCeilingPolicy.resolve(4096, 4))
    }

    @Test
    fun entryLevelMapsToStandard() {
        assertEquals(HighEndRuntimeTier.STANDARD, DeviceCapabilityCeilingPolicy.resolve(2048, 4))
        assertEquals(HighEndRuntimeTier.STANDARD, DeviceCapabilityCeilingPolicy.resolve(3000, 8))
    }

    @Test
    fun lopsidedHardwareDropsToLowerSustainableTier() {
        // Plenty of RAM but only 4 cores → cannot claim the 8-core FLAGSHIP or 6-core ULTRA rung.
        assertEquals(HighEndRuntimeTier.CINEMA, DeviceCapabilityCeilingPolicy.resolve(8192, 4))
        // Many cores but low RAM → RAM gate dominates.
        assertEquals(HighEndRuntimeTier.STANDARD, DeviceCapabilityCeilingPolicy.resolve(3072, 8))
    }
}
