package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NeoProfileTruthMetadataTest {

    @Test
    fun ndr10PlusReportsNeoProfileSeparateFromAndroidTransport() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = hdrRequest("NDR10+"),
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "N-Log Base"
        )

        assertEquals("NDR10+", metadata.selectedProfile)
        assertEquals("BT.2100_PQ_MAIN10", metadata.activeTransport)
        assertEquals("ST2084/PQ", metadata.transfer)
        assertEquals("BT.2020", metadata.primaries)
        assertEquals("BT.2020-NCL", metadata.matrix)
        assertEquals(10, metadata.encodedBitDepth)
        assertEquals(12, metadata.internalMathBitDepth)
        assertFalse(metadata.selectedProfile.contains("HDR10", ignoreCase = true))
        assertFalse(metadata.activeTransport.contains("HDR10+", ignoreCase = true))
        assertFalse(metadata.activeTransport.contains("Dolby", ignoreCase = true))
    }

    @Test
    fun nivukxVisionReportsNeoProfileSeparateFromDolbyVision() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = hdrRequest("NIVUKX Vision"),
            normalizedHdrVersion = "NIVUKXVISION",
            normalizedLogVersion = "N-Log Base"
        )

        assertEquals("NIVUKX Vision", metadata.selectedProfile)
        assertEquals("BT.2100_PQ_MAIN10", metadata.activeTransport)
        assertEquals("ST2084/PQ", metadata.transfer)
        assertEquals("BT.2020", metadata.primaries)
        assertEquals("BT.2020-NCL", metadata.matrix)
        assertEquals(10, metadata.encodedBitDepth)
        assertEquals(12, metadata.internalMathBitDepth)
        assertFalse(metadata.selectedProfile.contains("Dolby", ignoreCase = true))
        assertFalse(metadata.activeTransport.contains("Dolby", ignoreCase = true))
        assertFalse(metadata.activeTransport.contains("HDR10+", ignoreCase = true))
    }

    @Test
    fun unsupportedMain10TransportIsClearlyLabeledAsFallback() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = hdrRequest("NDR10+").copy(
                sourceBitDepth = 8,
                storageBitDepth = 8,
                tenBitTransport = false
            ),
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "N-Log Base"
        )
        val fields = metadata.asMap()

        assertEquals("NDR10+", metadata.selectedProfile)
        assertEquals("FALLBACK_SDR_UNSUPPORTED_MAIN10", metadata.activeTransport)
        assertEquals("MAIN10_TRANSPORT_UNAVAILABLE", fields.getValue("transportFallbackReason"))
        assertEquals("8", fields.getValue("encodedBitDepth"))
        assertEquals("false", fields.getValue("officialHdr10PlusMetadataWritten"))
        assertEquals("false", fields.getValue("vendorHdrCertification"))
        assertEquals("false", fields.getValue("vendorCertifiedHdr10Plus"))
        assertEquals("false", fields.getValue("vendorCertifiedDolbyVision"))
        assertEquals("false", fields.getValue("dolbyRpuWritten"))
        assertEquals("false", fields.getValue("externalHdrStandardSubstitution"))
        assertEquals("true", fields.getValue("proprietaryProfilePreserved"))
        assertEquals("false", fields.getValue("androidHdrTransportOnly"))
    }

    @Test
    fun proSdrReportsMain10OnlyWithRealTenBitEvidenceAndWideSdrMatrix() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = sdrRequest(EliteVideoPipelineMode.PRO).copy(
                colorSpace = "Rec.2020",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "Natural"
        )

        assertEquals("Pro SDR", metadata.selectedProfile)
        assertEquals("HEVC_MAIN10_SDR", metadata.activeTransport)
        assertEquals("BT.709/OETF", metadata.transfer)
        assertEquals("BT.2020", metadata.primaries)
        assertEquals("BT.2020-NCL", metadata.matrix)
        assertEquals(10, metadata.encodedBitDepth)
        assertEquals(10, metadata.internalMathBitDepth)
    }

    @Test
    fun normalSdrRec2020ReportsWideGamutSdrTransportTruthfully() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = sdrRequest(EliteVideoPipelineMode.NORMAL).copy(
                colorSpace = "Rec.2020",
                colorScience = "Rec.2020",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "Natural"
        )

        assertEquals("Normal SDR", metadata.selectedProfile)
        assertEquals("HEVC_MAIN10_SDR", metadata.activeTransport)
        assertEquals("BT.709/OETF", metadata.transfer)
        assertEquals("BT.2020", metadata.primaries)
        assertEquals("BT.2020-NCL", metadata.matrix)
        assertEquals("BT.2020", metadata.selectedContainerPrimaries)
        assertEquals("SDR_VIDEO", metadata.selectedContainerTransfer)
        assertEquals("BT.2020-NCL", metadata.selectedContainerMatrix)
        assertEquals(10, metadata.encodedBitDepth)
    }

    @Test
    fun proSdrTruthMetadataCarriesManualAcesAndNeoWideSelectionIdentity() {
        val ap0 = NeoProfileTruthMetadataPolicy.resolve(
            request = sdrRequest(EliteVideoPipelineMode.PRO).copy(
                colorSpace = "ACES AP0",
                colorScience = "Neo ACES High End",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "Natural"
        ).asMap()
        val neoWide = NeoProfileTruthMetadataPolicy.resolve(
            request = sdrRequest(EliteVideoPipelineMode.PRO).copy(
                colorSpace = "Display P3",
                colorScience = "Natural",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "Natural"
        ).asMap()

        assertEquals("Neo ACES AP0", ap0.getValue("selectedColorSpace"))
        assertEquals("Neo ACES High End", ap0.getValue("selectedColorScience"))
        assertEquals("ACES AP0", ap0.getValue("selectedColorPrimaries"))
        assertEquals("ACES2065-1", ap0.getValue("selectedColorWorkingGamut"))
        assertEquals("BT.2020", ap0.getValue("selectedContainerPrimaries"))
        assertEquals("BT.2020-NCL", ap0.getValue("selectedContainerMatrix"))
        assertEquals("false", ap0.getValue("vendorManagedColorScience"))
        assertEquals("Neo Wide Gamut", neoWide.getValue("selectedColorSpace"))
        assertEquals("Neo WG2", neoWide.getValue("selectedColorPrimaries"))
        assertEquals("D55", neoWide.getValue("selectedColorWhitePoint"))
    }

    @Test
    fun normalSdrDoesNotOverclaimMain10WhenSourceStorageEvidenceIsEightBit() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = sdrRequest(EliteVideoPipelineMode.NORMAL).copy(
                sourceBitDepth = 8,
                storageBitDepth = 8,
                tenBitTransport = true
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "Natural"
        )

        assertEquals("Normal SDR", metadata.selectedProfile)
        assertEquals("HEVC_SDR", metadata.activeTransport)
        assertEquals("BT.709", metadata.primaries)
        assertEquals("BT.709", metadata.matrix)
        assertEquals(8, metadata.encodedBitDepth)
        assertEquals(8, metadata.internalMathBitDepth)
    }

    @Test
    fun logFallbackDoesNotAdvertiseMain10LogTransportWhenEvidenceIsEightBit() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = logRequest().copy(
                sourceBitDepth = 8,
                storageBitDepth = 8,
                tenBitTransport = false
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "N-Log Cinema"
        )
        val fields = metadata.asMap()

        assertEquals("N-Log Cinema", metadata.selectedProfile)
        assertEquals("FALLBACK_SDR_UNSUPPORTED_MAIN10_LOG", metadata.activeTransport)
        assertEquals("BT.709/OETF", metadata.transfer)
        assertEquals("BT.709", metadata.primaries)
        assertEquals("BT.709", metadata.matrix)
        assertEquals(8, metadata.encodedBitDepth)
        assertEquals(8, metadata.internalMathBitDepth)
        assertEquals("LOG_MAIN10_TRANSPORT_UNAVAILABLE", fields.getValue("transportFallbackReason"))
    }

    @Test
    fun neoHdrTruthMetadataDocumentsOriginalProfileGuardsAndNoVendorClaims() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = hdrRequest("NIVUKX Vision"),
            normalizedHdrVersion = "NIVUKXVISION",
            normalizedLogVersion = "N-Log Base",
            neoDynamicMetadataSidecarWritten = true
        ).asMap()

        assertEquals("NIVUKX Vision", metadata.getValue("selectedProfile"))
        assertEquals("BT.2100_PQ_MAIN10", metadata.getValue("activeTransport"))
        assertEquals("NONE", metadata.getValue("transportFallbackReason"))
        assertEquals("false", metadata.getValue("officialHdr10PlusMetadataWritten"))
        assertEquals("false", metadata.getValue("vendorHdrCertification"))
        assertEquals("false", metadata.getValue("vendorCertifiedHdr10Plus"))
        assertEquals("false", metadata.getValue("vendorCertifiedDolbyVision"))
        assertEquals("false", metadata.getValue("dolbyRpuWritten"))
        assertEquals("false", metadata.getValue("externalHdrStandardSubstitution"))
        assertEquals("true", metadata.getValue("proprietaryProfilePreserved"))
        assertEquals("true", metadata.getValue("androidHdrTransportOnly"))
        assertEquals("true", metadata.getValue("neoDynamicMetadataSidecarWritten"))
        assertEquals("BT2390_SOFT_SHOULDER_SCENE_ADAPTIVE", metadata.getValue("highlightRolloff"))
        assertEquals("BT2100_SKIN_RED_RATIO_PROTECTION", metadata.getValue("skinToneProtection"))
        assertEquals("ACTIVE_CHANNEL_HEADROOM_LIMIT", metadata.getValue("redClippingGuard"))
        assertEquals("ACTIVE_SHADOW_CHROMA_FLOOR", metadata.getValue("cyanShadowGuard"))
        assertEquals("NEO_NDR_PROPRIETARY", metadata.getValue("proprietaryProfileAuthority"))
        assertEquals("true", metadata.getValue("bt2100TransportCarrierOnly"))
        assertEquals("BT.2100_PQ_ANDROID_CARRIER_ONLY", metadata.getValue("bt2100CarrierMode"))
        assertEquals("false", metadata.getValue("vendorDynamicRangeProfileUsed"))
        assertEquals("false", metadata.getValue("vendorDynamicMetadataWritten"))
        assertEquals("true", metadata.getValue("dolbyVisionOrHdr10PlusSubstitutionBlocked"))
        assertEquals(
            "SELECTED_PROFILE_TRANSFER_MATRIX_BIT_DEPTH_LOCKED",
            metadata.getValue("previewRecordingNpv10ParityPolicy")
        )
    }

    @Test
    fun nLogTruthMetadataUsesPrivateLogAuthorityWithoutBt2100OrVendorSubstitution() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = logRequest(),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "N-Log Cinema"
        ).asMap()

        assertEquals("N-Log Cinema", metadata.getValue("selectedProfile"))
        assertEquals("HEVC_MAIN10_LOG", metadata.getValue("activeTransport"))
        assertEquals("N-Log", metadata.getValue("transfer"))
        assertEquals("NEO_N_LOG_PROPRIETARY", metadata.getValue("proprietaryProfileAuthority"))
        assertEquals("false", metadata.getValue("bt2100TransportCarrierOnly"))
        assertEquals("NONE", metadata.getValue("bt2100CarrierMode"))
        assertEquals("false", metadata.getValue("vendorDynamicRangeProfileUsed"))
        assertEquals("false", metadata.getValue("vendorDynamicMetadataWritten"))
        assertEquals("true", metadata.getValue("dolbyVisionOrHdr10PlusSubstitutionBlocked"))
        assertEquals("false", metadata.getValue("externalHdrStandardSubstitution"))
        assertEquals("false", metadata.getValue("nLogVendorDependency"))
    }

    @Test
    fun rawAndNpv10TruthMetadataExposeUnmodifiedPrivateMasterPolicy() {
        val raw = NeoProfileTruthMetadataPolicy.resolve(
            request = EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.RAW,
                width = 3840,
                height = 2160,
                fps = 24,
                codec = GpuVideoCodec.HEVC,
                rawBitDepth = 12,
                sourceBitDepth = 12,
                storageBitDepth = 12,
                sourceFormat = "RAW_SENSOR"
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "N-Log Base"
        ).asMap()
        val npv10Log = NeoProfileTruthMetadataPolicy.resolve(
            request = EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.NPV10,
                width = 3840,
                height = 2160,
                fps = 24,
                codec = GpuVideoCodec.HEVC,
                logProfile = "N-Log Cinema",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                sourceFormat = "YCBCR_P010",
                payloadFormat = Npv10PayloadFormat.P010_LE,
                tenBitTransport = true,
                colorSpace = "Rec.2020-N-Log",
                colorScience = "N-Log Cinema"
            ),
            normalizedHdrVersion = "OFF",
            normalizedLogVersion = "N-Log Cinema"
        ).asMap()

        assertEquals("RAW_PRIVATE_MASTER", raw.getValue("activeTransport"))
        assertEquals("NEO_RAW_PRIVATE_SENSOR_MASTER", raw.getValue("proprietaryProfileAuthority"))
        assertEquals("true", raw.getValue("rawPrivateMasterUnmodified"))
        assertEquals("RAW_MONITOR_CINEMA_DEBAYER_PREVIEW_ONLY", raw.getValue("rawMonitorPipeline"))
        assertEquals("false", raw.getValue("rawMonitorDestructiveToMaster"))
        assertEquals("NPV10_PRIVATE_MASTER", npv10Log.getValue("activeTransport"))
        assertEquals("N-Log", npv10Log.getValue("transfer"))
        assertEquals("NEO_N_LOG_PROPRIETARY", npv10Log.getValue("proprietaryProfileAuthority"))
        assertEquals("true", npv10Log.getValue("npv10PrivateMasterUnmodified"))
        assertEquals("false", npv10Log.getValue("rawMonitorDestructiveToMaster"))
    }

    @Test
    fun apexHdrTruthMetadataStaysInNeoOwnedSidecarPath() {
        val metadata = NeoProfileTruthMetadataPolicy.resolve(
            request = hdrRequest("APEX Sharp").copy(colorScience = "NIVUKX APEX SHARP"),
            normalizedHdrVersion = "APEX_SHARP",
            normalizedLogVersion = "N-Log Base",
            neoDynamicMetadataSidecarWritten = true
        ).asMap()

        assertEquals("NIVUKX APEX SHARP", metadata.getValue("selectedProfile"))
        assertEquals("BT.2100_PQ_MAIN10", metadata.getValue("activeTransport"))
        assertEquals("ST2084/PQ", metadata.getValue("transfer"))
        assertEquals("BT.2020", metadata.getValue("primaries"))
        assertEquals("BT.2020-NCL", metadata.getValue("matrix"))
        assertEquals("true", metadata.getValue("neoDynamicMetadataSidecarWritten"))
        assertEquals("false", metadata.getValue("vendorHdrCertification"))
        assertEquals("false", metadata.getValue("vendorCertifiedHdr10Plus"))
        assertEquals("false", metadata.getValue("vendorCertifiedDolbyVision"))
        assertEquals("true", metadata.getValue("proprietaryProfilePreserved"))
        assertEquals("true", metadata.getValue("androidHdrTransportOnly"))
    }

    @Test
    fun neoDynamicMetadataSidecarWritesOriginalNeoEvidence() {
        val sidecar = NeoHdrDynamicMetadataSidecar.build(
            selectedProfile = "NIVUKX Vision",
            averageLuma = 0.34,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.82,
            inlineDynamicMetadataWritten = false
        )

        assertEquals("NIVUKX Vision", sidecar.entries.getValue("selectedProfile"))
        assertEquals("BT.2100_PQ_MAIN10", sidecar.entries.getValue("activeTransport"))
        assertEquals("true", sidecar.entries.getValue("neoDynamicMetadataSidecarWritten"))
        assertEquals("false", sidecar.entries.getValue("officialHdr10PlusMetadataWritten"))
        assertEquals("false", sidecar.entries.getValue("vendorHdrCertification"))
        assertEquals("false", sidecar.entries.getValue("vendorCertifiedHdr10Plus"))
        assertEquals("false", sidecar.entries.getValue("vendorCertifiedDolbyVision"))
        assertEquals("false", sidecar.entries.getValue("dolbyRpuWritten"))
        assertEquals("false", sidecar.entries.getValue("externalHdrStandardSubstitution"))
        assertEquals("true", sidecar.entries.getValue("proprietaryProfilePreserved"))
        assertEquals("true", sidecar.entries.getValue("androidHdrTransportOnly"))
        assertEquals("BT2390_SOFT_SHOULDER_SCENE_ADAPTIVE", sidecar.entries.getValue("highlightRolloff"))
        assertEquals("ACTIVE_CHANNEL_HEADROOM_LIMIT", sidecar.entries.getValue("redClippingGuard"))
        assertTrue(sidecar.text.contains("selectedProfile=NIVUKX Vision"))
        assertTrue(sidecar.text.contains("neoDynamicMetadataSidecarWritten=true"))
    }

    @Test
    fun neoDynamicMetadataSidecarPreservesApexProfileLabel() {
        val sidecar = NeoHdrDynamicMetadataSidecar.build(
            selectedProfile = "NIVUKX APEX ZERO",
            averageLuma = 0.28,
            highlightRatio = 0.09,
            dynamicRangeScore = 0.71,
            inlineDynamicMetadataWritten = false
        )

        assertEquals("NIVUKX APEX ZERO", sidecar.entries.getValue("selectedProfile"))
        assertEquals("BT.2100_PQ_MAIN10", sidecar.entries.getValue("activeTransport"))
        assertEquals("true", sidecar.entries.getValue("neoDynamicMetadataSidecarWritten"))
        assertTrue(sidecar.text.contains("selectedProfile=NIVUKX APEX ZERO"))
    }

    @Test
    fun neoDynamicMetadataSidecarStoreWritesPrivateEvidenceFile() {
        val root = Files.createTempDirectory("neo-hdr-sidecar").toFile()
        try {
            val sidecar = NeoHdrDynamicMetadataSidecar.build(
                selectedProfile = "NDR10+",
                averageLuma = 0.42,
                highlightRatio = 0.16,
                dynamicRangeScore = 0.76,
                inlineDynamicMetadataWritten = false
            )

            val file = NeoHdrDynamicMetadataSidecarStore.write(root, "clip hdr.mov", sidecar)
            val text = file.readText()

            assertTrue(file.isFile)
            assertTrue(file.parentFile?.name.orEmpty().contains("neo_hdr_dynamic_metadata"))
            assertTrue(file.name.contains("clip_hdr"))
            assertTrue(text.contains("selectedProfile=NDR10+"))
            assertTrue(text.contains("neoDynamicMetadataSidecarWritten=true"))
            assertTrue(text.contains("vendorHdrCertification=false"))
            assertTrue(text.contains("externalHdrStandardSubstitution=false"))
            assertTrue(text.contains("proprietaryProfilePreserved=true"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun hdrRequest(hdrMode: String): EliteVideoQualityRequest =
        EliteVideoQualityRequest(
            mode = EliteVideoPipelineMode.HDR,
            width = 3840,
            height = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = hdrMode,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            tenBitTransport = true,
            colorSpace = "Rec.2020"
        )

    private fun sdrRequest(mode: EliteVideoPipelineMode): EliteVideoQualityRequest =
        EliteVideoQualityRequest(
            mode = mode,
            width = 3840,
            height = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            sourceBitDepth = 10,
            storageBitDepth = 10,
            tenBitTransport = true,
            colorSpace = "Rec.709"
        )

    private fun logRequest(): EliteVideoQualityRequest =
        EliteVideoQualityRequest(
            mode = EliteVideoPipelineMode.LOG,
            width = 3840,
            height = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            sourceBitDepth = 10,
            storageBitDepth = 10,
            tenBitTransport = true,
            colorSpace = "Rec.2020-N-Log"
        )
}
