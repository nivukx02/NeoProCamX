package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingManagerBitratePolicyTest {

    @Test
    fun fullSensorFourThreeLogBitrateStaysInCinemaGradeRange() {
        val bitrate = RecordingManager.recommendedBitrateFor(
            w = 4096,
            h = 3072,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = true,
            tenBit = true,
            colorSpace = "Rec.2020",
            colorProfile = "Sony S-Log3"
        )

        assertTrue(bitrate >= 280_000_000)
    }

    @Test
    fun recommendedBitrateMeetsHighEndFloorsAcrossCommonResolutions() {
        val cases = listOf(
            Triple(1280 to 720, 60, 200_000_000),
            Triple(1920 to 1080, 60, 400_000_000),
            Triple(3840 to 2160, 30, 500_000_000),
            Triple(3840 to 2160, 60, 1_000_000_000),
            Triple(7680 to 4320, 30, 1_000_000_000)
        )

        cases.forEach { (size, fps, minimumBitrate) ->
            val bitrate = RecordingManager.recommendedBitrateFor(
                w = size.first,
                h = size.second,
                fps = fps,
                codec = GpuVideoCodec.HEVC,
                hdrMode = "OFF",
                logEnabled = false,
                tenBit = true,
                colorSpace = "Rec.709",
                colorProfile = "Natural"
            )

            assertTrue(
                "${size.first}x${size.second}@$fps expected >= $minimumBitrate but was $bitrate",
                bitrate >= minimumBitrate
            )
        }
    }

    @Test
    fun recommendedBitrateKeepsAllVideoModesAtFullCodecCeiling() {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val normal = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = true,
            colorSpace = "Rec.709",
            colorProfile = "Natural"
        )
        val proSdr = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = true,
            colorSpace = "Rec.709",
            colorProfile = "Natural"
        )
        val hdr = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "HDR10",
            logEnabled = false,
            tenBit = true,
            colorSpace = "Rec.2020",
            colorProfile = "Natural"
        )
        val log = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = true,
            tenBit = true,
            colorSpace = "Rec.2020",
            colorProfile = "Sony S-Log3"
        )

        assertEquals(hevcCeiling, normal)
        assertEquals(hevcCeiling, proSdr)
        assertEquals(hevcCeiling, hdr)
        assertEquals(hevcCeiling, log)
    }

    @Test
    fun recommendedBitrateCarriesExtremeDetailIntoQualityBudgetOutsideStrictCeiling() = withQualityFlags(
        enabled = false,
        strict = false
    ) {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val focused = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709",
            colorProfile = "Natural",
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
        )
        val extreme = RecordingManager.recommendedBitrateFor(
            w = 3840,
            h = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709",
            colorProfile = "Natural",
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )

        assertTrue("focused=$focused extreme=$extreme", extreme > focused)
        assertTrue("extreme=$extreme ceiling=$hevcCeiling", extreme <= hevcCeiling)
    }

    private inline fun withQualityFlags(enabled: Boolean, strict: Boolean, block: () -> Unit) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = enabled
        HighEndQualityPolicy.strictHighEndOnly = strict
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
