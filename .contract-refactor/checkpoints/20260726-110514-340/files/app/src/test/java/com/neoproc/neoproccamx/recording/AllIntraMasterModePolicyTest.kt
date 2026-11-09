package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for [AllIntraMasterModePolicy] (CP-QV12).
 *
 * Verifies each Master tier maps to the exact single-source `AllIntraHevcPolicy.BITRATE_*`
 * constant, that a too-high tier is clamped DOWN to the device cap (no over-claim), that a
 * too-low tier is floored UP, and that only [VideoBitrateMode.CINEMA_MASTER] is treated as a
 * Master request.
 */
class AllIntraMasterModePolicyTest {

    // A device range that is wide open (small floor, huge ceiling) so no tier is clamped —
    // proving each tier passes through as its exact source-of-truth constant.
    private val wideLower = 64_000
    private val wideUpper = 900_000_000

    @Test
    fun eachTierReferencesExactBitrateConstant() {
        assertEquals(AllIntraHevcPolicy.BITRATE_ECONOMY, AllIntraMasterModePolicy.MasterTier.ECONOMY_80.bps)
        assertEquals(AllIntraHevcPolicy.BITRATE_STANDARD, AllIntraMasterModePolicy.MasterTier.STANDARD_100.bps)
        assertEquals(AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA, AllIntraMasterModePolicy.MasterTier.PRO_120.bps)
        assertEquals(AllIntraHevcPolicy.BITRATE_CINEMA_MASTER, AllIntraMasterModePolicy.MasterTier.CINEMA_180.bps)

        // The referenced constants are exactly 80 / 100 / 120 / 180 Mbps.
        assertEquals(80_000_000, AllIntraMasterModePolicy.MasterTier.ECONOMY_80.bps)
        assertEquals(100_000_000, AllIntraMasterModePolicy.MasterTier.STANDARD_100.bps)
        assertEquals(120_000_000, AllIntraMasterModePolicy.MasterTier.PRO_120.bps)
        assertEquals(180_000_000, AllIntraMasterModePolicy.MasterTier.CINEMA_180.bps)
    }

    @Test
    fun eachTierResolvesToItsExactBitrateWhenDeviceRangeIsWideOpen() {
        assertEquals(
            80_000_000,
            AllIntraMasterModePolicy.resolveRequestedTierBitrate(
                AllIntraMasterModePolicy.MasterTier.ECONOMY_80, wideUpper, wideLower
            )
        )
        assertEquals(
            100_000_000,
            AllIntraMasterModePolicy.resolveRequestedTierBitrate(
                AllIntraMasterModePolicy.MasterTier.STANDARD_100, wideUpper, wideLower
            )
        )
        assertEquals(
            120_000_000,
            AllIntraMasterModePolicy.resolveRequestedTierBitrate(
                AllIntraMasterModePolicy.MasterTier.PRO_120, wideUpper, wideLower
            )
        )
        assertEquals(
            180_000_000,
            AllIntraMasterModePolicy.resolveRequestedTierBitrate(
                AllIntraMasterModePolicy.MasterTier.CINEMA_180, wideUpper, wideLower
            )
        )
    }

    @Test
    fun cinema180ClampsDownToLowerDeviceCapProvingNoOverClaim() {
        // Device only advertises up to 120 Mbps: the 180 Mbps request MUST clamp down to 120,
        // never exceed the encoder-advertised ceiling.
        val effective = AllIntraMasterModePolicy.resolveRequestedTierBitrate(
            tier = AllIntraMasterModePolicy.MasterTier.CINEMA_180,
            deviceCapUpperBps = 120_000_000,
            deviceCapLowerBps = 64_000
        )
        assertEquals(120_000_000, effective)
        assertTrue(
            "Master must never over-claim beyond the device cap",
            effective <= 120_000_000
        )
    }

    @Test
    fun tierBelowFloorFloorsUpToDeviceFloor() {
        // Device floor (lower bound) is 100 Mbps, above the 80 Mbps ECONOMY request: the
        // resolver floors the request UP to the honored floor rather than under-driving the encoder.
        val effective = AllIntraMasterModePolicy.resolveRequestedTierBitrate(
            tier = AllIntraMasterModePolicy.MasterTier.ECONOMY_80,
            deviceCapUpperBps = 600_000_000,
            deviceCapLowerBps = 100_000_000
        )
        assertEquals(100_000_000, effective)
        assertTrue("Effective bitrate must not drop below the device floor", effective >= 100_000_000)
    }

    @Test
    fun onlyCinemaMasterCountsAsMasterModeRequested() {
        assertTrue(AllIntraMasterModePolicy.isMasterModeRequested(VideoBitrateMode.CINEMA_MASTER))
        assertFalse(AllIntraMasterModePolicy.isMasterModeRequested(VideoBitrateMode.AUTO))
        assertFalse(AllIntraMasterModePolicy.isMasterModeRequested(VideoBitrateMode.CUSTOM))
    }
}
