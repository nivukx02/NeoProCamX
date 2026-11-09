package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrDynamicRangePolicyTest {

    // ── HdrDynamicRangeMode.fromUiMode ────────────────────────────────────────

    @Test
    fun fromUiMode_offMapsToSdr() {
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode("OFF", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode("", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode(null, logEnabled = false))
    }

    @Test
    fun fromUiMode_mapsLegacyHdrVariantsToPqTransport() {
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("HDR10", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("hdr10", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode(" HDR10 ", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("HDR", logEnabled = false))
    }

    @Test
    fun fromUiMode_keepsExplicitHdr10PlusAsExplicitPlatformMode() {
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, HdrDynamicRangeMode.fromUiMode("HDR10+", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, HdrDynamicRangeMode.fromUiMode("hdr10+", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, HdrDynamicRangeMode.fromUiMode("HDR10PLUS", logEnabled = false))
    }

    @Test
    fun fromUiMode_routesNeoOwnedProfilesOverStaticHdr10Carrier() {
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("NDR10+", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("NIVUKX Vision", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("APEX Sharp", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("NIVUKX APEX Cinema", logEnabled = false))
    }

    @Test
    fun fromUiMode_mapsHlgToHlgAndRejectsLegacyVendorText() {
        assertEquals(HdrDynamicRangeMode.HLG, HdrDynamicRangeMode.fromUiMode("HLG", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode("Dolby Vision", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode("DV", logEnabled = false))
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode("DOLBYVISION", logEnabled = false))
    }

    @Test
    fun fromUiMode_logEnabledRoutesToSdrWhenHdrIsOff() {
        // LOG without an explicit HDR mode must not open an HDR camera session.
        // The encoder owns the LOG transfer, while Camera2 stays STANDARD.
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode("OFF", logEnabled = true))
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangeMode.fromUiMode(null, logEnabled = true))
    }

    @Test
    fun fromUiMode_explicitHdrOverridesLogFlag() {
        assertEquals(HdrDynamicRangeMode.HDR10, HdrDynamicRangeMode.fromUiMode("HDR10", logEnabled = true))
    }

    // ── HdrDynamicRangePolicy.buildSupport ────────────────────────────────────

    @Test
    fun buildSupport_emptyProfileSetIsNone() {
        val support = HdrDynamicRangePolicy.buildSupport(emptySet())
        assertEquals(HdrDynamicRangePolicy.Support.NONE, support)
        assertTrue(support.standardOnly)
        assertFalse(support.anyHdrSupported)
    }

    @Test
    fun buildSupport_standardOnlyDeviceIsRecognised() {
        val support = HdrDynamicRangePolicy.buildSupport(setOf(HdrDynamicRangePolicy.PROFILE_STANDARD))
        assertTrue(support.standardOnly)
        assertFalse(support.anyHdrSupported)
    }

    @Test
    fun buildSupport_hdr10AndHlgFlagsLightUpIndependently() {
        val hdr10Only = HdrDynamicRangePolicy.buildSupport(
            setOf(HdrDynamicRangePolicy.PROFILE_STANDARD, HdrDynamicRangePolicy.PROFILE_HDR10)
        )
        assertFalse(hdr10Only.standardOnly)
        assertTrue(hdr10Only.hdr10Supported)
        assertFalse(hdr10Only.hlg10Supported)
        assertTrue(hdr10Only.anyHdrSupported)

        val hlgOnly = HdrDynamicRangePolicy.buildSupport(
            setOf(HdrDynamicRangePolicy.PROFILE_STANDARD, HdrDynamicRangePolicy.PROFILE_HLG10)
        )
        assertFalse(hlgOnly.standardOnly)
        assertFalse(hlgOnly.hdr10Supported)
        assertTrue(hlgOnly.hlg10Supported)
    }

    @Test
    fun buildSupport_ignoresCertifiedVendorProfilesForNeoModes() {
        val support = HdrDynamicRangePolicy.buildSupport(
            setOf(
                HdrDynamicRangePolicy.PROFILE_STANDARD,
                HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_REF,
                HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM
            )
        )
        assertTrue(support.standardOnly)
        assertFalse(support.anyHdrSupported)
        assertNull(support.certifiedVendorDynamicRangeProfile)
    }

    @Test
    fun buildSupport_ignoresCertifiedVendorReferenceProfileWhenOnlyVendorTransportExists() {
        val support = HdrDynamicRangePolicy.buildSupport(
            setOf(
                HdrDynamicRangePolicy.PROFILE_STANDARD,
                HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_REF
            )
        )
        assertTrue(support.standardOnly)
        assertFalse(support.anyHdrSupported)
        assertNull(support.certifiedVendorDynamicRangeProfile)
    }

    // ── HdrDynamicRangePolicy.resolveProfilePure (no SDK gate) ────────────────

    private val flagshipSupport = HdrDynamicRangePolicy.Support(
        standardOnly = false,
        hlg10Supported = true,
        hdr10Supported = true,
        hdr10PlusSupported = true,
        certifiedVendorDynamicRangeProfile = null
    )

    private val midRangeSupport = HdrDynamicRangePolicy.Support(
        standardOnly = false,
        hlg10Supported = true,
        hdr10Supported = true,
        hdr10PlusSupported = false,
        certifiedVendorDynamicRangeProfile = null
    )

    private val hlgOnlySupport = HdrDynamicRangePolicy.Support(
        standardOnly = false,
        hlg10Supported = true,
        hdr10Supported = false,
        hdr10PlusSupported = false,
        certifiedVendorDynamicRangeProfile = null
    )

    @Test
    fun resolveProfile_rawSurfaceAlwaysStandard() {
        for (mode in HdrDynamicRangeMode.values()) {
            assertNull(
                "RAW surface must never carry HDR (mode=$mode)",
                HdrDynamicRangePolicy.resolveProfilePure(mode, flagshipSupport, HdrSurfaceRole.RAW)
            )
        }
    }

    @Test
    fun resolveProfile_jpegAndAnalysisAlwaysStandard() {
        for (mode in HdrDynamicRangeMode.values()) {
            assertNull(
                "JPEG must never carry HDR (mode=$mode)",
                HdrDynamicRangePolicy.resolveProfilePure(mode, flagshipSupport, HdrSurfaceRole.JPEG)
            )
            assertNull(
                "Analysis must never carry HDR (mode=$mode)",
                HdrDynamicRangePolicy.resolveProfilePure(mode, flagshipSupport, HdrSurfaceRole.ANALYSIS)
            )
        }
    }

    @Test
    fun resolveProfile_sdrModeAlwaysReturnsNull() {
        for (role in listOf(HdrSurfaceRole.PREVIEW, HdrSurfaceRole.ENCODER)) {
            assertNull(
                HdrDynamicRangePolicy.resolveProfilePure(HdrDynamicRangeMode.SDR, flagshipSupport, role)
            )
        }
    }

    @Test
    fun resolveProfile_standardOnlyDeviceForcesNullForEveryHdrMode() {
        for (mode in listOf(
            HdrDynamicRangeMode.HLG,
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangeMode.HDR10_PLUS,
            HdrDynamicRangeMode.DOLBY_VISION
        )) {
            assertNull(
                "Standard-only device must fall back to null (mode=$mode)",
                HdrDynamicRangePolicy.resolveProfilePure(
                    mode,
                    HdrDynamicRangePolicy.Support.NONE,
                    HdrSurfaceRole.PREVIEW
                )
            )
        }
    }

    @Test
    fun resolveProfile_previewAndEncoderResolveIdentically() {
        for (mode in HdrDynamicRangeMode.values()) {
            for (support in listOf(flagshipSupport, midRangeSupport, hlgOnlySupport)) {
                val previewProfile =
                    HdrDynamicRangePolicy.resolveProfilePure(mode, support, HdrSurfaceRole.PREVIEW)
                val encoderProfile =
                    HdrDynamicRangePolicy.resolveProfilePure(mode, support, HdrSurfaceRole.ENCODER)
                assertEquals(
                    "Preview/encoder parity must hold for mode=$mode support=$support",
                    previewProfile,
                    encoderProfile
                )
            }
        }
    }

    @Test
    fun resolveProfile_hdr10PicksHdr10OnFlagship() {
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_HDR10,
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.HDR10, flagshipSupport, HdrSurfaceRole.PREVIEW
            )
        )
    }

    @Test
    fun resolveProfile_hdr10PlusFallsBackToHdr10WhenPlusUnavailable() {
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_HDR10,
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.HDR10_PLUS, midRangeSupport, HdrSurfaceRole.PREVIEW
            )
        )
    }

    @Test
    fun resolveProfile_hdr10PlusRejectsHlgOnlyTransport() {
        assertNull(
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.HDR10_PLUS, hlgOnlySupport, HdrSurfaceRole.PREVIEW
            )
        )
    }

    @Test
    fun resolveProfile_hdr10RejectsHlgOnlyTransport() {
        assertNull(
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.HDR10, hlgOnlySupport, HdrSurfaceRole.PREVIEW
            )
        )
    }

    @Test
    fun transportModeForProfileMatchesActualCamera2Profile() {
        assertEquals(
            HdrDynamicRangeMode.HLG,
            HdrDynamicRangePolicy.transportModeForProfile(HdrDynamicRangePolicy.PROFILE_HLG10)
        )
        assertEquals(
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangePolicy.transportModeForProfile(HdrDynamicRangePolicy.PROFILE_HDR10)
        )
        assertEquals(
            HdrDynamicRangeMode.HDR10_PLUS,
            HdrDynamicRangePolicy.transportModeForProfile(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS)
        )
        assertEquals(
            HdrDynamicRangeMode.SDR,
            HdrDynamicRangePolicy.transportModeForProfile(
                HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM
            )
        )
        assertEquals(HdrDynamicRangeMode.SDR, HdrDynamicRangePolicy.transportModeForProfile(null))
    }

    @Test
    fun resolveProfile_hlgOnDeviceWithoutHlgReturnsNull() {
        val noHlg = HdrDynamicRangePolicy.Support(
            standardOnly = false,
            hlg10Supported = false,
            hdr10Supported = true,
            hdr10PlusSupported = false,
            certifiedVendorDynamicRangeProfile = null
        )
        assertNull(
            HdrDynamicRangePolicy.resolveProfilePure(HdrDynamicRangeMode.HLG, noHlg, HdrSurfaceRole.PREVIEW)
        )
    }

    @Test
    fun resolveProfile_vendorModeDoesNotUseCertifiedVendorProfileWhenAvailable() {
        assertNull(
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.DOLBY_VISION, flagshipSupport, HdrSurfaceRole.PREVIEW
            )
        )
    }

    @Test
    fun resolveProfile_dolbyVisionFallsBackToHdr10WhenDvUnavailable() {
        assertNull(
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.DOLBY_VISION, midRangeSupport, HdrSurfaceRole.PREVIEW
            )
        )
    }

    @Test
    fun resolveProfile_dolbyVisionFallsBackToHlgOnHlgOnlyDevice() {
        assertNull(
            HdrDynamicRangePolicy.resolveProfilePure(
                HdrDynamicRangeMode.DOLBY_VISION, hlgOnlySupport, HdrSurfaceRole.PREVIEW
            )
        )
    }
}
