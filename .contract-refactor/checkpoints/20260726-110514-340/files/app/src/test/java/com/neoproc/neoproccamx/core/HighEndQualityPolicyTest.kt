package com.neoproc.neoproccamx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighEndQualityPolicyTest {

    @Test
    fun `imax strict profile exposes canonical hard constants`() {
        assertEquals("IMAX_STRICT_V20_100000", ImaxStrictProfile.PROFILE_NAME)
        assertEquals(7.20f, ImaxStrictProfile.ULTRA_DETAIL_AMPLIFICATION, 0.000001f)
        assertEquals(2.90f, ImaxStrictProfile.ADAPTIVE_GAIN_CEILING, 0.000001f)
        assertEquals(0.96f, ImaxStrictProfile.SHARPENING_STRICT_CEILING, 0.000001f)
        assertEquals(10.0f, ImaxStrictProfile.MAX_ADDITIVE_DETAIL_GAIN, 0.000001f)
        assertEquals(1500.0f, ImaxStrictProfile.QUALITY_BOOST_60FPS, 0.000001f)
        assertEquals(129, ImaxStrictProfile.LUT_CUBE_SIZE)
        assertEquals(65, SingularityHorizonProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE)
        assertEquals(0.0026f, ImaxStrictProfile.SHOT_NOISE_COEFFICIENT, 0.0000001f)
        assertEquals(0.00050f, ImaxStrictProfile.READ_NOISE_COEFFICIENT, 0.0000001f)
        assertEquals(0.0000002f, ImaxStrictProfile.HETEROSCEDASTIC_FLOOR, 0.00000001f)
        assertEquals(50.0, ImaxStrictProfile.MIN_LUMA_SNR_BASE_DB, 0.000001)
        assertEquals(40.0, ImaxStrictProfile.MIN_LUMA_SNR_HIGH_ISO_DB, 0.000001)
        assertEquals(0.4, ImaxStrictProfile.MAX_CHROMA_NOISE_BASE_STD, 0.000001)
        assertEquals(0.8, ImaxStrictProfile.MAX_CHROMA_NOISE_HIGH_ISO_STD, 0.000001)
        assertEquals(0.8, ImaxStrictProfile.MAX_TEMPORAL_FLICKER_PERCENT, 0.000001)
        assertEquals(0.34, ImaxStrictProfile.MIN_MTF50_CY_PER_PX, 0.000001)
        assertEquals(3.0, ImaxStrictProfile.MAX_OVERSHOOT_PERCENT, 0.000001)
        assertEquals(0.93, ImaxStrictProfile.MIN_DETAIL_RETENTION_RAW, 0.000001)
        assertEquals(0.90, ImaxStrictProfile.MIN_DETAIL_RETENTION_HIGH_ISO_RAW, 0.000001)
        assertEquals(1.3, ImaxStrictProfile.MAX_DELTA_E2000_MEAN, 0.000001)
        assertEquals(3.0, ImaxStrictProfile.MAX_DELTA_E2000, 0.000001)
        assertEquals(1.0, ImaxStrictProfile.MAX_GRAY_DELTA_ABS, 0.000001)
        assertEquals(0.0, ImaxStrictProfile.MAX_BANDING_SCORE, 0.000001)
        assertEquals(0, ImaxStrictProfile.MAX_DEAD_HOT_PIXELS)
        assertEquals("Rec2020_PQ", ImaxStrictProfile.EXPORT_COLOR_SPACE)
        assertEquals("4:2:2", ImaxStrictProfile.EXPORT_CHROMA_SUBSAMPLING)
        assertEquals(12, ImaxStrictProfile.EXPORT_BIT_DEPTH)
        assertEquals("TETRAHEDRAL", ImaxStrictProfile.LUT_INTERPOLATION)
        assertEquals(true, ImaxStrictProfile.FORCE_STRICT_ONLY)
    }

    @Test
    fun `beyond imax profile is stricter than imax strict without replacing it`() {
        assertEquals("BEYOND_IMAX_ELITE_10_10", BeyondImaxEliteProfile.PROFILE_NAME)
        assertTrue(BeyondImaxEliteProfile.ULTRA_DETAIL_AMPLIFICATION > ImaxStrictProfile.ULTRA_DETAIL_AMPLIFICATION)
        assertTrue(BeyondImaxEliteProfile.ADAPTIVE_GAIN_CEILING > ImaxStrictProfile.ADAPTIVE_GAIN_CEILING)
        assertTrue(BeyondImaxEliteProfile.SHARPENING_STRICT_CEILING >= ImaxStrictProfile.SHARPENING_STRICT_CEILING)
        assertTrue(BeyondImaxEliteProfile.QUALITY_BOOST_60FPS > ImaxStrictProfile.QUALITY_BOOST_60FPS)
        assertTrue(BeyondImaxEliteProfile.MIN_LUMA_SNR_BASE_DB > ImaxStrictProfile.MIN_LUMA_SNR_BASE_DB)
        assertTrue(BeyondImaxEliteProfile.MAX_CHROMA_NOISE_BASE_STD < ImaxStrictProfile.MAX_CHROMA_NOISE_BASE_STD)
        assertTrue(BeyondImaxEliteProfile.MAX_TEMPORAL_FLICKER_PERCENT < ImaxStrictProfile.MAX_TEMPORAL_FLICKER_PERCENT)
        assertTrue(BeyondImaxEliteProfile.MIN_MTF50_CY_PER_PX > ImaxStrictProfile.MIN_MTF50_CY_PER_PX)
        assertTrue(BeyondImaxEliteProfile.MAX_DELTA_E2000_MEAN < ImaxStrictProfile.MAX_DELTA_E2000_MEAN)
        assertEquals(128, BeyondImaxEliteProfile.CANONICAL_UNIFORM_CHECKSUM.length)
    }

    @Test
    fun `minimum bitrate enforces beyond imax long floors`() {
        assertEquals(2_000_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(7680, 4320, 25))
        assertEquals(1_500_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(5120, 2880, 25))
        assertEquals(1_000_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, 25))
        assertEquals(640_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(2048, 1080, 25))
        assertEquals(420_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(1920, 1080, 25))
        assertEquals(240_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(1280, 720, 25))
        assertEquals(2_400_000_000L, HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, 60))
        assertEquals(1_000_000_000, HighEndQualityPolicy.minimumBitrateBps(3840, 2160, 25))
    }

    @Test
    fun `strict ai lut and boost use beyond imax ceilings`() {
        assertEquals(3200, HighEndQualityPolicy.aiMaxDimension(previewFps = 30f, recording = false))
        assertEquals(4096, HighEndQualityPolicy.aiMaxDimension(previewFps = 30f, recording = true))
        assertEquals(65, HighEndQualityPolicy.lutSizeForPreview(highFidelity = true, previewFps = 60f, recording = true))
        assertEquals(1000.0f, HighEndQualityPolicy.qualityBoost(recording = true, previewFps = 24f), 0.0001f)
        assertEquals(2400.0f, HighEndQualityPolicy.qualityBoost(recording = true, previewFps = 60f), 0.0001f)
        assertEquals(5200.0f, HighEndQualityPolicy.qualityBoost(recording = true, previewFps = 120f), 0.0001f)
    }

    @Test
    fun `runtime preview lut stays heap safe during live mode switches`() {
        val previewSize = HighEndQualityPolicy.lutSizeForPreview(
            highFidelity = true,
            previewFps = 60f,
            recording = true
        )
        val floatCount = previewSize.toLong() * previewSize * previewSize * 3L
        val bytes = floatCount * Float.SIZE_BYTES

        assertEquals(previewSize, HighEndQualityPolicy.highPrecisionLutSize)
        assertEquals(65, previewSize)
        assertTrue("preview LUT allocation bytes=$bytes", bytes <= 16L * 1024L * 1024L)
        assertEquals(129, ImaxStrictProfile.LUT_CUBE_SIZE)
        assertEquals(257, BeyondImaxEliteProfile.LUT_CUBE_SIZE)
    }

    @Test
    fun `preview fps ceiling stays at 60 for the video contract`() {
        assertEquals(60, HighEndQualityPolicy.maxPreviewFps(120))
        assertEquals(60, HighEndQualityPolicy.targetPreviewFps)
        assertEquals(60, HighEndQualityPolicy.snapToCinemaFps(120))
    }

    @Test
    fun `strict high end keeps lower cinema fps selections instead of forcing sixty`() {
        assertEquals(24, HighEndQualityPolicy.previewFpsFloor(24, recording = true))
        assertEquals(25, HighEndQualityPolicy.previewFpsFloor(25, recording = true))
        assertEquals(25, HighEndQualityPolicy.previewFpsFloor(30, recording = true))
        assertEquals(25, HighEndQualityPolicy.snapToCinemaFps(30))
        assertEquals(24, HighEndQualityPolicy.maxPreviewFps(24))
        assertEquals(25, HighEndQualityPolicy.maxPreviewFps(30))
    }
}
