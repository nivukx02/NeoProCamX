package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingManagerPolicyTest {

    @Test
    fun recommendedBitrate_usesMaximumBudgetForSdrHdrLogAndTenBit() {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val base = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709"
        )
        val enhanced = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "HDR10",
            logEnabled = true,
            tenBit = true,
            colorSpace = "Rec.2020"
        )

        assertEquals(hevcCeiling, base)
        assertEquals(hevcCeiling, enhanced)
    }

    @Test
    fun minimumBitrateFloor_isMonotonicWithResolution() {
        val hd = RecordingManager.minimumBitrateFloorFor(1920, 1080)
        val twoK = RecordingManager.minimumBitrateFloorFor(2048, 1080)
        val uhd = RecordingManager.minimumBitrateFloorFor(3840, 2160)
        val sixK = RecordingManager.minimumBitrateFloorFor(5760, 3240)

        assertTrue(twoK > hd)
        assertTrue(uhd > twoK)
        assertEquals(HighEndQualityPolicy.minimumBitrateBps(3840, 2160, 30), uhd)
        assertEquals(HighEndQualityPolicy.minimumBitrateBps(5760, 3240, 30), sixK)
    }

    @Test
    fun minimumBitrateFloor_usesActualFpsForStrictSdrRuntime() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            val thirty = RecordingManager.minimumBitrateFloorFor(3840, 2160, fps = 30)
            val sixty = RecordingManager.minimumBitrateFloorFor(3840, 2160, fps = 60)

            assertEquals(HighEndQualityPolicy.minimumBitrateBps(3840, 2160, 30), thirty)
            assertEquals(HighEndQualityPolicy.minimumBitrateBps(3840, 2160, 60), sixty)
            assertTrue(sixty > thirty)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun recommendedBitrate_neverDropsBelowFloorForRequestedResolution() {
        val width = 1920
        val height = 1080
        val recommended = RecordingManager.recommendedBitrateFor(
            w = width,
            h = height,
            fps = 24,
            codec = GpuVideoCodec.AVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709"
        )

        assertTrue(recommended >= RecordingManager.minimumBitrateFloorFor(width, height))
    }
}
