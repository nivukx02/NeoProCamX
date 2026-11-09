package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewHudFormatterTest {

    @Test
    fun format_joinsSensorResolutionAndAspectOnly() {
        val result = CameraPreviewHudFormatter.format(
            sensorWidth = 8192,
            sensorHeight = 6144,
            resolution = "8K UHD",
            aspect = "4:3"
        )

        assertEquals("8192x6144 | 8K UHD | 4:3", result)
    }

    @Test
    fun format_usesUnknownForMissingFields() {
        val result = CameraPreviewHudFormatter.format(
            sensorWidth = null,
            sensorHeight = null,
            resolution = "",
            aspect = null
        )

        assertEquals("Unknown | Unknown | Unknown", result)
    }

    // CP-QV23 — 35mm-equivalent lens badge rides the existing HUD line.

    @Test
    fun format_appendsLensBadgeWhenLabelPresent() {
        val result = CameraPreviewHudFormatter.format(
            sensorWidth = 8192,
            sensorHeight = 6144,
            resolution = "8K UHD",
            aspect = "4:3",
            lensLabel = "24mm Wide"
        )

        assertEquals("8192x6144 | 8K UHD | 4:3 | 24mm Wide", result)
    }

    @Test
    fun format_lensBadgeNullIsByteIdenticalToLegacyFormat() {
        val legacy = CameraPreviewHudFormatter.format(
            sensorWidth = 8192,
            sensorHeight = 6144,
            resolution = "8K UHD",
            aspect = "4:3"
        )
        val withNullLabel = CameraPreviewHudFormatter.format(
            sensorWidth = 8192,
            sensorHeight = 6144,
            resolution = "8K UHD",
            aspect = "4:3",
            lensLabel = null
        )

        assertEquals("8192x6144 | 8K UHD | 4:3", legacy)
        assertEquals(legacy, withNullLabel)
    }

    @Test
    fun format_blankLensBadgeIsNoOp() {
        val result = CameraPreviewHudFormatter.format(
            sensorWidth = 8192,
            sensorHeight = 6144,
            resolution = "8K UHD",
            aspect = "4:3",
            lensLabel = "   "
        )

        assertEquals("8192x6144 | 8K UHD | 4:3", result)
    }

    @Test
    fun aspectLabel_resolvesCommonSensorRatios() {
        assertEquals("4:3", CameraPreviewHudFormatter.aspectLabel(8192, 6144))
        assertEquals("16:9", CameraPreviewHudFormatter.aspectLabel(3840, 2160))
        assertEquals("1:1", CameraPreviewHudFormatter.aspectLabel(3000, 3000))
    }

    @Test
    fun formatStage6Status_usesCompactTruthfulRecordingLine() {
        val result = CameraPreviewHudFormatter.formatStage6Status(
            provisional = true,
            qualityClass = "CORE-HONEST",
            transportClassification = "AHB_GPU_COMPATIBILITY",
            deviceLabStatus = "NOT_TESTED",
            nativePathValidated = false
        )

        assertEquals("PROV CORE | AHB_GPU | Lab NT | Native NT", result)
    }
}
