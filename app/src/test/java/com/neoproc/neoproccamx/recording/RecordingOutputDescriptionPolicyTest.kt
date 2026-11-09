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
                colorRange = RecordingOutputColorRange.FULL,
                masteringPeakNits = null,
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
        assertTrue(description.contains(";colorRange=FULL"))
        assertTrue(description.contains(";masteringPeakNits=NONE"))
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
                colorRange = RecordingOutputColorRange.LIMITED,
                masteringPeakNits = null,
                sceneMetadataDescription = "   "
            )
        )

        assertEquals(
            "video=1920x1080@24fps;codec=HEVC;bitDepth=8-bit;color=Rec.709;hdrIntent=OFF;" +
                "dynamicRange=Original SDR (Rec.709 8-bit);logCurve=OFF;qualityTier=STANDARD;" +
                "cinemaMaster=false;allIntra=false;gopSeconds=1.00;simulatedTenBit=false;" +
                "bitrateMbps=90.0;colorRange=LIMITED;masteringPeakNits=NONE",
            description
        )
    }

    @Test
    fun build_serializesCanonicalBrandedPeaksAndExactGenericPqPeak() {
        val ndr10 = RecordingOutputDescriptionPolicy.build(
            hdrSnapshot(hdrIntent = "NDR10+", dynamicRange = "NDR10+ BT.2100 PQ", peak = 1_000.0)
        )
        val nivukx = RecordingOutputDescriptionPolicy.build(
            hdrSnapshot(hdrIntent = "NIVUKXVISION", dynamicRange = "NIVUKX Vision", peak = 5_000.0)
        )
        val apex = RecordingOutputDescriptionPolicy.build(
            hdrSnapshot(hdrIntent = "APEX_CINEMA", dynamicRange = "NIVUKX APEX CINEMA", peak = 5_000.0)
        )
        val generic = RecordingOutputDescriptionPolicy.build(
            hdrSnapshot(hdrIntent = "PQ", dynamicRange = "BT.2100 PQ", peak = 4_321.5)
        )

        assertTrue(ndr10.contains(";masteringPeakNits=5000"))
        assertTrue(nivukx.contains(";masteringPeakNits=10000"))
        assertTrue(apex.contains(";masteringPeakNits=10000"))
        assertTrue(generic.contains(";masteringPeakNits=4321.5"))
    }

    @Test
    fun build_genericPqFailsClosedForMissingOrMalformedPeak() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 999.0, 10_001.0).forEach { peak ->
            val description = RecordingOutputDescriptionPolicy.build(
                hdrSnapshot(hdrIntent = "PQ", dynamicRange = "BT.2100 PQ", peak = peak)
            )
            assertTrue(description.contains(";masteringPeakNits=1000"))
        }
    }

    @Test
    fun latestSceneRefreshChangesOnlyScenePacketAndPreservesPreparedColorIdentity() {
        val prepared = hdrSnapshot(
            hdrIntent = "NDR10+",
            dynamicRange = "NDR10+ BT.2100 PQ",
            peak = 5_000.0
        ).copy(sceneMetadataDescription = "validationGeneration=1;colorRange=LIMITED")

        val refreshed = RecordingOutputDescriptionPolicy.withLatestSceneMetadata(
            preparedSnapshot = prepared,
            latestSceneMetadataDescription =
                "validationGeneration=2;colorRange=LIMITED;masteringPeakNits=1000"
        )

        assertEquals(
            prepared,
            refreshed.copy(sceneMetadataDescription = prepared.sceneMetadataDescription)
        )
        assertEquals(RecordingOutputColorRange.FULL, refreshed.colorRange)
        assertEquals(5_000.0, refreshed.masteringPeakNits ?: 0.0, 0.0)
        assertEquals(
            "validationGeneration=2;colorRange=LIMITED;masteringPeakNits=1000",
            refreshed.sceneMetadataDescription
        )

        val description = RecordingOutputDescriptionPolicy.build(refreshed)
        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(description)
        assertEquals(RecordingMediaStoreColorRange.FULL, metadata.colorRange)
        assertEquals(5_000.0, metadata.masteringPeakNits, 0.0)
    }

    private fun hdrSnapshot(
        hdrIntent: String,
        dynamicRange: String,
        peak: Double?
    ) = RecordingOutputDescriptionSnapshot(
        videoWidth = 3840,
        videoHeight = 2160,
        videoFps = 25,
        codecLabel = "HEVC",
        bitDepthLabel = "10-bit",
        colorSpace = "Rec.2020",
        hdrIntentLabel = hdrIntent,
        dynamicRangeLabel = dynamicRange,
        logCurveLabel = "OFF",
        qualityTierLabel = "CINEMA",
        cinemaMasteringEnabled = true,
        allIntraEnabled = false,
        gopSeconds = 1.0f,
        simulatedTenBitFallbackActive = false,
        bitrateBitsPerSecond = 180_000_000,
        colorRange = RecordingOutputColorRange.FULL,
        masteringPeakNits = peak,
        sceneMetadataDescription = ""
    )
}
