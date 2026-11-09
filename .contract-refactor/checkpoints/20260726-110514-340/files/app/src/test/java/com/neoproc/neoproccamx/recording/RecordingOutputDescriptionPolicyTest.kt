package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOutputDescriptionPolicyTest {

    @Test
    fun build_preservesLogDynamicRangeAndSceneMetadata() {
        val description = RecordingOutputDescriptionPolicy.build(
            RecordingOutputDescriptionSnapshot(
                videoWidth = 4096,
                videoHeight = 3072,
                videoFps = 30,
                codecLabel = "HEVC",
                bitDepthLabel = "10-bit",
                colorSpace = "Rec.2020",
                hdrIntentLabel = "OFF",
                dynamicRangeLabel = "LOG SDR (Rec.2020 10-bit)",
                logCurveLabel = "Sony S-Log3",
                qualityTierLabel = "CINEMA",
                cinemaMasteringEnabled = false,
                allIntraEnabled = false,
                gopSeconds = 0.10f,
                simulatedTenBitFallbackActive = false,
                bitrateBitsPerSecond = 160_000_000,
                sceneMetadataDescription = "mode=LOG;fullReadoutForced=true"
            )
        )

        assertTrue(description.contains("video=4096x3072@30fps"))
        assertTrue(description.contains(";codec=HEVC"))
        assertTrue(description.contains(";bitDepth=10-bit"))
        assertTrue(description.contains(";color=Rec.2020"))
        assertTrue(description.contains(";dynamicRange=LOG SDR (Rec.2020 10-bit)"))
        assertTrue(description.contains(";logCurve=Sony S-Log3"))
        assertTrue(description.contains(";bitrateMbps=160.0"))
        assertTrue(description.endsWith("mode=LOG;fullReadoutForced=true"))
    }

    @Test
    fun build_omitsTrailingSeparatorWhenSceneMetadataIsBlank() {
        val description = RecordingOutputDescriptionPolicy.build(
            RecordingOutputDescriptionSnapshot(
                videoWidth = 1920,
                videoHeight = 1080,
                videoFps = 24,
                codecLabel = "HEVC",
                bitDepthLabel = "8-bit",
                colorSpace = "Rec.709",
                hdrIntentLabel = "OFF",
                dynamicRangeLabel = "Original SDR (Rec.709 8-bit)",
                logCurveLabel = "OFF",
                qualityTierLabel = "STANDARD",
                cinemaMasteringEnabled = false,
                allIntraEnabled = false,
                gopSeconds = 1.0f,
                simulatedTenBitFallbackActive = false,
                bitrateBitsPerSecond = 90_000_000,
                sceneMetadataDescription = "   "
            )
        )

        assertEquals(
            "video=1920x1080@24fps;codec=HEVC;bitDepth=8-bit;color=Rec.709;hdrIntent=OFF;" +
                "dynamicRange=Original SDR (Rec.709 8-bit);logCurve=OFF;qualityTier=STANDARD;" +
                "cinemaMaster=false;allIntra=false;gopSeconds=1.00;simulatedTenBit=false;bitrateMbps=90.0",
            description
        )
    }
}
