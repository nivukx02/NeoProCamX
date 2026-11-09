package com.neoproc.neoproccamx.core

import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract for Phase-7 device-adaptive scaling. The injected [HighEndQualityPolicy.deviceCapabilityCeiling]
 * must (a) be a NO-OP at its FLAGSHIP default so every existing strict floor is byte-identical, and
 * (b) relax the 4K resolution floor, the bitrate floor, and the runtime-tier escalation on a weaker
 * device so it records its native-sensor maximum — never downscaling a capable device.
 *
 * State is saved/restored (the policy is a shared singleton) so no ceiling leaks across the suite.
 */
class HighEndQualityPolicyDeviceAdaptiveTest {

    private var savedStrict = true
    private var savedCeiling = HighEndRuntimeTier.FLAGSHIP

    @Before
    fun save() {
        savedStrict = HighEndQualityPolicy.strictHighEndOnly
        savedCeiling = HighEndQualityPolicy.deviceCapabilityCeiling
        HighEndQualityPolicy.strictHighEndOnly = true
    }

    @After
    fun restore() {
        HighEndQualityPolicy.strictHighEndOnly = savedStrict
        HighEndQualityPolicy.deviceCapabilityCeiling = savedCeiling
    }

    @Test
    fun flagshipDefaultIsInertAndPreservesStrictFloors() {
        HighEndQualityPolicy.deviceCapabilityCeiling = HighEndRuntimeTier.FLAGSHIP
        assertEquals(HighEndRuntimeTier.FLAGSHIP, HighEndQualityPolicy.runtimeTier(60f, recording = true))
        assertEquals(3840, HighEndQualityPolicy.minimumRecordingWidth())
        assertEquals(2160, HighEndQualityPolicy.minimumRecordingHeight())
        assertEquals(1_000_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, 30))
        assertEquals(2_400_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, 60))
    }

    @Test
    fun entryLevelStandardCeilingRelaxesFloorsToNativeMax() {
        HighEndQualityPolicy.deviceCapabilityCeiling = HighEndRuntimeTier.STANDARD
        // runtime tier no longer escalated to FLAGSHIP — clamped to the device ceiling.
        assertEquals(HighEndRuntimeTier.STANDARD, HighEndQualityPolicy.runtimeTier(60f, recording = true))
        // 4K floor dropped to native 1080 so the device is not rejected for missing 4K.
        assertEquals(1920, HighEndQualityPolicy.minimumRecordingWidth())
        assertEquals(1080, HighEndQualityPolicy.minimumRecordingHeight())
        assertTrue(HighEndQualityPolicy.isRecordingResolutionAcceptable(1920, 1080, 4000))
        // Bitrate floor softened (420 Mbps × 0.5) so a weak encoder isn't asked for ~1 Gbps.
        assertEquals(210_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(1920, 1080, 30))
        // Vulkan-ineligible tier → GLES fallback selected.
        assertTrue(HighEndQualityPolicy.shouldUseGlesFallback(60f, recording = true))
    }

    @Test
    fun midRangeCinemaCeilingStays4KWithFullBitrate() {
        HighEndQualityPolicy.deviceCapabilityCeiling = HighEndRuntimeTier.CINEMA
        assertEquals(3840, HighEndQualityPolicy.minimumRecordingWidth())
        assertEquals(1_000_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, 30))
        assertEquals(HighEndRuntimeTier.CINEMA, HighEndQualityPolicy.runtimeTier(60f, recording = true))
        assertFalse(HighEndQualityPolicy.shouldUseGlesFallback(60f, recording = true))
    }

    @Test
    fun highEndUltraCeilingHasNoBitrateOrVulkanRegression() {
        HighEndQualityPolicy.deviceCapabilityCeiling = HighEndRuntimeTier.ULTRA
        assertEquals(1_000_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, 30))
        assertEquals(HighEndRuntimeTier.ULTRA, HighEndQualityPolicy.runtimeTier(60f, recording = true))
        assertFalse(HighEndQualityPolicy.shouldUseGlesFallback(60f, recording = true))
    }
}
