package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEncoderFormatPolicyTest {

    @Test
    fun hdr10HevcFormatCarries10BitColorAndHdrMetadataMode() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        assertEquals("video/hevc", result.spec.mime)
        assertEquals(3840, result.spec.width)
        assertEquals(2160, result.spec.height)
        assertEquals(300_000_000, result.spec.bitrate)
        assertEquals(30, result.spec.fps)
        assertEquals(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            result.spec.colorFormat
        )
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, result.spec.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, result.spec.colorTransfer)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, result.spec.colorRange)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HDR10, result.staticMetadataMode)
    }

    @Test
    fun hlgFormatDoesNotAttachHdr10StaticMetadata() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                )
            )
        )

        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HLG, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun hdr10PlusFormatUsesSceneAdaptiveMasteringMetadata() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                hdr10PlusRequested = true,
                hdrSceneMetadata = HdrStaticMetadataPolicy.SceneMetadata(
                    averageLuma = 0.40,
                    highlightRatio = 0.18,
                    dynamicRangeScore = 0.86
                )
            )
        )

        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HDR10_PLUS, result.staticMetadataMode)
        assertEquals(
            HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_4000_NITS.maxLuminanceNits,
            result.masteringDisplay?.maxLuminanceNits ?: 0.0,
            1e-6
        )
        assertTrue((result.masteringDisplay?.maxContentLightLevelNits ?: 0) > 1000)
    }

    @Test
    fun dolbyFallbackFormatUsesDolbyFallbackMetadataMode() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                certifiedVendorFallbackRequested = true,
                hdrSceneMetadata = HdrStaticMetadataPolicy.SceneMetadata(
                    averageLuma = 0.36,
                    highlightRatio = 0.22,
                    dynamicRangeScore = 0.90
                )
            )
        )

        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK, result.staticMetadataMode)
        assertEquals(
            HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_10000_NITS.maxLuminanceNits,
            result.masteringDisplay?.maxLuminanceNits ?: 0.0,
            1e-6
        )
        assertTrue((result.masteringDisplay?.maxContentLightLevelNits ?: 0) >= 3000)
    }

    @Test
    fun sdrMain10FormatKeepsRec709TransferAndNoHdrMetadata() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT709, result.spec.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransferRequest)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun rec2020SdrP010FormatCarriesWideGamutSdrKeysWithoutHdrMetadata() {
        val rec2020Sdr = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_FULL,
            requiresTenBit = true,
            main10Requested = true
        )
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = rec2020Sdr,
                outputColorEncoding = rec2020Sdr,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                inputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            )
        )

        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010, result.spec.colorFormat)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, result.spec.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransferRequest)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, result.spec.colorRange)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun rec709SdrOnP010TenBitFeedKeepsSdrTransferAndNoHdrMetadata() {
        // Unified force-10-bit invariant: SDR forced onto a 10-bit P010 feed (narrowest Rec.709
        // gamut) must stay SDR transfer with no HDR static metadata. 10-bit transport must never
        // silently promote SDR into HDR.
        val rec709Sdr = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_FULL,
            requiresTenBit = true,
            main10Requested = true
        )
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = rec709Sdr,
                outputColorEncoding = rec709Sdr,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                inputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            )
        )

        assertEquals(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010, result.spec.colorFormat)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, result.spec.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransferRequest)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun sdrCodecInputKeepsSdrOutputMetadata() {
        val sdrOutput = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = true
        )
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingCodecInputColorPolicy.resolve(
                    mime = "video/hevc",
                    outputColorEncoding = sdrOutput
                ),
                outputColorEncoding = sdrOutput,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT709, result.spec.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun maxQualityFormatKeepsFullBitrateQualityAndCodecMode() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                bitrate = 1_200_000_000,
                iFrameIntervalSeconds = 0.10f,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                encoderComplexity = 10,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA
            )
        )

        assertEquals(1_200_000_000, result.spec.bitrate)
        assertEquals(1_200_000_000, result.spec.maxBitrate)
        assertEquals(100, result.spec.quality)
        assertEquals(1f, result.spec.iFrameIntervalSeconds, 0.0001f)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ, result.spec.bitrateMode)
        assertEquals(10, result.spec.encoderComplexity)
    }

    @Test
    fun maxQualityFormatCarriesCodecLevelWhenProvided() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA
            )
        )

        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, result.spec.profile)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51, result.spec.level)
    }

    @Test
    fun maxQualityFormatAppliesEncoderQuantizationAndReorderingTuning() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                bitrate = 2_000_000_000,
                iFrameIntervalSeconds = 0.10f,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                )
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(0, tuning.qpMin)
        assertEquals(6, tuning.qpMax)
        assertEquals(0, tuning.iFrameQpMin)
        assertEquals(6, tuning.iFrameQpMax)
        assertEquals(1, tuning.pFrameQpMin)
        assertEquals(6, tuning.pFrameQpMax)
        assertEquals(2, tuning.bFrameQpMin)
        assertEquals(6, tuning.bFrameQpMax)
        assertEquals(0, tuning.maxBFrames)
        assertEquals(1, tuning.lowLatency)
        assertEquals(1, tuning.latencyFrames)
        assertEquals(1, result.spec.latencyFrames)
        assertEquals(0, result.spec.allowFrameDrop)
        assertEquals(null, tuning.intraRefreshPeriodFrames)
    }

    @Test
    fun cinemaSdrFormatUsesTighterQuantizationWithoutChangingSdrMetadata() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                bitrate = 900_000_000,
                iFrameIntervalSeconds = 0.10f,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = false,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(6, tuning.qpMax)
        assertEquals(6, tuning.iFrameQpMax)
        assertEquals(6, tuning.pFrameQpMax)
        assertEquals(6, tuning.bFrameQpMax)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, result.spec.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun av1MaxQualityFormatAppliesQuantizationTuningAcrossStrictModes() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                mime = "video/av01",
                bitrate = 1_600_000_000,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                )
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(0, tuning.qpMin)
        assertEquals(6, tuning.qpMax)
        assertEquals(0, tuning.iFrameQpMin)
        assertEquals(6, tuning.iFrameQpMax)
        assertEquals(1, tuning.pFrameQpMin)
        assertEquals(6, tuning.pFrameQpMax)
        assertEquals(2, tuning.bFrameQpMin)
        assertEquals(6, tuning.bFrameQpMax)
        assertEquals(null, tuning.maxBFrames)
        assertEquals(1, tuning.lowLatency)
        assertEquals(null, tuning.intraRefreshPeriodFrames)
    }

    @Test
    fun allIntraCinemaFormatDisablesBFramesAndKeepsEliteQuantization() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                bitrate = 2_000_000_000,
                iFrameIntervalSeconds = 0f,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(6, tuning.qpMax)
        assertEquals(0, tuning.maxBFrames)
        assertEquals(1, tuning.lowLatency)
        assertEquals(null, tuning.intraRefreshPeriodFrames)
    }

    @Test
    fun oreoFormatKeepsOneFrameLatencyWhenOtherRealtimeKnobsAreUnavailable() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                sdkInt = Build.VERSION_CODES.O,
                iFrameIntervalSeconds = 0f
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(null, tuning.maxBFrames)
        assertEquals(null, tuning.lowLatency)
        assertEquals(1, tuning.latencyFrames)
        assertEquals(1, result.spec.latencyFrames)
        assertEquals(null, tuning.intraRefreshPeriodFrames)
    }

    @Test
    fun realtimeCinemaSurfaceRelaxesImpossibleQuantizerCeilingWhenEncoderBitrateIsCapped() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                width = 3264,
                height = 2448,
                bitrate = 160_000_000,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertTrue(tuning.qpMax ?: 0 > 6)
        assertEquals(12, tuning.qpMax)
        assertEquals(12, tuning.iFrameQpMax)
        assertEquals(12, tuning.pFrameQpMax)
        assertEquals(12, tuning.bFrameQpMax)
        assertEquals(0, tuning.maxBFrames)
        assertEquals(1, tuning.lowLatency)
    }

    @Test
    fun directGallery4k25Main10UsesStrictCbrWithoutCompetingQuantizerController() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                width = 4096,
                height = 3072,
                fps = 25,
                bitrate = 160_000_000,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                strictBitrateTarget = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(4096, result.spec.width)
        assertEquals(3072, result.spec.height)
        assertEquals(25, result.spec.fps)
        assertEquals(160_000_000, result.spec.bitrate)
        assertEquals(160_000_000, result.spec.maxBitrate)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, result.spec.profile)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, result.spec.bitrateMode)
        assertEquals(null, result.spec.quality)
        assertEquals(null, tuning.qpMin)
        assertEquals(null, tuning.qpMax)
        assertEquals(null, tuning.iFrameQpMin)
        assertEquals(null, tuning.iFrameQpMax)
        assertEquals(null, tuning.pFrameQpMin)
        assertEquals(null, tuning.pFrameQpMax)
        assertEquals(null, tuning.bFrameQpMin)
        assertEquals(null, tuning.bFrameQpMax)
        assertEquals(0, tuning.maxBFrames)
        assertEquals(1, tuning.lowLatency)
        assertEquals(1, tuning.latencyFrames)
        assertEquals(0, result.spec.allowFrameDrop)
    }

    @Test
    fun directGalleryVbrFallbackDoesNotAddNearLosslessQuantizerController() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                width = 3840,
                height = 2160,
                fps = 25,
                bitrate = 160_000_000,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                strictBitrateTarget = true,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(160_000_000, result.spec.bitrate)
        assertEquals(160_000_000, result.spec.maxBitrate)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, result.spec.bitrateMode)
        assertEquals(null, result.spec.quality)
        assertEquals(null, tuning.qpMin)
        assertEquals(null, tuning.qpMax)
        assertEquals(null, tuning.iFrameQpMin)
        assertEquals(null, tuning.iFrameQpMax)
        assertEquals(null, tuning.pFrameQpMin)
        assertEquals(null, tuning.pFrameQpMax)
        assertEquals(null, tuning.bFrameQpMin)
        assertEquals(null, tuning.bFrameQpMax)
        assertEquals(0, tuning.maxBFrames)
        assertEquals(0, result.spec.allowFrameDrop)
    }

    @Test
    fun proSdrMain10CappedCinemaFormatKeepsEliteRealtimeQuantization() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                width = 3840,
                height = 2160,
                fps = 60,
                bitrate = 400_000_000,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(12, tuning.qpMax)
        assertEquals(12, tuning.iFrameQpMax)
        assertEquals(12, tuning.pFrameQpMax)
        assertEquals(12, tuning.bFrameQpMax)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
        assertFalse(result.staticMetadataAttached)
    }

    @Test
    fun p010ByteBufferFormatDeclaresMaxInputSize() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                width = 1920,
                height = 1080,
                inputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
                inputMaxSizeBytes = 6_220_800
            )
        )

        assertEquals(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
            result.spec.colorFormat
        )
        assertEquals(6_220_800, result.spec.inputMaxSizeBytes)
    }

    @Test
    fun encoderQualityTuningCanBeDisabledForCompatibilityRetry() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
                advancedQualityTuningEnabled = false
            )
        )

        assertEquals(null, result.spec.qualityTuning)
    }

    @Test
    fun compatibilityRetryRequestDisablesAdvancedQualityTuningOnce() {
        val original = request(
            qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
            advancedQualityTuningEnabled = true
        )
        val retry = RecordingEncoderFormatPolicy.compatibilityRetryRequest(original)

        requireNotNull(retry)
        assertFalse(retry.advancedQualityTuningEnabled)
        assertEquals(null, RecordingEncoderFormatPolicy.compatibilityRetryRequest(retry))
    }

    @Test
    fun encoderQualityTuningSkipsApi31QuantizersOnOlderRuntime() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                sdkInt = Build.VERSION_CODES.R,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA
            )
        )

        val tuning = requireNotNull(result.spec.qualityTuning)
        assertEquals(null, tuning.qpMin)
        assertEquals(null, tuning.qpMax)
        assertEquals(0, tuning.maxBFrames)
        assertEquals(1, tuning.lowLatency)
        assertEquals(null, tuning.intraRefreshPeriodFrames)
    }

    @Test
    fun unknownEncoderComplexityDoesNotWriteBitrateModeConstantAsComplexity() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(encoderComplexity = null)
        )

        assertEquals(null, result.spec.encoderComplexity)
    }

    @Test
    fun allIntraResultUpdatesBitrateAndIntervalOnlyWhenApplied() {
        val result = RecordingEncoderFormatPolicy.resolveAllIntraResult(
            requested = true,
            mime = "video/hevc",
            fps = 30,
            videoPlanUseTenBit = true,
            probed = AllIntraHevcPolicy.AllIntraConfig(
                bitrate = 500_000_000,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5,
                frameRate = 30
            ),
            clampedBitrate = 450_000_000,
            applyResult = true
        )

        assertTrue(result.active)
        assertEquals(450_000_000, result.outputBitrate)
        assertEquals(1f / 30f, result.iFrameIntervalSeconds, 0.0001f)
        assertEquals("ALL_INTRA", result.qualityTierLabel)
        assertEquals(450_000_000, result.appliedConfig?.bitrate)
        assertTrue(result.tenBit)
    }

    @Test
    fun allIntraUnsupportedRemainsHonestFallback() {
        val result = RecordingEncoderFormatPolicy.resolveAllIntraResult(
            requested = true,
            mime = "video/hevc",
            fps = 30,
            videoPlanUseTenBit = true,
            probed = null,
            clampedBitrate = 300_000_000,
            applyResult = false
        )

        assertFalse(result.active)
        assertEquals(300_000_000, result.outputBitrate)
        assertEquals(null, result.qualityTierLabel)
        assertEquals(null, result.appliedConfig)
    }

    @Test
    fun allIntraIgnoredWhenContainerIsNotRequestedHevc() {
        val result = RecordingEncoderFormatPolicy.resolveAllIntraResult(
            requested = true,
            mime = "video/avc",
            fps = 30,
            videoPlanUseTenBit = false,
            probed = AllIntraHevcPolicy.AllIntraConfig(
                bitrate = 200_000_000,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5,
                frameRate = 30
            ),
            clampedBitrate = 180_000_000,
            applyResult = true
        )

        assertFalse(result.active)
        assertEquals(180_000_000, result.outputBitrate)
    }

    private fun request(
        mime: String = "video/hevc",
        width: Int = 3840,
        height: Int = 2160,
        colorEncoding: RecordingColorEncodingConfig = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_FULL,
            requiresTenBit = false,
            main10Requested = false
        ),
        outputColorEncoding: RecordingColorEncodingConfig = colorEncoding,
        profile: Int? = null,
        level: Int? = null,
        fps: Int = 30,
        bitrate: Int = 300_000_000,
        iFrameIntervalSeconds: Float = 1f,
        bitrateMode: Int? = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        encoderComplexity: Int? = 10,
        qualityTier: RecordingCodecCatalog.QualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
        advancedQualityTuningEnabled: Boolean = true,
        strictBitrateTarget: Boolean = false,
        sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        hdr10PlusRequested: Boolean = false,
        certifiedVendorFallbackRequested: Boolean = false,
        inputColorFormat: Int? = null,
        inputMaxSizeBytes: Int? = null,
        hdrSceneMetadata: HdrStaticMetadataPolicy.SceneMetadata = HdrStaticMetadataPolicy.SceneMetadata.DEFAULT
    ) = RecordingEncoderFormatRequest(
        mime = mime,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
        iFrameIntervalSeconds = iFrameIntervalSeconds,
        colorEncoding = colorEncoding,
        bitrateMode = bitrateMode,
        encoderComplexity = encoderComplexity,
        operatingRate = 90,
        profile = profile,
        level = level,
        qualityTier = qualityTier,
        advancedQualityTuningEnabled = advancedQualityTuningEnabled,
        strictBitrateTarget = strictBitrateTarget,
        sdkInt = sdkInt,
        outputColorEncoding = outputColorEncoding,
        hdr10PlusRequested = hdr10PlusRequested,
        certifiedVendorFallbackRequested = certifiedVendorFallbackRequested,
        inputColorFormat = inputColorFormat,
        inputMaxSizeBytes = inputMaxSizeBytes,
        hdrSceneMetadata = hdrSceneMetadata
    )
}
