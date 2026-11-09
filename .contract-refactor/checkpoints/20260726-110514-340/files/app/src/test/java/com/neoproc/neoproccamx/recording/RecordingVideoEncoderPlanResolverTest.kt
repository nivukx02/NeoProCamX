package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaHardware
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaMode
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaPlanResolver
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaQuality
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaRequest
import com.neoproc.neoproccamx.pipeline.ComputationalCinemaRuntime
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class RecordingVideoEncoderPlanResolverTest {

    private inline fun withHighEndQuality(enabled: Boolean, block: () -> Unit) {
        val previous = HighEndQualityPolicy.enabled
        HighEndQualityPolicy.enabled = enabled
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previous
        }
    }

    @Test
    fun resolveColorEncoding_usesBt2020AndNeoPqTransferForLegacyHdr10Alias() {
        val config = RecordingVideoEncoderPlanResolver.resolveColorEncoding(
            mime = "video/hevc",
            videoHdrMode = "HDR10",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, config.colorStandard)
        // NDR-family recording resolves legacy HDR10 labels to Neo BT.2100 PQ delivery. Android
        // capture may use HLG as the camera carrier, but recorded pixels/tags must not fall back to
        // the old HLG-only NDR behavior when the true-PQ transport is enabled.
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, config.colorTransfer)
        assertTrue(config.requiresTenBit)
        assertTrue(config.main10Requested)
    }

    @Test
    fun resolveColorEncoding_preservesWideGamutSdrWhenHdrToggleIsOff() {
        val config = RecordingVideoEncoderPlanResolver.resolveColorEncoding(
            mime = "video/hevc",
            videoHdrMode = "OFF",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, config.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, config.colorTransfer)
        assertTrue(config.requiresTenBit)
        assertTrue(config.main10Requested)
    }

    @Test
    fun resolveColorEncoding_keepsLogRecordingsInWideGamutSdr() {
        val config = RecordingVideoEncoderPlanResolver.resolveColorEncoding(
            mime = "video/hevc",
            videoHdrMode = "OFF",
            videoColorSpace = "Rec.2020",
            logEnabled = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, config.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, config.colorTransfer)
        assertTrue(config.requiresTenBit)
        assertTrue(config.main10Requested)
    }

    @Test
    fun resolveColorEncoding_logRequestOverridesStaleHdrToggleAsWideGamutSdr() {
        val config = RecordingVideoEncoderPlanResolver.resolveColorEncoding(
            mime = "video/hevc",
            videoHdrMode = "HLG",
            videoColorSpace = "Rec.2020",
            logEnabled = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, config.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, config.colorTransfer)
        assertTrue(config.requiresTenBit)
        assertTrue(config.main10Requested)
    }

    @Test
    fun resolvePlan_preservesWideGamutWhenTenBitIsUnavailable() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 220_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 180_000_000 },
                clampBitrate = { it }
            )

            assertFalse(plan.useTenBit)
            assertTrue(plan.simulatedTenBitFallback)
            assertEquals("Original 8-bit (10-bit unavailable)", plan.activeBitDepthLabel)
            assertEquals("Wide Gamut SDR (Rec.2020 8-bit)", plan.dynamicRangeLabel)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertEquals(
                RecordingCodecCatalog.iframeInterval(RecordingCodecCatalog.QualityTier.CINEMA),
                plan.iFrameIntervalSeconds,
                0.0001f
            )
            assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, plan.preferredBitrateModes.first())
            val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
            val masteringBoost = HighEndQualityPolicy.qualityBoost(recording = true, previewFps = 30f) *
                if (strictHighEnd) 1.22f else 1.10f
            val expectedBitrate = (
                220_000_000f *
                    1.16f *
                    masteringBoost
                ).roundToInt()
            assertEquals(expectedBitrate, plan.effectiveBitrate)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, plan.profile)
        }
    }

    @Test
    fun resolvePlan_preservesLogFallbackLabelWhenTenBitIsUnavailable() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 220_000_000,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 180_000_000 },
                clampBitrate = { it },
                logEnabled = true
            )

            assertEquals("LOG SDR (Rec.2020 8-bit)", plan.dynamicRangeLabel)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
        }
    }

    @Test
    fun resolvePlan_keepsLogSdrOnMain10WithSdrTransferRequest() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 4096,
                height = 2304,
                fps = 60,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 120_000_000 },
                clampBitrate = { it },
                logEnabled = true
            )

            assertTrue(plan.useTenBit)
            assertFalse(plan.simulatedTenBitFallback)
            assertEquals("Hardware 10-bit", plan.activeBitDepthLabel)
            assertEquals("LOG SDR (Rec.2020 10-bit)", plan.dynamicRangeLabel)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertTrue(plan.colorEncoding.main10Requested)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
        }
    }

    @Test
    fun resolvePlan_selectsHevcMain10ProfileWhenAvailable() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 280_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 180_000_000 },
                clampBitrate = { it }
            )

            assertTrue(plan.useTenBit)
            assertFalse(plan.simulatedTenBitFallback)
            assertEquals("Hardware 10-bit", plan.activeBitDepthLabel)
            assertEquals("NDR10+ BT.2100 PQ (10-bit)", plan.dynamicRangeLabel)
            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertEquals(
                RecordingCodecCatalog.iframeInterval(RecordingCodecCatalog.QualityTier.CINEMA),
                plan.iFrameIntervalSeconds,
                0.0001f
            )
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51, plan.level)
            assertEquals(180, plan.operatingRate)
        }
    }

    @Test
    fun resolvePlan_usesResolutionHeadroomOperatingRateFor4k25And4k30() {
        withHighEndQuality(enabled = true) {
            val colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = true,
                main10Requested = true
            )
            val plan25 = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 25,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = colorEncoding,
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 120_000_000 },
                clampBitrate = { it }
            )
            val plan30 = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = colorEncoding,
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 120_000_000 },
                clampBitrate = { it }
            )

            assertEquals(90, plan25.operatingRate)
            assertEquals(90, plan30.operatingRate)
        }
    }

    @Test
    fun resolvePlan_usesFullFrameHeadroomOperatingRateForFourByThree4k25Hdr() {
        withHighEndQuality(enabled = true) {
            val colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = true,
                main10Requested = true
            )
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 4096,
                height = 3072,
                fps = 25,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = colorEncoding,
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 160_000_000 },
                clampBitrate = { it }
            )

            assertEquals(180, plan.operatingRate)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertTrue(plan.useTenBit)
        }
    }

    @Test
    fun resolvePlan_selectsStrictCodecLevelsAcrossResolutionAndFps() {
        withHighEndQuality(enabled = true) {
            val eightKHevc = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 7680,
                height = 4320,
                fps = 30,
                requestedBitrate = 2_000_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 1_000_000_000 },
                clampBitrate = { it }
            )
            val fourKAvc60 = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/avc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 1_000_000_000,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 500_000_000 },
                clampBitrate = { it }
            )
            val fourKAv1Hdr60 = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/av01",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 1_600_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 500_000_000 },
                clampBitrate = { it }
            )

            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, eightKHevc.level)
            assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel52, fourKAvc60.level)
            assertEquals(MediaCodecInfo.CodecProfileLevel.AV1Level51, fourKAv1Hdr60.level)
        }
    }

    @Test
    fun resolvePlan_preservesNeoNdrVersionLabelsWhenPqTransportIsShared() {
        withHighEndQuality(enabled = true) {
            val ndr10Plus = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 260_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 180_000_000 },
                clampBitrate = { it },
                requestedDynamicRangeLabel = "HDR10+"
            )
            val nivukx = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 260_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 180_000_000 },
                clampBitrate = { it },
                requestedDynamicRangeLabel = "NIVUKX Vision"
            )

            assertEquals("NDR10+ BT.2100 PQ (10-bit)", ndr10Plus.dynamicRangeLabel)
            assertEquals("NIVUKX Vision BT.2100 PQ (10-bit)", nivukx.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_recordsProSdrRec709AsMain10WhenTenBitSettingIsOn() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 220_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 120_000_000 },
                clampBitrate = { it }
            )

            assertTrue(plan.useTenBit)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals("Original SDR (Rec.709 10-bit)", plan.dynamicRangeLabel)
            assertTrue(plan.colorEncoding.main10Requested)
        }
    }

    @Test
    fun resolvePlan_keepsComputationalMain10ForPureSdrRec709Delivery() {
        withHighEndQuality(enabled = false) {
            val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
                request = ComputationalCinemaRequest(
                    mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                    quality = ComputationalCinemaQuality.MAXIMUM,
                    zoomRatio = 1.0f,
                    recording = true
                ),
                hardware = ComputationalCinemaHardware(
                    supportsRaw = true,
                    supportsYuvReprocessing = true,
                    supportsHdrVideo = true,
                    supportsTenBit = true,
                    supportsHevcMain10 = true,
                    supportsOis = true,
                    supportsVideoStabilization = true,
                    supportsVulkan = true,
                    supportsZeroCopyGpuImport = true,
                    supportsAiSuperResolution = true,
                    maxEncoderLongEdge = 7680,
                    maxSensorLongEdge = 8192
                ),
                runtime = ComputationalCinemaRuntime(
                    previewFps = 60f,
                    targetFps = 60,
                    thermalSeverity = 0,
                    droppedFrames = 0,
                    encoderPressure = 0.10f,
                    estimatedLux = 120f,
                    motionScore = 0.04f
                )
            )

            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 4096,
                height = 2304,
                fps = 60,
                requestedBitrate = 160_000_000,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 120_000_000 },
                clampBitrate = { it },
                computationalCinemaPlan = cinemaPlan
            )

            assertTrue(plan.useTenBit)
            assertTrue(plan.colorEncoding.main10Requested)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals("Original SDR (Rec.709 10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_usesStandardTierForSdr1080pWhenHighEndDisabled() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/avc",
                width = 1920,
                height = 1080,
                fps = 30,
                requestedBitrate = 72_000_000,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 32_000_000 },
                clampBitrate = { it },
                logEnabled = false
            )

            assertEquals(RecordingCodecCatalog.QualityTier.STANDARD, plan.qualityTier)
            assertEquals(
                RecordingCodecCatalog.iframeInterval(RecordingCodecCatalog.QualityTier.STANDARD),
                plan.iFrameIntervalSeconds,
                0.0001f
            )
            assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, plan.preferredBitrateModes.first())
        }
    }

    @Test
    fun resolvePlan_forCinemaMasterForcesCbrAndKeepsMasterBitrate() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 500_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 180_000_000 },
                clampBitrate = { it },
                cinemaMasteringEnabled = true
            )

            assertEquals(500_000_000, plan.effectiveBitrate)
            assertEquals(listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR), plan.preferredBitrateModes)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
        }
    }

    @Test
    fun resolvePlan_forEliteProSdr10PreservesSelectedRec709Primaries() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 120_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                eliteQualityTier = EliteVideoQualityTier.ELITE_PRO_SDR_10BIT,
                sceneQualityHints = RecordingEncoderSceneQualityHints(sceneComplexity = 0.72f)
            )

            assertTrue(plan.sdr10Mastering)
            assertTrue(plan.allIntraMastering)
            assertTrue(plan.contentAdaptiveBitrateGuard)
            assertTrue(plan.qpTuningEnabled)
            assertEquals(MediaFormat.COLOR_STANDARD_BT709, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertTrue(plan.colorEncoding.requiresTenBit)
            assertTrue(plan.colorEncoding.main10Requested)
            assertTrue(plan.useTenBit)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, plan.level)
            assertEquals(0f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR), plan.preferredBitrateModes)
            assertTrue(plan.effectiveBitrate >= 400_000_000)
            assertTrue(plan.effectiveBitrate <= 800_000_000)
            assertEquals("Pro SDR-10 (Rec.709 10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_forEliteProSdr10PreservesSelectedRec2020Primaries() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 120_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                eliteQualityTier = EliteVideoQualityTier.ELITE_PRO_SDR_10BIT
            )

            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertTrue(plan.colorEncoding.requiresTenBit)
            assertTrue(plan.colorEncoding.main10Requested)
            assertEquals("Pro SDR-10 (Rec.2020 10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_forManualEliteProSdrKeepsQpTuningGuardWithoutAllIntra() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 400_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                manualBitrateSelectionEnabled = true,
                eliteQualityTier = EliteVideoQualityTier.ELITE_PRO_SDR_10BIT
            )

            assertTrue(plan.sdr10Mastering)
            assertFalse(plan.allIntraMastering)
            assertTrue(plan.qpTuningEnabled)
            assertEquals(1f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(
                listOf(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ),
                plan.preferredBitrateModes
            )
        }
    }

    @Test
    fun resolvePlan_forEliteNormalSdrForcesAllIntraRec709Main10WithoutHdrPromotion() {
        withHighEndQuality(enabled = false) {
            val bitrateConfig = BitPerfectRecordingPolicy().resolveNormalSdrAllIntraConfig(1920, 1080)
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 1920,
                height = 1080,
                fps = 30,
                requestedBitrate = 120_000_000,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                eliteQualityTier = EliteVideoQualityTier.ELITE_NORMAL_SDR
            )

            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertEquals(0f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, plan.preferredBitrateModes.first())
            assertEquals(MediaFormat.COLOR_STANDARD_BT709, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertTrue(plan.colorEncoding.requiresTenBit)
            assertTrue(plan.colorEncoding.main10Requested)
            assertTrue(plan.useTenBit)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertTrue(plan.p010Required)
            assertEquals("P010", plan.preferredPixelFormat)
            assertTrue(plan.effectiveBitrate >= bitrateConfig.minimumBitrateBps)
            assertTrue(plan.effectiveBitrate <= bitrateConfig.maximumBitrateBps)
            assertFalse(plan.sdr10Mastering)
            assertTrue(plan.allIntraMastering)
            assertTrue(plan.contentAdaptiveBitrateGuard)
            assertTrue(plan.qpTuningEnabled)
            assertEquals("Normal SDR-10 (Rec.709 10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_forEliteNormalSdrPreservesSelectedRec2020Primaries() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3264,
                height = 2448,
                fps = 30,
                requestedBitrate = 220_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 120_000_000 },
                clampBitrate = { it },
                eliteQualityTier = EliteVideoQualityTier.ELITE_NORMAL_SDR
            )

            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertEquals(MediaFormat.COLOR_RANGE_FULL, plan.colorEncoding.colorRange)
            assertTrue(plan.colorEncoding.requiresTenBit)
            assertTrue(plan.colorEncoding.main10Requested)
            assertTrue(plan.useTenBit)
            assertTrue(plan.p010Required)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals("Normal SDR-10 (Rec.2020 10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_forEliteNormalSdrUsesFpsAwareStrictAllIntraFloorWithoutHdrPromotion() {
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.strictHighEndOnly = true
            withHighEndQuality(enabled = true) {
                val bitrateConfig = BitPerfectRecordingPolicy()
                    .resolveNormalSdrAllIntraConfig(3840, 2160, fps = 60)
                val expectedBitrate = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
                val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                    mime = "video/hevc",
                    width = 3840,
                    height = 2160,
                    fps = 60,
                    requestedBitrate = 120_000_000,
                    tenBitEnabled = false,
                    colorEncoding = RecordingColorEncodingConfig(
                        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                        colorRange = MediaFormat.COLOR_RANGE_FULL,
                        requiresTenBit = false,
                        main10Requested = false
                    ),
                    hardwareTenBitSupported = true,
                    av1Main10Profile = null,
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                    clampBitrate = { it },
                    eliteQualityTier = EliteVideoQualityTier.ELITE_NORMAL_SDR
                )

                assertTrue(bitrateConfig.minimumBitrateBps > expectedBitrate)
                assertEquals(expectedBitrate, plan.effectiveBitrate)
                assertEquals(MediaFormat.COLOR_STANDARD_BT709, plan.colorEncoding.colorStandard)
                assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
                assertTrue(plan.colorEncoding.requiresTenBit)
                assertTrue(plan.colorEncoding.main10Requested)
                assertTrue(plan.useTenBit)
                assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
                assertTrue(plan.p010Required)
                assertEquals("P010", plan.preferredPixelFormat)
                assertTrue(plan.allIntraMastering)
                assertTrue(plan.contentAdaptiveBitrateGuard)
            }
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun resolvePlan_forEliteNLogForcesBt2020P010Main10CbrAllIntraAndCabg() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 120_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                logEnabled = true,
                eliteQualityTier = EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT,
                sceneQualityHints = RecordingEncoderSceneQualityHints(sceneComplexity = 0.64f)
            )

            assertTrue(plan.nLogMastering)
            assertTrue(plan.allIntraMastering)
            assertTrue(plan.contentAdaptiveBitrateGuard)
            assertTrue(plan.qpTuningEnabled)
            assertTrue(plan.p010Required)
            assertEquals("P010", plan.preferredPixelFormat)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertTrue(plan.colorEncoding.requiresTenBit)
            assertTrue(plan.colorEncoding.main10Requested)
            assertTrue(plan.useTenBit)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, plan.level)
            assertEquals(0f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR), plan.preferredBitrateModes)
            assertTrue(plan.effectiveBitrate >= 400_000_000)
            assertTrue(plan.effectiveBitrate <= 800_000_000)
            assertEquals("N-LOG v5 APEX-LOG (Rec.2020 P010 10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_forEliteNLogStaysMain10WhenDeviceReportsMain12Profile() {
        withHighEndQuality(enabled = false) {
            val hevcMain12Profile = 123_456
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 120_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                hevcMain12Profile = hevcMain12Profile,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                logEnabled = true,
                eliteQualityTier = EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT,
                sceneQualityHints = RecordingEncoderSceneQualityHints(sceneComplexity = 0.64f)
            )

            assertTrue(plan.nLogMastering)
            assertTrue(plan.useTenBit)
            assertFalse(plan.useTwelveBit)
            assertTrue(plan.p010Required)
            assertEquals("P010", plan.preferredPixelFormat)
            assertEquals("Hardware 10-bit", plan.activeBitDepthLabel)
            assertEquals("N-LOG v5 APEX-LOG (Rec.2020 P010 10-bit)", plan.dynamicRangeLabel)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals("HEVC_MAIN10_SUPPORTED", plan.encoderQualityTelemetry.capabilityCheck)
        }
    }

    @Test
    fun contentAdaptiveBitrateGuardUsesSmoothTanhCeilingAndQpDetailDelta() {
        val calm = RecordingVideoEncoderPlanResolver.resolveContentAdaptiveBitrate(
            baseBitrate = 400_000_000,
            spatialComplexity = 0f,
            motionMagnitude = 0f
        )
        val complex = RecordingVideoEncoderPlanResolver.resolveContentAdaptiveBitrate(
            baseBitrate = 400_000_000,
            spatialComplexity = 1f,
            motionMagnitude = 1f
        )

        assertEquals(400_000_000, calm)
        assertTrue(complex > calm)
        assertTrue(complex <= 740_000_000)
        assertTrue(
            RecordingVideoEncoderPlanResolver.resolveMacroblockQpDelta(
                localVariance = 640f,
                frameVariance = 160f
            ) < 0f
        )
    }

    @Test
    fun resolvePlan_appliesMaximumComputationalCinemaMain10AndBitrateIntent() {
        withHighEndQuality(enabled = false) {
            val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
                request = ComputationalCinemaRequest(
                    mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                    quality = ComputationalCinemaQuality.MAXIMUM,
                    zoomRatio = 2.0f,
                    recording = true
                ),
                hardware = ComputationalCinemaHardware(
                    supportsRaw = true,
                    supportsYuvReprocessing = true,
                    supportsHdrVideo = true,
                    supportsTenBit = true,
                    supportsHevcMain10 = true,
                    supportsOis = true,
                    supportsVideoStabilization = true,
                    supportsVulkan = true,
                    supportsZeroCopyGpuImport = true,
                    supportsAiSuperResolution = true,
                    maxEncoderLongEdge = 7680,
                    maxSensorLongEdge = 8192
                ),
                runtime = ComputationalCinemaRuntime(
                    previewFps = 60f,
                    targetFps = 60,
                    thermalSeverity = 0,
                    droppedFrames = 0,
                    encoderPressure = 0.10f,
                    estimatedLux = 120f,
                    motionScore = 0.04f
                )
            )

            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 120_000_000,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                computationalCinemaPlan = cinemaPlan
            )

            assertTrue(plan.useTenBit)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertTrue(plan.effectiveBitrate > 250_000_000)
            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
        }
    }

    @Test
    fun resolvePlan_strictHighEndMaxBitrateSaturatesIntoCodecCeilingWithoutOverflow() {
        withHighEndQuality(enabled = true) {
            val codecCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = codecCeiling,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 500_000_000 },
                clampBitrate = { requested -> requested.coerceAtMost(codecCeiling) }
            )

            assertEquals(codecCeiling, plan.effectiveBitrate)
            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, plan.preferredBitrateModes.first())
            assertEquals(RecordingCodecCatalog.iframeInterval(RecordingCodecCatalog.QualityTier.CINEMA), plan.iFrameIntervalSeconds)
        }
    }

    @Test
    fun resolvePlan_strictNdrHdrLocksCinemaQpTuningGuard() {
        withStrictHighEnd {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 60,
                requestedBitrate = 600_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 500_000_000 },
                clampBitrate = { it },
                requestedDynamicRangeLabel = "NDR10+"
            )

            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertTrue(plan.qpTuningEnabled)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
            assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, plan.preferredBitrateModes.first())
        }
    }

    @Test
    fun resolvePlan_strictLogWithoutEliteTierLocksCinemaQpTuningGuard() {
        withStrictHighEnd {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 420_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 300_000_000 },
                clampBitrate = { it },
                logEnabled = true,
                requestedDynamicRangeLabel = "N-Log Cinema"
            )

            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertTrue(plan.qpTuningEnabled)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, plan.profile)
        }
    }

    @Test
    fun resolvePlan_extremeDetailFallbackPromotesSdrToCinemaQpTuning() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 1920,
                height = 1080,
                fps = 30,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it.coerceAtMost(160_000_000) },
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
                requestedDynamicRangeLabel = "Normal SDR-10"
            )

            assertEquals(RecordingCodecCatalog.QualityTier.CINEMA, plan.qualityTier)
            assertTrue(plan.qpTuningEnabled)
            assertEquals(0.10f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                plan.preferredBitrateModes.first()
            )
        }
    }

    @Test
    fun resolvePlan_boostsBitrateForComplexLowLightHdrAndLogsEncoderTelemetry() {
        withHighEndQuality(enabled = false) {
            val requestedBitrate = 120_000_000
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = requestedBitrate,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                sceneQualityHints = RecordingEncoderSceneQualityHints(
                    sceneComplexity = 0.82f,
                    estimatedLux = 4.0f,
                    droppedFrames = 2,
                    thermalSeverity = 4
                ),
                requestedDynamicRangeLabel = "NDR10+"
            )

            assertTrue(plan.effectiveBitrate > requestedBitrate)
            assertTrue(plan.encoderQualityTelemetry.sceneComplexityBoostApplied)
            assertTrue(plan.encoderQualityTelemetry.lowLightBoostApplied)
            assertTrue(plan.encoderQualityTelemetry.hdrOrLogBoostApplied)
            assertTrue(plan.encoderQualityTelemetry.thermalDowngradeActive)
            assertEquals(2, plan.encoderQualityTelemetry.droppedFrames)
            assertTrue(plan.encoderQualityTelemetry.logLines.any { it.contains("droppedFrames=2") })
            assertTrue(plan.encoderQualityTelemetry.logLines.any { it.contains("thermalDowngrade=true") })
        }
    }

    @Test
    fun resolvePlan_keepsThermalDowngradeOffForStrictHighEndNoDowngrade() {
        withStrictHighEnd {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 120_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it },
                sceneQualityHints = RecordingEncoderSceneQualityHints(thermalSeverity = 4),
                requestedDynamicRangeLabel = "NDR10+"
            )

            assertFalse(plan.encoderQualityTelemetry.thermalDowngradeActive)
            assertTrue(plan.encoderQualityTelemetry.logLines.any { it.contains("thermalDowngrade=false") })
        }
    }

    private inline fun withStrictHighEnd(block: () -> Unit) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun resolvePlan_reportsCapabilityCheckedMain10FallbackInsteadOfForcingUnsupportedProfile() {
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it }
            )

            assertFalse(plan.useTenBit)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, plan.profile)
            assertEquals("MAIN10_UNSUPPORTED_FALLBACK_TO_8BIT", plan.encoderQualityTelemetry.capabilityCheck)
            assertTrue(plan.encoderQualityTelemetry.logLines.any { it.contains("capabilityCheck=MAIN10_UNSUPPORTED_FALLBACK_TO_8BIT") })
        }
    }

    @Test
    fun resolvePlan_manualBitrateSelectionIsNotMultipliedByAutomaticQualityPolicy() {
        withHighEndQuality(enabled = true) {
            val selectedBitrate = 180_000_000
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 1920,
                height = 1080,
                fps = 30,
                requestedBitrate = selectedBitrate,
                tenBitEnabled = false,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 18_000_000 },
                clampBitrate = { it },
                manualBitrateSelectionEnabled = true,
                sceneQualityHints = RecordingEncoderSceneQualityHints(
                    motionRisk = 0.88f,
                    noiseRisk = 0.80f,
                    sourceQualityTier = "MOTION_STABLE_LOW_NOISE_DETAIL",
                    sourceQualityBudgetMultiplier = 1.35,
                    antiMotionBlurEnabled = true
                )
            )

            assertEquals(selectedBitrate, plan.effectiveBitrate)
            assertEquals(1f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                plan.preferredBitrateModes.first()
            )
        }
    }

    @Test
    fun resolvePlan_appliesV20SourcePressureFloorToAutomaticBitrate() {
        withHighEndQuality(enabled = false) {
            fun resolve(hints: RecordingEncoderSceneQualityHints) =
                RecordingVideoEncoderPlanResolver.resolvePlan(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    requestedBitrate = 20_000_000,
                    tenBitEnabled = false,
                    colorEncoding = RecordingColorEncodingConfig(
                        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                        colorRange = MediaFormat.COLOR_RANGE_FULL,
                        requiresTenBit = false,
                        main10Requested = false
                    ),
                    hardwareTenBitSupported = false,
                    av1Main10Profile = null,
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    minimumBitrateFloorFor = { _, _ -> 18_000_000 },
                    clampBitrate = { it.coerceAtMost(800_000_000) },
                    sceneQualityHints = hints,
                    videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
                )

            val baseline = resolve(RecordingEncoderSceneQualityHints.EMPTY)
            val pressured = resolve(
                RecordingEncoderSceneQualityHints(
                    motionRisk = 0.84f,
                    noiseRisk = 0.76f,
                    sourceQualityTier = "MOTION_STABLE_LOW_NOISE_DETAIL",
                    sourceQualityBudgetMultiplier = 1.32,
                    antiMotionBlurEnabled = true
                )
            )

            assertTrue(pressured.effectiveBitrate > baseline.effectiveBitrate)
            assertTrue(
                pressured.encoderQualityTelemetry.logLines.any {
                    it.contains("V20 source quality budget") &&
                        it.contains("MOTION_STABLE_HIGH_NOISE_DETAIL")
                }
            )
        }
    }

    @Test
    fun resolvePlan_manualHdrBitrateSelectionIsNotRaisedToAutomaticHighEndFloor() {
        withHighEndQuality(enabled = true) {
            val selectedBitrate = 80_000_000
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 1920,
                height = 1080,
                fps = 30,
                requestedBitrate = selectedBitrate,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                hardwareTenBitSupported = true,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 340_000_000 },
                clampBitrate = { requested -> requested.coerceAtMost(160_000_000) },
                manualBitrateSelectionEnabled = true,
                requestedDynamicRangeLabel = "NDR10+"
            )

            assertEquals(selectedBitrate, plan.effectiveBitrate)
            assertEquals(1f, plan.iFrameIntervalSeconds, 0.0001f)
            assertEquals(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                plan.preferredBitrateModes.first()
            )
            assertEquals("NDR10+ BT.2100 PQ (10-bit)", plan.dynamicRangeLabel)
        }
    }

    @Test
    fun resolvePlan_forcedTenBitSdrOnNonTenBitHardwareStaysSdrWithHonestFallback() {
        // Unified force-10-bit contract: when the universal 10-bit flag forces tenBitEnabled=true
        // for a plain Rec.709 SDR clip on hardware that cannot back 10-bit HEVC, the resolver must
        // (1) honestly report simulatedTenBitFallback (the elite hard-error signal upstream), and
        // (2) NEVER promote SDR into an HDR transfer. The live path avoids this by gating the force
        // on supports10Bit(), but the resolver must stay honest if it is ever reached.
        withHighEndQuality(enabled = false) {
            val plan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 30,
                requestedBitrate = 160_000_000,
                tenBitEnabled = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = false
                ),
                hardwareTenBitSupported = false,
                av1Main10Profile = null,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                minimumBitrateFloorFor = { _, _ -> 80_000_000 },
                clampBitrate = { it }
            )

            assertFalse(plan.useTenBit)
            assertTrue(plan.simulatedTenBitFallback)
            // SDR stays SDR — no ST2084/HLG promotion, no Main10 profile forced on 8-bit hardware.
            assertEquals(MediaFormat.COLOR_STANDARD_BT709, plan.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, plan.colorEncoding.colorTransfer)
            assertFalse(plan.colorEncoding.main10Requested)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, plan.profile)
        }
    }
}
