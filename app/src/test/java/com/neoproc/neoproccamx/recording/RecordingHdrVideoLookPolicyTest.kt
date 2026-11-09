package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHdrVideoLookPolicyTest {

    @Test
    fun supportedHdrVersions_listsCanonicalHdrModes() {
        assertEquals(
            listOf("OFF", "NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA"),
            RecordingHdrVideoLookPolicy.supportedHdrVersions()
        )
    }

    @Test
    fun originalHdrColorProfile_mapsLegacyHdrRequestsToNeoProCamXProfiles() {
        assertEquals("NDR10+ Native", RecordingHdrVideoLookPolicy.originalHdrColorProfile("HLG"))
        assertEquals("NDR10+ Native", RecordingHdrVideoLookPolicy.originalHdrColorProfile("HDR10+"))
        assertEquals("NDR10+ Native", RecordingHdrVideoLookPolicy.originalHdrColorProfile("NDR10+"))
        assertEquals("NIVUKX Vision", RecordingHdrVideoLookPolicy.originalHdrColorProfile("NIVUKX VISION"))
        assertEquals(null, RecordingHdrVideoLookPolicy.originalHdrColorProfile("DOLBYVISION"))
        assertEquals(null, RecordingHdrVideoLookPolicy.originalHdrColorProfile("OFF"))
    }

    @Test
    fun resolveLookProfile_mapsLegacyHdr10IdentityToNdr10Pq() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HDR10",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("NDR10+ BT.2100 PQ", profile.requestedDynamicRangeLabel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,profile.colorEncoding.colorTransfer)
        assertTrue(profile.colorEncoding.requiresTenBit)
    }

    @Test
    fun resolveLookProfile_treatsArriWideGamutAsHdrEligible() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HLG",
            videoColorSpace = "ARRI AWG4",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("NDR10+ BT.2100 PQ", profile.requestedDynamicRangeLabel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,profile.colorEncoding.colorTransfer)
    }

    @Test
    fun resolveLookProfile_treatsSonyAndRedWideGamutsAsHdrEligible() {
        val sony = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HLG",
            videoColorSpace = "Sony S-Gamut3.Cine",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val red = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HLG",
            videoColorSpace = "RED WideGamutRGB",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, sony.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,sony.colorEncoding.colorTransfer)
        assertTrue(sony.colorEncoding.requiresTenBit)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, red.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,red.colorEncoding.colorTransfer)
        assertTrue(red.colorEncoding.requiresTenBit)
    }

    @Test
    fun resolveLookProfile_hdrRequestForcesBt2020EvenWhenColorSpaceIsStaleRec709() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HLG",
            videoColorSpace = "Rec.709",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("NDR10+ BT.2100 PQ", profile.requestedDynamicRangeLabel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,profile.colorEncoding.colorTransfer)
        assertTrue(profile.colorEncoding.requiresTenBit)
    }

    @Test
    fun resolveLookProfile_preservesWideGamutOffModeAsTenBitSdr() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "OFF",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("Wide Gamut SDR (Rec.2020)", profile.requestedDynamicRangeLabel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, profile.colorEncoding.colorTransfer)
        assertTrue(profile.colorEncoding.requiresTenBit)
        assertTrue(profile.colorEncoding.main10Requested)
    }

    @Test
    fun resolveLookProfile_keepsLogModeDistinctFromNormalSdr() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "OFF",
            videoColorSpace = "Rec.2020",
            logEnabled = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("LOG SDR (Rec.2020)", profile.requestedDynamicRangeLabel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, profile.colorEncoding.colorTransfer)
        assertTrue(profile.colorEncoding.requiresTenBit)
        assertTrue(profile.colorEncoding.main10Requested)
    }

    @Test
    fun resolveLookProfile_logRequestOverridesStaleHdrFlagForColorMetadata() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HDR10",
            videoColorSpace = "Rec.2020",
            logEnabled = true,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("LOG SDR (Rec.2020)", profile.requestedDynamicRangeLabel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, profile.colorEncoding.colorTransfer)
        assertTrue(profile.colorEncoding.requiresTenBit)
        assertTrue(profile.colorEncoding.main10Requested)
    }

    @Test
    fun normalizeVideoHdrMode_canonicalizesLegacyAliases() {
        assertEquals("NDR10+", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("hdr"))
        assertEquals("NDR10+", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("HDR10PLUS"))
        assertEquals("NDR10+", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("NDR10PLUS"))
        assertEquals("NIVUKXVISION", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("Nivukx Vision"))
        assertEquals("OFF", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("Dolby Vision"))
        assertEquals("OFF", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("dolby-vision"))
        assertEquals("APEX_SHARP", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("apex"))
        assertEquals("APEX_SHARP", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("Apex Sharp"))
        assertEquals("APEX_ZERO", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("Apex Zero"))
        assertEquals("APEX_CINEMA", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("Apex Cinema"))
        assertEquals("OFF", RecordingHdrVideoLookPolicy.normalizeVideoHdrMode("unknown"))
    }

    @Test
    fun resolveLookProfile_apexModesUsePqMain10Transport() {
        listOf(
            "APEX_SHARP" to "NIVUKX APEX SHARP",
            "APEX_ZERO" to "NIVUKX APEX ZERO",
            "APEX_CINEMA" to "NIVUKX APEX CINEMA"
        ).forEach { (mode, label) ->
            val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
                mime = "video/hevc",
                videoHdrMode = mode,
                videoColorSpace = "Rec.2020",
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )

            assertEquals(label, profile.requestedDynamicRangeLabel)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,profile.colorEncoding.colorTransfer)
            assertTrue(profile.colorEncoding.requiresTenBit)
            assertTrue(profile.colorEncoding.main10Requested)
        }
    }

    @Test
    fun resolveLookProfile_preservesOriginalNeoHdrModesAsPqMain10Transport() {
        val ndr10Plus = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "NDR10+",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val nivukx = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "NIVUKX Vision",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("NDR10+ BT.2100 PQ", ndr10Plus.requestedDynamicRangeLabel)
        assertEquals("NIVUKX Vision BT.2100 PQ", nivukx.requestedDynamicRangeLabel)
        listOf(ndr10Plus, nivukx).forEach { profile ->
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,profile.colorEncoding.colorTransfer)
            assertTrue(profile.colorEncoding.requiresTenBit)
            assertTrue(profile.colorEncoding.main10Requested)
        }
    }

    @Test
    fun resolveLookProfile_forbidsInlineVendorStyleDynamicSeiForNeoHdr() {
        val ndr10Plus = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "NDR10+",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val nivukx = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "NIVUKX Vision",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        val sdr = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "OFF",
            videoColorSpace = "Rec.709",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertFalse(ndr10Plus.dynamicMetadataSeiEnabled)
        assertEquals(null, ndr10Plus.dynamicMetadataPayloadType)
        assertEquals(5_000, ndr10Plus.masteringPeakNits)
        assertEquals(5_000, ndr10Plus.targetPeakNits)
        assertFalse(nivukx.dynamicMetadataSeiEnabled)
        assertEquals(null, nivukx.dynamicMetadataPayloadType)
        assertEquals(10_000, nivukx.masteringPeakNits)
        assertEquals(10_000, nivukx.targetPeakNits)
        assertFalse(nivukx.requestedDynamicRangeLabel.contains("Dolby", ignoreCase = true))
        assertFalse(sdr.dynamicMetadataSeiEnabled)
    }

    @Test
    fun resolveActiveDynamicRangeLabel_preservesNeoHdrIdentityOnTenBitPqTransport() {
        val encoding = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorRange = MediaFormat.COLOR_RANGE_FULL,
            requiresTenBit = true,
            main10Requested = true
        )

        assertEquals(
            "NDR10+ BT.2100 PQ (10-bit)",
            RecordingHdrVideoLookPolicy.resolveActiveDynamicRangeLabel(
                requestedDynamicRangeLabel = "NDR10+ BT.2100 PQ",
                colorEncoding = encoding,
                useTenBit = true,
                simulatedTenBitFallback = false
            )
        )
        assertEquals(
            "NIVUKX Vision BT.2100 PQ (10-bit)",
            RecordingHdrVideoLookPolicy.resolveActiveDynamicRangeLabel(
                requestedDynamicRangeLabel = "NIVUKX Vision BT.2100 PQ",
                colorEncoding = encoding,
                useTenBit = true,
                simulatedTenBitFallback = false
            )
        )
    }

    @Test
    fun resolveLookProfile_rejectsLegacyVendorVisionTextInsteadOfClaimingNivukx() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "Dolby Vision",
            videoColorSpace = "Rec.2020",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals("Wide Gamut SDR (Rec.2020)", profile.requestedDynamicRangeLabel)
        assertFalse(profile.requestedDynamicRangeLabel.contains("Dolby", ignoreCase = true))
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, profile.colorEncoding.colorTransfer)
        assertTrue(profile.colorEncoding.requiresTenBit)
        assertTrue(profile.colorEncoding.main10Requested)
    }

    @Test
    fun resolveBitrateLookMultiplier_logRequestOverridesStaleHdrBudget() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        val multiplier = try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
                hdrMode = "HDR10",
                logEnabled = true,
                tenBit = true,
                colorSpace = "Rec.2020"
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }

        assertEquals(1.88f, multiplier, 0.0001f)
    }

    @Test
    fun resolveBitrateLookMultiplier_wideGamutSdrGetsAutoHdrBudget() {
        val sdr709 = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709"
        )
        val wideGamut = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.2020"
        )

        assertTrue(wideGamut > sdr709)
    }

    @Test
    fun resolveActiveDynamicRangeLabel_reportsSdrFallbackWhenHdrCannotShip() {
        val label = RecordingHdrVideoLookPolicy.resolveActiveDynamicRangeLabel(
            requestedDynamicRangeLabel = "HDR10",
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = false,
                main10Requested = false
            ),
            useTenBit = false,
            simulatedTenBitFallback = true
        )

        assertEquals("Original SDR (Rec.709 8-bit)", label)
    }

    @Test
    fun resolveActiveDynamicRangeLabel_preservesWideGamutFallbackIdentity() {
        val label = RecordingHdrVideoLookPolicy.resolveActiveDynamicRangeLabel(
            requestedDynamicRangeLabel = "HDR10",
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = false,
                main10Requested = false
            ),
            useTenBit = false,
            simulatedTenBitFallback = true
        )

        assertEquals("Wide Gamut SDR (Rec.2020 8-bit)", label)
    }

    @Test
    fun resolveActiveDynamicRangeLabel_preservesLogFallbackIdentity() {
        val label = RecordingHdrVideoLookPolicy.resolveActiveDynamicRangeLabel(
            requestedDynamicRangeLabel = "LOG SDR (Rec.2020)",
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = false,
                main10Requested = false
            ),
            useTenBit = false,
            simulatedTenBitFallback = true
        )

        assertEquals("LOG SDR (Rec.2020 8-bit)", label)
    }

    @Test
    fun resolveLookProfile_treatsAcesWideGamutAsHdrEligible() {
        val profile = RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = "HLG",
            videoColorSpace = "ACEScg",
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, profile.colorEncoding.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084,profile.colorEncoding.colorTransfer)
    }

    @Test
    fun resolveBitrateLookMultiplier_strictModeAddsCinemaBoostForHdr10Plus() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            val baseline = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
                hdrMode = "HDR10+",
                logEnabled = false,
                tenBit = true,
                colorSpace = "Rec.2020"
            )

            HighEndQualityPolicy.strictHighEndOnly = true
            val strict = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
                hdrMode = "HDR10+",
                logEnabled = false,
                tenBit = true,
                colorSpace = "Rec.2020"
            )

            assertTrue(strict > baseline)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun resolveBitrateLookMultiplier_mapsLegacyDynamicRangeBudgetToNdrAndKeepsNivukxHigher() {
        val legacyDynamicRange = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
            hdrMode = "HDR10+",
            logEnabled = false,
            tenBit = true,
            colorSpace = "Rec.2020"
        )
        val ndr10Plus = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
            hdrMode = "NDR10+",
            logEnabled = false,
            tenBit = true,
            colorSpace = "Rec.2020"
        )
        val nivukx = RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
            hdrMode = "NIVUKX Vision",
            logEnabled = false,
            tenBit = true,
            colorSpace = "Rec.2020"
        )

        assertEquals(ndr10Plus, legacyDynamicRange, 0.0001f)
        assertTrue(nivukx > ndr10Plus)
    }
}
