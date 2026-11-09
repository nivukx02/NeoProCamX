package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NormalSdrVideoQualityScorecardPolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EliteVideoQualityContractTest {

    @org.junit.Before
    fun setUp() {
        com.neoproc.neoproccamx.core.NdrFeatureGate.enabled = true
    }

    @Test
    fun resolveKeepsNormalProRawAndNpv10AsExplicitEliteModes() {
        val normal = EliteVideoQualityContract.resolve(
            baseRequest(mode = EliteVideoPipelineMode.NORMAL)
        )
        val pro = EliteVideoQualityContract.resolve(
            baseRequest(mode = EliteVideoPipelineMode.PRO, tenBitTransport = true, sourceBitDepth = 10)
        )
        val raw = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.RAW,
                rawBitDepth = 12,
                sourceBitDepth = 12,
                storageBitDepth = 12,
                tenBitTransport = false
            )
        )
        val npv10 = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true
            )
        )

        assertEquals(EliteVideoQualityTier.ELITE_NORMAL_SDR, normal.qualityTier)
        assertEquals(EliteVideoQualityTier.ELITE_PRO_SDR_10BIT, pro.qualityTier)
        assertEquals(EliteVideoQualityTier.ELITE_RAW_12BIT, raw.qualityTier)
        assertEquals(EliteVideoQualityTier.ELITE_NPV10_NATIVE_P010, npv10.qualityTier)
        assertTrue(listOf(normal, pro, raw, npv10).all { it.auditStatus == EliteQualityAuditStatus.PASS })
    }

    @Test
    fun resolveMapsCompatibleHdrRequestsToNeoProCamXHdrProfiles() {
        val expected = mapOf(
            "HLG" to EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            "HDR10" to EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            "HDR10+" to EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            "NDR10+" to EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            "NIVUKXVISION" to EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
        )

        expected.forEach { (hdrMode, tier) ->
            val certificate = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    hdrMode = hdrMode,
                    colorSpace = "Rec.2020",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )

            assertTrue(certificate.normalizedHdrVersion == "NDR10+" || certificate.normalizedHdrVersion == "NIVUKXVISION")
            assertEquals(tier, certificate.qualityTier)
            assertTrue("$hdrMode should require 10-bit", certificate.requiresTenBitTransport)
            assertEquals(EliteQualityAuditStatus.PASS, certificate.auditStatus)
        }
    }

    @Test
    fun resolveMapsCompatibleLogRequestsToNeoProCamXNLogFamily() {
        val expected = mapOf(
            "N-Log Base" to EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT,
            "N-Log Shadow" to EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT,
            "N-Log Highlight" to EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT,
            "N-Log Cinema" to EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT,
            "External Log Alias" to EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT,
            "External Cinema Gamut" to EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT
        )

        expected.forEach { (profile, tier) ->
            val certificate = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.LOG,
                    logProfile = profile,
                    colorSpace = "Rec.2020-LOG",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )

            assertTrue(certificate.normalizedLogVersion.startsWith("N-Log"))
            assertEquals(tier, certificate.qualityTier)
            assertTrue("$profile should require 10-bit", certificate.requiresTenBitTransport)
            assertEquals(EliteQualityAuditStatus.PASS, certificate.auditStatus)
        }
    }

    @Test
    fun resolveDoesNotClaimEliteHdrOrLogWithoutRealTenBitTransport() {
        val hdr = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "HDR10+",
                colorSpace = "Rec.2020",
                sourceBitDepth = 8,
                storageBitDepth = 8,
                tenBitTransport = false
            )
        )
        val log = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.LOG,
                logProfile = "N-Log Cinema",
                colorSpace = "Rec.2020-LOG",
                sourceBitDepth = 8,
                storageBitDepth = 8,
                tenBitTransport = false
            )
        )

        assertEquals(EliteVideoQualityTier.FAILED_UNSAFE, hdr.qualityTier)
        assertEquals(EliteVideoQualityTier.FAILED_UNSAFE, log.qualityTier)
        assertEquals(EliteQualityAuditStatus.FAIL, hdr.auditStatus)
        assertEquals(EliteQualityAuditStatus.FAIL, log.auditStatus)
        assertTrue(hdr.failureReasons.any { it.contains("10-bit") })
        assertTrue(log.failureReasons.any { it.contains("10-bit") })
    }

    @Test
    fun metadataIsTruthfulForNpv10EightBitFallbackAndConvertedBitrateFloor() {
        val fallback = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 8,
                storageBitDepth = 10,
                sourceFormat = "YUV_420_888",
                payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
                tenBitTransport = false
            )
        )
        val metadata = fallback.toMetadata()

        assertEquals(EliteVideoQualityTier.HIGH_NPV10_8BIT_EXPANDED, fallback.qualityTier)
        assertEquals(EliteQualityAuditStatus.PASS, fallback.auditStatus)
        assertFalse(metadata.getValue("truthfulNative10Bit").toBoolean())
        assertEquals("YUV420_8BIT_EXPANDED", metadata.getValue("payloadFormat"))
        assertTrue(fallback.minimumBitrateBps > 0)
    }

    @Test
    fun ndr10NivukxAndNLogExposeTruthfulMetadata() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = false
            HighEndQualityPolicy.strictHighEndOnly = false
            val ndr10Plus = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "NDR10+",
                    colorSpace = "Rec.2020-NDR10+",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val nivukx = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "NIVUKX Vision",
                    colorSpace = "Rec.2020-NIVUKX",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val nLog = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.LOG,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    logProfile = "N-Log Cinema",
                    colorSpace = "Rec.2020-N-Log",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )

            assertEquals("NDR10+", ndr10Plus.toMetadata().getValue("normalizedHdrVersion"))
            assertEquals("NIVUKXVISION", nivukx.toMetadata().getValue("normalizedHdrVersion"))
            assertEquals("N-Log Cinema", nLog.toMetadata().getValue("normalizedLogVersion"))
            assertEquals(EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT, ndr10Plus.qualityTier)
            assertEquals(EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT, nivukx.qualityTier)
            assertEquals(EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT, nLog.qualityTier)
            assertTrue(nivukx.minimumBitrateBps >= ndr10Plus.minimumBitrateBps)
            assertTrue(nLog.minimumBitrateBps > ndr10Plus.minimumBitrateBps)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun apexHdrProfilesKeepEliteNivukxApexIdentityMetadataAndBitrateHeadroom() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = false
            HighEndQualityPolicy.strictHighEndOnly = false
            val ndr10Plus = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "NDR10+",
                    colorSpace = "Rec.2020-NDR10+",
                    colorScience = "NDR10+",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val nivukx = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "NIVUKX Vision",
                    colorSpace = "Rec.2020-NIVUKX",
                    colorScience = "NIVUKX Vision",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val apexSharp = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "APEX Sharp",
                    colorSpace = "Rec.2020-NIVUKX-APEX",
                    colorScience = "NIVUKX APEX SHARP",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val apexZero = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "APEX Zero",
                    colorSpace = "Rec.2020-NIVUKX-APEX",
                    colorScience = "NIVUKX APEX ZERO",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val apexCinema = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    hdrMode = "APEX Cinema",
                    colorSpace = "Rec.2020-NIVUKX-APEX",
                    colorScience = "NIVUKX APEX CINEMA",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )

            listOf(
                apexSharp to "APEX_SHARP",
                apexZero to "APEX_ZERO",
                apexCinema to "APEX_CINEMA"
            ).forEach { (certificate, expectedVersion) ->
                val metadata = certificate.toMetadata()

                assertEquals(EliteQualityAuditStatus.PASS, certificate.auditStatus)
                assertEquals(EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT, certificate.qualityTier)
                assertEquals(expectedVersion, certificate.normalizedHdrVersion)
                assertEquals("true", metadata.getValue("neoHdrPrivateSidecarRequired"))
                assertEquals("NEO_PRIVATE_SIDECAR", metadata.getValue("neoHdrMetadataCarrier"))
                assertEquals("10000", metadata.getValue("neoHdrMasteringPeakNits"))
                assertEquals("10000", metadata.getValue("neoHdrTargetPeakNits"))
                assertEquals("false", metadata.getValue("vendorHdrCertification"))
                assertEquals("true", metadata.getValue("proprietaryProfilePreserved"))
            }
            assertTrue(apexSharp.minimumBitrateBps > nivukx.minimumBitrateBps)
            assertTrue(apexCinema.minimumBitrateBps > ndr10Plus.minimumBitrateBps)
            assertTrue(apexZero.minimumBitrateBps >= nivukx.minimumBitrateBps)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun metadataReportsTruthfulNeoHdrTransportAndInternalMathDepth() {
        val nivukx = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "NIVUKX Vision",
                colorSpace = "Rec.2020-NIVUKX",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            )
        ).toMetadata()

        assertEquals("NIVUKX Vision", nivukx.getValue("selectedProfile"))
        assertEquals("BT.2100_PQ_MAIN10", nivukx.getValue("activeTransport"))
        assertEquals("ST2084/PQ", nivukx.getValue("transfer"))
        assertEquals("BT.2020", nivukx.getValue("primaries"))
        assertEquals("BT.2020-NCL", nivukx.getValue("matrix"))
        assertEquals("10", nivukx.getValue("encodedBitDepth"))
        assertEquals("12", nivukx.getValue("internalMathBitDepth"))
        assertEquals("false", nivukx.getValue("dynamicMetadataWritten"))
        assertEquals("false", nivukx.getValue("npv10MasterAvailable"))
    }

    @Test
    fun neoHdrQualityMetadataUsesPrivateSidecarInsteadOfInlineT35Sei() {
        val ndr10Plus = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "NDR10+",
                colorSpace = "Rec.2020-NDR10+",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            )
        ).toMetadata()
        val nivukx = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "NIVUKX Vision",
                colorSpace = "Rec.2020-NIVUKX",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            )
        ).toMetadata()

        assertEquals("false", ndr10Plus.getValue("neoHdrDynamicSeiRequired"))
        assertEquals("NEO_PRIVATE_SIDECAR", ndr10Plus.getValue("neoHdrMetadataCarrier"))
        assertEquals("0", ndr10Plus.getValue("neoHdrSeiPayloadType"))
        assertEquals("0", ndr10Plus.getValue("neoHdrSeiCountryCode"))
        assertEquals("false", ndr10Plus.getValue("officialHdr10PlusMetadataWritten"))
        assertEquals("false", ndr10Plus.getValue("vendorHdrCertification"))
        assertEquals("false", ndr10Plus.getValue("vendorCertifiedHdr10Plus"))
        assertEquals("false", ndr10Plus.getValue("vendorCertifiedDolbyVision"))
        assertEquals("false", ndr10Plus.getValue("dolbyRpuWritten"))
        assertEquals("false", ndr10Plus.getValue("externalHdrStandardSubstitution"))
        assertEquals("true", ndr10Plus.getValue("proprietaryProfilePreserved"))
        assertEquals("5000", ndr10Plus.getValue("neoHdrMasteringPeakNits"))
        assertEquals("false", ndr10Plus.getValue("neoHdrBitstreamAuditRequired"))
        assertEquals("true", ndr10Plus.getValue("neoHdrNoVendorDependency"))
        assertFalse(ndr10Plus.values.any { it.contains("Dolby", ignoreCase = true) })

        assertEquals("false", nivukx.getValue("neoHdrDynamicSeiRequired"))
        assertEquals("NEO_PRIVATE_SIDECAR", nivukx.getValue("neoHdrMetadataCarrier"))
        assertEquals("0", nivukx.getValue("neoHdrSeiPayloadType"))
        assertEquals("10000", nivukx.getValue("neoHdrMasteringPeakNits"))
        assertEquals("0.64", nivukx.getValue("neoHdrColorVolumeCompression"))
        assertEquals("false", nivukx.getValue("vendorHdrCertification"))
        assertEquals("true", nivukx.getValue("proprietaryProfilePreserved"))
        assertFalse(nivukx.values.any { it.contains("Dolby", ignoreCase = true) })
    }

    @Test
    fun nLogQualityMetadataDeclaresV3AllIntraP010MasteringOnlyForOwnLogFamily() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.LOG,
                width = 3840,
                height = 2160,
                fps = 60,
                logProfile = "N-Log Cinema",
                colorSpace = "Rec.2020-N-Log",
                colorScience = "N-Log Cinema",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            )
        ).toMetadata()

        assertEquals("true", metadata.getValue("nLogMasteringActive"))
        assertEquals("N-LOG-v5.0-APEX-LOG", metadata.getValue("nLogCurveVersion"))
        assertEquals("DEVICE_MEASUREMENT_REQUIRED", metadata.getValue("nLogDynamicRangeEvidence"))
        assertEquals("UNVERIFIED", metadata.getValue("nLogDynamicRangeStops"))
        assertEquals("10", metadata.getValue("nLogBitDepthRequired"))
        assertEquals("true", metadata.getValue("nLogP010Required"))
        assertEquals("true", metadata.getValue("nLogAllIntraRequired"))
        assertEquals("1000000000", metadata.getValue("nLogBitrateFloorBps"))
        assertEquals("2000000000", metadata.getValue("nLogBitrateCeilingBps"))
        assertEquals("BT.2020", metadata.getValue("nLogPrimaries"))
        assertEquals("ACES_AP0_AP1_REC2020", metadata.getValue("nLogAcesWorkflow"))
        assertEquals("EXPOSURE_ADAPTIVE_JOINT_BILATERAL", metadata.getValue("nLogShadowNoiseSuppression"))
        assertEquals("OKLAB_HUE_PRESERVING_HIGHLIGHT_ROLLOFF", metadata.getValue("nLogHighlightGamutProtection"))
        assertEquals("false", metadata.getValue("nLogVendorDependency"))
        assertFalse(metadata.getValue("selectedProfile").contains("ARRI", ignoreCase = true))
        assertFalse(metadata.getValue("selectedProfile").contains("Sony", ignoreCase = true))
        assertFalse(metadata.getValue("selectedProfile").contains("RED", ignoreCase = true))
    }

    @Test
    fun proSdr10MetadataDeclaresWideGamutAllIntraAndHdrLookBypass() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = false
            HighEndQualityPolicy.strictHighEndOnly = false
            val certificate = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.PRO,
                    width = 3840,
                    height = 2160,
                    fps = 60,
                    colorSpace = "Rec.2020",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val metadata = certificate.toMetadata()

            assertEquals(EliteQualityAuditStatus.PASS, certificate.auditStatus)
            assertEquals(EliteVideoQualityTier.ELITE_PRO_SDR_10BIT, certificate.qualityTier)
            assertEquals(400_000_000, certificate.minimumBitrateBps)
            assertEquals("true", metadata.getValue("sdr10WideGamut"))
            assertEquals("true", metadata.getValue("hdrLookBypass"))
            assertEquals("true", metadata.getValue("allIntraRequired"))
            assertEquals("BT.2020", metadata.getValue("sdr10Primaries"))
            assertEquals("SDR_VIDEO", metadata.getValue("sdr10Transfer"))
            assertEquals("400000000", metadata.getValue("sdr10BitrateFloorBps"))
            assertEquals("800000000", metadata.getValue("sdr10BitrateCeilingBps"))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun normalSdrMetadataDeclaresRec709AllIntraMain10WithoutHdrPromotion() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            val expected = BitPerfectRecordingPolicy().resolveNormalSdrAllIntraConfig(
                3840,
                2160,
                fps = 60
            )
            val codecCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
            val certificate = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.NORMAL,
                    width = 3840,
                    height = 2160,
                    fps = 60,
                    colorSpace = "Rec.709",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true
                )
            )
            val metadata = certificate.toMetadata()

            assertEquals(EliteQualityAuditStatus.PASS, certificate.auditStatus)
            assertEquals(EliteVideoQualityTier.ELITE_NORMAL_SDR, certificate.qualityTier)
            assertEquals(codecCeiling, certificate.minimumBitrateBps)
            assertTrue(certificate.requiresTenBitTransport)
            assertTrue(certificate.truthfulNative10Bit)
            assertEquals("true", metadata.getValue("normalSdrMasteringActive"))
            assertEquals("true", metadata.getValue("normalSdrHdrLookBypass"))
            assertEquals("true", metadata.getValue("normalSdrAllIntraRequired"))
            assertTrue(expected.minimumBitrateBps > codecCeiling)
            assertEquals(codecCeiling.toString(), metadata.getValue("normalSdrBitrateFloorBps"))
            assertEquals(codecCeiling.toString(), metadata.getValue("normalSdrBitrateCeilingBps"))
            assertEquals("BT.709", metadata.getValue("normalSdrPrimaries"))
            assertEquals("SDR_VIDEO", metadata.getValue("normalSdrTransfer"))
            assertEquals("true", metadata.getValue("normalSdrTenBitMain10Required"))
            assertEquals("false", metadata.getValue("sdr10MasteringActive"))
            assertEquals("HEVC_MAIN10_SDR", metadata.getValue("activeTransport"))
            assertEquals("BT.709/OETF", metadata.getValue("transfer"))
            assertEquals("10", metadata.getValue("encodedBitDepth"))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun strictHdrBitrateUsesResolutionAndFpsFloorInsteadOfBlindCodecMaximum() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            val certificate = EliteVideoQualityContract.resolve(
                baseRequest(
                    mode = EliteVideoPipelineMode.HDR,
                    width = 1920,
                    height = 1080,
                    fps = 25,
                    hdrMode = "NDR10+",
                    sourceBitDepth = 10,
                    storageBitDepth = 10,
                    tenBitTransport = true,
                    colorSpace = "Neo Wide Gamut"
                )
            )
            val codecCeiling =
                RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000

            assertTrue(certificate.minimumBitrateBps > 0)
            assertTrue(certificate.minimumBitrateBps < codecCeiling)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun uncompressedBitrateFloorsSaturateInsteadOfWrappingOnExtremeMetadata() {
        val rawFloor = EliteVideoQualityContract.resolveMinimumBitrateBps(
            baseRequest(
                mode = EliteVideoPipelineMode.RAW,
                width = 1_000_000,
                height = 1_000_000,
                fps = 1_000_000,
                rawBitDepth = 16,
                sourceBitDepth = 16,
                storageBitDepth = 16,
                colorSpace = "RAW",
                colorScience = "Sensor RAW"
            )
        )
        val npv10Floor = EliteVideoQualityContract.resolveMinimumBitrateBps(
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                width = 1_000_000,
                height = 1_000_000,
                fps = 1_000_000,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true,
                payloadFormat = Npv10PayloadFormat.P010_LE
            )
        )

        assertEquals(Int.MAX_VALUE, rawFloor)
        assertEquals(Int.MAX_VALUE, npv10Floor)
    }

    @Test
    fun enhancementMetadataKeepsPerceptualUpscaleTruthfulForNormalVideo() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NORMAL,
                width = 1280,
                height = 720,
                fps = 30,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            )
        ).toMetadata()

        assertEquals("STRICT_10X_10_10_NORMAL_PRO_SDR", metadata.getValue("enhancementLevel"))
        assertEquals("ENCODED_RECORDING", metadata.getValue("enhancementAppliedStage"))
        assertEquals("ZERO_VISIBLE_NOISE_10_10_TARGET", metadata.getValue("noiseReductionGoal"))
        assertEquals("FULL_STRENGTH_10X_EDGE_AWARE_DETAIL", metadata.getValue("edgeEnhancement"))
        assertEquals("1K_TO_4K_PERCEPTUAL", metadata.getValue("perceptualUpscaleTarget"))
        assertEquals("false", metadata.getValue("nativeOutputResolutionFaked"))
        assertEquals("false", metadata.getValue("truthfulMasterPreserved"))
        assertTrue(metadata.getValue("trueResolutionClaim").contains("TRUE_NATIVE_1280x720"))
        assertTrue(metadata.getValue("trueResolutionClaim").contains("NO_FAKE_SUPER_RESOLUTION"))
        assertFalse(metadata.getValue("trueResolutionClaim").contains("TRUE_4K"))
        assertFalse(metadata.getValue("trueResolutionClaim").contains("TRUE_16K"))
    }

    @Test
    fun stillMoreEliteMetadataAddsTruthfulReconstructionPolicyAcrossAllVideoModes() {
        val requests = listOf(
            baseRequest(mode = EliteVideoPipelineMode.NORMAL, width = 1280, height = 720),
            baseRequest(mode = EliteVideoPipelineMode.PRO, sourceBitDepth = 10, storageBitDepth = 10, tenBitTransport = true),
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "NIVUKX Vision",
                colorSpace = "Rec.2020-NIVUKX",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.LOG,
                logProfile = "N-Log Cinema",
                colorSpace = "Rec.2020-N-Log",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.RAW,
                rawBitDepth = 12,
                sourceBitDepth = 12,
                storageBitDepth = 12
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true
            )
        )

        requests.forEach { request ->
            val metadata = EliteVideoQualityContract.resolve(request).toMetadata()

            assertEquals("NORMAL_PRO_SDR_10X_10_10_PIPELINE", metadata.getValue("elitePipelineGeneration"))
            assertEquals("FULL_STRENGTH_MULTI_FRAME_LUMA_CHROMA_10X", metadata.getValue("noiseReductionPipeline"))
            assertEquals("PREVIEW_RECORDING_NPV10_PARITY_TEMPORAL_10X", metadata.getValue("temporalDenoiseModel"))
            assertEquals("TILT_LOCKED_COLOR_STABILITY_10_10", metadata.getValue("chromaStabilityTarget"))
            assertEquals("SUBPIXEL_10X_TEXTURE_RECONSTRUCTION", metadata.getValue("detailReconstruction"))
            assertEquals("TEN_X_HALOGUARD_PREVIEW_RECORDING_EXPORT", metadata.getValue("edgeHaloPolicy"))
            assertEquals("PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED", metadata.getValue("resolutionTruth"))
            assertEquals("TRUTHFUL_PERCEPTUAL_SCALING_NATIVE_PIXELS_UNCHANGED", metadata.getValue("perceptualResolutionPolicy"))
            assertEquals("RICH_TEXTURE_ZERO_NOISE_10_10", metadata.getValue("textureModel"))
            assertEquals("EDGE_CONFIDENCE_MAP_ACTIVE", metadata.getValue("edgeConfidenceMap"))
            assertEquals("TEXTURE_PRESERVATION_GUARD_ACTIVE", metadata.getValue("texturePreservationGuard"))
            assertEquals("ADAPTIVE_DETAIL_RECONSTRUCTION_ACTIVE", metadata.getValue("adaptiveDetailReconstruction"))
            assertEquals("SENSOR_NOISE_PROFILE_ACTIVE", metadata.getValue("sensorNoiseProfile"))
            assertEquals("SHADOW_CHROMA_LOCK_ACTIVE", metadata.getValue("shadowChromaLock"))
            assertEquals("SUBPIXEL_EDGE_DETECTION_ACTIVE", metadata.getValue("subpixelEdgeDetection"))
            assertEquals("ALIAS_SAFE_DETAIL_RECONSTRUCTION_ACTIVE", metadata.getValue("aliasSafeDetailReconstruction"))
            assertEquals("SCENE_COMPLEXITY_BITRATE_GUARD_ACTIVE", metadata.getValue("sceneComplexityBitrateGuard"))
            assertTrue(metadata.getValue("encoderArtifactGuard").contains("ARTIFACT_SUPPRESSION"))
            assertEquals(
                "PREVIEW_MATCHED_ADAPTIVE_QP_10X_ARTIFACT_SUPPRESSION",
                metadata.getValue("encoderArtifactSuppression")
            )
            assertEquals("false", metadata.getValue("nativeOutputResolutionFaked"))
        }
    }

    @Test
    fun qualityMetadataRequiresManualColorSelectionsForRecordingPipelines() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NORMAL,
                colorSpace = "Neo Wide Gamut",
                logProfile = "N-Log Cinema"
            )
        ).toMetadata()

        assertEquals("USER_MANUAL", metadata.getValue("colorSelectionSource"))
        assertEquals("true", metadata.getValue("manualColorSelectionRequired"))
        assertEquals("Neo Wide Gamut", metadata.getValue("selectedColorSpace"))
        assertEquals("N-Log Cinema", metadata.getValue("selectedColorScience"))
    }

    @Test
    fun manualColorScienceAndColorSpaceRemainUserSelectableAcrossAllVideoPipelines() {
        val requests = listOf(
            baseRequest(
                mode = EliteVideoPipelineMode.NORMAL,
                colorSpace = "Neo Wide Gamut",
                colorScience = "Natural",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.PRO,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                colorSpace = "Rec.2020",
                colorScience = "Rec.709 Standard",
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "NDR10+",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                colorSpace = "Rec.2020-NDR10+",
                colorScience = "NDR10+",
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.LOG,
                logProfile = "N-Log Cinema",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                colorSpace = "Rec.2020-N-Log",
                colorScience = "N-Log Cinema",
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.RAW,
                rawBitDepth = 12,
                sourceBitDepth = 12,
                storageBitDepth = 12,
                colorSpace = "RAW",
                colorScience = "Sensor RAW"
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                colorSpace = "Rec.2020-N-Log",
                colorScience = "N-Log Cinema",
                tenBitTransport = true
            )
        )

        requests.forEach { request ->
            val metadata = EliteVideoQualityContract.resolve(request).toMetadata()

            assertEquals("USER_MANUAL", metadata.getValue("colorSelectionSource"))
            assertEquals("ALL_VIDEO_PIPELINES_USER_SELECTABLE", metadata.getValue("manualColorSelectionScope"))
            assertEquals("true", metadata.getValue("manualColorSelectionRequired"))
            assertEquals("true", metadata.getValue("manualColorSelectionReady"))
            assertEquals(request.colorSpace, metadata.getValue("selectedColorSpace"))
            assertTrue(metadata.getValue("selectedColorScience").isNotBlank())
        }
    }

    @Test
    fun enhancementMetadataPreservesNpv10PrivateMasterTruth() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                width = 3840,
                height = 2160,
                fps = 60,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true
            )
        ).toMetadata()

        assertEquals("STRICT_10X_10_10_NORMAL_PRO_SDR", metadata.getValue("enhancementLevel"))
        assertEquals("NPV10_EXPORT_ONLY", metadata.getValue("enhancementAppliedStage"))
        assertEquals("4K_TO_16K_PERCEPTUAL", metadata.getValue("perceptualUpscaleTarget"))
        assertEquals("TRUTHFUL_PERCEPTUAL_SCALING_NATIVE_PIXELS_UNCHANGED", metadata.getValue("perceptualResolutionPolicy"))
        assertTrue(metadata.getValue("bitrateFloorMultiplier").toDouble() >= 1.58)
        assertEquals("true", metadata.getValue("truthfulMasterPreserved"))
        assertEquals("false", metadata.getValue("masterEnhancementDestructive"))
        assertEquals("PRIVATE_MASTER_UNMODIFIED", metadata.getValue("npv10MasterPolicy"))
        assertEquals("false", metadata.getValue("nativeOutputResolutionFaked"))
    }

    @Test
    fun normalProSdrMetadataCarriesStrictTenOutOfTenScorecard() {
        val requests = listOf(
            baseRequest(mode = EliteVideoPipelineMode.NORMAL),
            baseRequest(
                mode = EliteVideoPipelineMode.PRO,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true
            )
        )

        requests.forEach { request ->
            val metadata = EliteVideoQualityContract.resolve(request).toMetadata()

            assertEquals(
                NormalSdrVideoQualityScorecardPolicy.PROFILE_NAME,
                metadata.getValue("normalProSdrQualityProfile")
            )
            assertEquals("10.0", metadata.getValue("normalProSdrRequiredScore"))
            assertEquals("10.0", metadata.getValue("normalProSdrOverallScore"))
            assertEquals("true", metadata.getValue("normalProSdrScorecardPassed"))
            assertEquals("10.0", metadata.getValue("normalSdrRealtimePreviewQuality"))
            assertEquals("10.0", metadata.getValue("proSdrRealtimePreviewQuality"))
            assertEquals("10.0", metadata.getValue("recordingPreviewParity"))
            assertEquals("10.0", metadata.getValue("npv10SdrExportDetailSharpness"))
            assertEquals("10.0", metadata.getValue("noiseSuppression"))
            assertEquals("10.0", metadata.getValue("tiltColorStability"))
            assertEquals("10.0", metadata.getValue("sharpnessDetailIntensity"))
            assertEquals("10.0", metadata.getValue("performanceFpsSafety"))
        }
    }

    @Test
    fun eliteMetadataRequiresV15EvidenceBeforeStrictDeviceVerifiedQuality() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.HDR,
                hdrMode = "NDR10+",
                colorSpace = "Rec.2020-NDR10+",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            )
        ).toMetadata()

        assertEquals("V15_STRICT_QUALITY_EVIDENCE_GATE", metadata.getValue("qualityEvidenceGeneration"))
        assertEquals("V10_EVIDENCE_CALIBRATED_GATE", metadata.getValue("v10QualityEvidenceGeneration"))
        assertEquals("true", metadata.getValue("strict10QualityRequiresV10Evidence"))
        assertEquals("true", metadata.getValue("strict10QualityRequiresV15Evidence"))
        assertEquals("true", metadata.getValue("strict10QualityRequiresV6Evidence"))
        assertEquals("true", metadata.getValue("tierOmegaEvidenceGateApplied"))
        assertEquals("false", metadata.getValue("tierOmegaQualityProven"))
        assertEquals("54.000000", metadata.getValue("tierOmegaMinLumaSnrBaseDb"))
        assertEquals("0.380000", metadata.getValue("tierOmegaMinMtf50CyPerPx"))
        assertEquals("BEYOND_IMAX_ELITE_10_10", metadata.getValue("beyondImaxQualityProfile"))
        assertEquals("false", metadata.getValue("strict10QualityProven"))
        assertEquals("false", metadata.getValue("strict10DeviceVerified"))
        assertEquals("NOT_PROVEN_WITHOUT_RECORDED_FILE_PROBE_AND_DEVICE_LOGS", metadata.getValue("qualityEvidenceStatus"))
        assertEquals("MOTION_AWARE_TEMPORAL_LUMA_CHROMA_V6", metadata.getValue("noiseModelV6"))
        assertEquals("EDGE_CONFIDENCE_ANTI_HALO_DETAIL_V6", metadata.getValue("detailModelV6"))
    }

    @Test
    fun npv10MetadataUsesActualColorProfileInsteadOfInventingLogProfile() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true,
                logProfile = "Rec.709",
                colorSpace = "Rec.709"
            )
        ).toMetadata()

        assertEquals("Rec.709", metadata.getValue("selectedProfile"))
        assertEquals("NPV10_PRIVATE_MASTER", metadata.getValue("activeTransport"))
        assertEquals("OFF", metadata.getValue("normalizedLogVersion"))
        assertEquals("BT.709/OETF", metadata.getValue("transfer"))
        assertEquals("BT.709", metadata.getValue("primaries"))
        assertEquals("BT.709", metadata.getValue("matrix"))
    }

    @Test
    fun npv10NdrMetadataCarriesBt2100PqPrivateMasterContract() {
        val metadata = EliteVideoQualityContract.resolve(
            baseRequest(
                mode = EliteVideoPipelineMode.NPV10,
                hdrMode = "NDR10+",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true,
                colorSpace = "BT.2100 PQ",
                colorScience = "NDR10+"
            )
        ).toMetadata()

        assertEquals("NDR10+", metadata.getValue("selectedProfile"))
        assertEquals("NDR10+", metadata.getValue("normalizedHdrVersion"))
        assertEquals("NPV10_BT.2100_PQ_PRIVATE_MASTER", metadata.getValue("activeTransport"))
        assertEquals("ST2084/PQ", metadata.getValue("transfer"))
        assertEquals("BT.2020", metadata.getValue("primaries"))
        assertEquals("BT.2020-NCL", metadata.getValue("matrix"))
    }

    @Test
    fun neoHdrDynamicMetadataSidecarDocumentsFallbackWhenInlineMetadataIsUnavailable() {
        val sidecar = NeoHdrDynamicMetadataSidecar.build(
            selectedProfile = "NDR10+",
            averageLuma = 0.34,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.82,
            inlineDynamicMetadataWritten = false
        )

        assertEquals("NDR10+", sidecar.entries.getValue("selectedProfile"))
        assertEquals("BT.2100_PQ_MAIN10", sidecar.entries.getValue("activeTransport"))
        assertEquals("ST2084/PQ", sidecar.entries.getValue("transfer"))
        assertEquals("BT.2020", sidecar.entries.getValue("primaries"))
        assertEquals("BT.2020-NCL", sidecar.entries.getValue("matrix"))
        assertEquals("10", sidecar.entries.getValue("encodedBitDepth"))
        assertEquals("12", sidecar.entries.getValue("internalMathBitDepth"))
        assertEquals("false", sidecar.entries.getValue("dynamicMetadataWritten"))
        assertEquals("true", sidecar.entries.getValue("dynamicMetadataSidecarWritten"))
        assertTrue(sidecar.text.contains("selectedProfile=NDR10+"))
        assertTrue(sidecar.text.contains("dynamicMetadataSidecarWritten=true"))
    }

    private fun baseRequest(
        mode: EliteVideoPipelineMode,
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 60,
        codec: GpuVideoCodec = GpuVideoCodec.HEVC,
        hdrMode: String = "OFF",
        logProfile: String = "N-Log Cinema",
        rawBitDepth: Int = 0,
        sourceBitDepth: Int = 8,
        storageBitDepth: Int = if (sourceBitDepth >= 10) sourceBitDepth else 8,
        sourceFormat: String = "YUV_420_888",
        payloadFormat: Npv10PayloadFormat? = null,
        tenBitTransport: Boolean = mode == EliteVideoPipelineMode.NORMAL || mode == EliteVideoPipelineMode.PRO,
        colorSpace: String = "Rec.709",
        colorScience: String = logProfile
    ): EliteVideoQualityRequest =
        EliteVideoQualityRequest(
            mode = mode,
            width = width,
            height = height,
            fps = fps,
            codec = codec,
            hdrMode = hdrMode,
            logProfile = logProfile,
            rawBitDepth = rawBitDepth,
            sourceBitDepth = if (mode == EliteVideoPipelineMode.NORMAL || mode == EliteVideoPipelineMode.PRO) {
                sourceBitDepth.coerceAtLeast(10)
            } else {
                sourceBitDepth
            },
            storageBitDepth = if (mode == EliteVideoPipelineMode.NORMAL || mode == EliteVideoPipelineMode.PRO) {
                storageBitDepth.coerceAtLeast(10)
            } else {
                storageBitDepth
            },
            sourceFormat = sourceFormat,
            payloadFormat = payloadFormat,
            tenBitTransport = tenBitTransport,
            colorSpace = colorSpace,
            colorScience = colorScience
        )
}
