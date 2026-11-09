package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.rendering.HdrSwapchainFormatPolicy.SurfaceFormat
import com.neoproc.neoproccamx.rendering.HdrSwapchainFormatPolicy.SurfaceRole
import com.neoproc.neoproccamx.rendering.HdrSwapchainFormatPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrSwapchainFormatPolicyTest {

    private val srgbBgra8 = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_B8G8R8A8_UNORM,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
    )
    private val srgbRgba8 = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_R8G8B8A8_UNORM,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
    )
    private val srgb10Bit = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
    )
    private val pq10Bit = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_ST2084_EXT
    )
    private val hlg10Bit = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_HLG_EXT
    )
    private val bt2020Linear10Bit = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_BT2020_LINEAR_EXT
    )
    private val fp16ExtLinear = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_R16G16B16A16_SFLOAT,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT
    )
    private val fp16Srgb = SurfaceFormat(
        HdrSwapchainFormatPolicy.VK_FORMAT_R16G16B16A16_SFLOAT,
        HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
    )

    // ── undefined sentinel branch ────────────────────────────────────────────

    @Test
    fun resolve_undefinedSentinelInSdrMode_returnsBgra8Srgb() {
        val undefined = SurfaceFormat(
            HdrSwapchainFormatPolicy.VK_FORMAT_UNDEFINED,
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        )
        val result = HdrSwapchainFormatPolicy.resolve(listOf(undefined), HdrDynamicRangeMode.SDR)
        assertEquals(Tier.UNDEFINED_SENTINEL, result.tier)
        assertEquals(HdrSwapchainFormatPolicy.VK_FORMAT_B8G8R8A8_UNORM, result.format.format)
    }

    @Test
    fun resolve_undefinedSentinelInHdrMode_returns10BitSrgb() {
        val undefined = SurfaceFormat(
            HdrSwapchainFormatPolicy.VK_FORMAT_UNDEFINED,
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        )
        val result = HdrSwapchainFormatPolicy.resolve(listOf(undefined), HdrDynamicRangeMode.HDR10)
        assertEquals(Tier.UNDEFINED_SENTINEL, result.tier)
        assertEquals(
            HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
            result.format.format
        )
    }

    @Test
    fun resolve_emptyAvailableFormats_returnsDefensiveFallback() {
        val result = HdrSwapchainFormatPolicy.resolve(emptyList(), HdrDynamicRangeMode.HDR10)
        assertEquals(Tier.DRIVER_FIRST_AVAILABLE, result.tier)
    }

    // ── SDR mode ─────────────────────────────────────────────────────────────

    @Test
    fun sdr_prefersBgra8Over10BitWhenBothAvailable() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(pq10Bit, srgb10Bit, srgbBgra8),
            HdrDynamicRangeMode.SDR
        )
        // SDR path must NOT pick the 10-bit/HDR option even when offered
        // — it would result in a washed-out preview.
        assertEquals(Tier.EIGHT_BIT_SRGB, result.tier)
        assertEquals(srgbBgra8, result.format)
    }

    @Test
    fun sdr_prefersRgba8WhenBgra8Unavailable() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(pq10Bit, srgbRgba8),
            HdrDynamicRangeMode.SDR
        )
        assertEquals(Tier.EIGHT_BIT_SRGB, result.tier)
        assertEquals(srgbRgba8, result.format)
    }

    @Test
    fun sdr10EncoderUsesTenBitSurfaceWithoutChangingEightBitDisplaySelection() {
        val formats = listOf(srgbBgra8, srgb10Bit)

        val display = HdrSwapchainFormatPolicy.resolve(
            availableFormats = formats,
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            requireTenBitSdr = true
        )
        val encoder = HdrSwapchainFormatPolicy.resolve(
            availableFormats = formats,
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.ENCODER,
            requireTenBitSdr = true
        )

        assertEquals(srgbBgra8, display.format)
        assertEquals(8, display.formatBitDepth)
        assertFalse(display.tenBitSdrDeliveryCertified)
        assertEquals(srgb10Bit, encoder.format)
        assertEquals(Tier.TEN_BIT_SDR_ENCODER, encoder.tier)
        assertEquals(10, encoder.formatBitDepth)
        assertTrue(encoder.tenBitSdrDeliveryCertified)
        assertNotEquals(display.deliveryIdentity, encoder.deliveryIdentity)
    }

    @Test
    fun tenBitDisplayFormatNeverCertifiesTenBitEncoderDelivery() {
        val display = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(srgb10Bit),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            requireTenBitSdr = true
        )

        assertEquals(10, display.formatBitDepth)
        assertFalse(display.tenBitSdrDeliveryCertified)
        assertTrue(display.deliveryIdentity.contains("DISPLAY"))
    }

    // ── SDR 10-bit DISPLAY (preview) tier — X2/R1 ────────────────────────────

    @Test
    fun sdrDisplay_preferTenBit_picksTenBitSrgbWhenAvailable() {
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(srgbBgra8, srgb10Bit),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        assertEquals(srgb10Bit, result.format)
        assertEquals(Tier.TEN_BIT_SDR_DISPLAY, result.tier)
        assertEquals(10, result.formatBitDepth)
        assertTrue(result.tenBitSdrDeliveryCertified)
    }

    @Test
    fun sdrDisplay_preferTenBit_fallsBackToEightBitWhenNoTenBitPair() {
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(srgbBgra8),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        // Byte-exact graceful fallback — identical to today's 8-bit preview, no downgrade.
        assertEquals(srgbBgra8, result.format)
        assertEquals(Tier.EIGHT_BIT_SRGB, result.tier)
        assertEquals(8, result.formatBitDepth)
        assertFalse(result.tenBitSdrDeliveryCertified)
    }

    @Test
    fun sdrDisplay_preferTenBit_neverPicksPqOrHlg() {
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(pq10Bit, hlg10Bit, srgb10Bit),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        assertEquals(srgb10Bit, result.format)
        assertNotEquals(
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_ST2084_EXT, result.format.colorSpace
        )
        assertNotEquals(
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_HLG_EXT, result.format.colorSpace
        )
    }

    @Test
    fun sdrDisplay_preferTenBit_onlyHdrColourspaceTenBit_picksNonStrictEotf() {
        // No sRGB-tagged 10-bit pair; must still avoid PQ/HLG and pick a non-strict-EOTF 10-bit
        // format (BT.2020 linear) — never an EOTF-enforcing colourspace.
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(pq10Bit, hlg10Bit, bt2020Linear10Bit),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        assertEquals(bt2020Linear10Bit, result.format)
        assertNotEquals(
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_ST2084_EXT, result.format.colorSpace
        )
        assertNotEquals(
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_HLG_EXT, result.format.colorSpace
        )
    }

    @Test
    fun sdrDisplay_defaultFlagFalse_stillPicksEightBit() {
        // No regression: with the flag defaulted false, SDR DISPLAY still prefers 8-bit sRGB.
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(srgbBgra8, srgb10Bit),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY
        )
        assertEquals(srgbBgra8, result.format)
        assertEquals(Tier.EIGHT_BIT_SRGB, result.tier)
    }

    @Test
    fun sdrDisplay_undefinedSentinel_preferTenBit_returnsTenBitSrgb() {
        val undefined = SurfaceFormat(
            HdrSwapchainFormatPolicy.VK_FORMAT_UNDEFINED,
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        )
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = listOf(undefined),
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        assertEquals(Tier.UNDEFINED_SENTINEL, result.tier)
        assertEquals(
            HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32, result.format.format
        )
        assertEquals(
            HdrSwapchainFormatPolicy.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, result.format.colorSpace
        )
    }

    @Test
    fun displayTenBitAndEncoderTenBit_haveDistinctDeliveryIdentities() {
        val formats = listOf(srgb10Bit)
        val display = HdrSwapchainFormatPolicy.resolve(
            availableFormats = formats,
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        val encoder = HdrSwapchainFormatPolicy.resolve(
            availableFormats = formats,
            mode = HdrDynamicRangeMode.SDR,
            surfaceRole = SurfaceRole.ENCODER,
            requireTenBitSdr = true
        )
        assertEquals(Tier.TEN_BIT_SDR_DISPLAY, display.tier)
        assertEquals(Tier.TEN_BIT_SDR_ENCODER, encoder.tier)
        assertTrue(display.tenBitSdrDeliveryCertified)
        assertTrue(encoder.tenBitSdrDeliveryCertified)
        assertNotEquals(display.deliveryIdentity, encoder.deliveryIdentity)
    }

    @Test
    fun sdrDisplayPreference_isIgnoredForHdrMode() {
        val flagship = listOf(srgbBgra8, srgb10Bit, pq10Bit, hlg10Bit, bt2020Linear10Bit)
        val result = HdrSwapchainFormatPolicy.resolve(
            availableFormats = flagship,
            mode = HdrDynamicRangeMode.HDR10,
            surfaceRole = SurfaceRole.DISPLAY,
            preferTenBitSdrDisplay = true
        )
        // The new SDR-display flag must not perturb the HDR path.
        assertEquals(pq10Bit, result.format)
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
    }

    @Test
    fun sdr_neverPicksHdrColourspace() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(pq10Bit, hlg10Bit, bt2020Linear10Bit),
            HdrDynamicRangeMode.SDR
        )
        // Only HDR formats available — must fall back, but must NOT carry
        // an HDR colourspace as that would still mis-render SDR content.
        assertNotEquals(HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_ST2084_EXT,
            result.format.colorSpace)
        assertNotEquals(HdrSwapchainFormatPolicy.VK_COLOR_SPACE_HDR10_HLG_EXT,
            result.format.colorSpace)
    }

    // ── HDR10 / HDR10+ / Dolby Vision ────────────────────────────────────────

    @Test
    fun hdr10_picks10BitPqWhenAvailable() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, srgb10Bit, pq10Bit),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
        assertEquals(pq10Bit, result.format)
    }

    @Test
    fun hdr10Plus_alsoPicks10BitPq() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(pq10Bit, hlg10Bit),
            HdrDynamicRangeMode.HDR10_PLUS
        )
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
        assertEquals(pq10Bit, result.format)
    }

    @Test
    fun dolbyVision_picksPqColourspace() {
        // Dolby Vision is HDR10-compatible at the swapchain level.
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(hlg10Bit, pq10Bit),
            HdrDynamicRangeMode.DOLBY_VISION
        )
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
        assertEquals(pq10Bit, result.format)
    }

    @Test
    fun hdr10_fallsBackToBt2020LinearWhenPqAbsent() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, bt2020Linear10Bit),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
        assertEquals(bt2020Linear10Bit, result.format)
    }

    @Test
    fun hdr10_fallsBackToFp16ExtendedLinearWhenNo10BitHdr() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, fp16ExtLinear),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.FP16_HDR_EXTENDED_LINEAR, result.tier)
        assertEquals(fp16ExtLinear, result.format)
    }

    @Test
    fun hdr10_fallsBackTo10BitSrgbWhenNoHdrColourspace() {
        // Driver doesn't expose HDR colourspaces (no
        // VK_EXT_swapchain_colorspace) but does expose 10-bit format.
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, srgb10Bit),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.TEN_BIT_SRGB_FALLBACK, result.tier)
        assertEquals(srgb10Bit, result.format)
    }

    @Test
    fun hdr10_fallsBackToFp16SrgbBeforeEightBit() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, fp16Srgb),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.FP16_SRGB_FALLBACK, result.tier)
        assertEquals(fp16Srgb, result.format)
    }

    @Test
    fun hdr10_fallsBackToEightBitSrgbAsLastResort() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.EIGHT_BIT_SRGB, result.tier)
        assertEquals(srgbBgra8, result.format)
    }

    // ── HLG specific routing ─────────────────────────────────────────────────

    @Test
    fun hlg_prefersHlgOverPqColourspace() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(pq10Bit, hlg10Bit),
            HdrDynamicRangeMode.HLG
        )
        // HLG mode must bind to HLG colourspace — using PQ would force an
        // EOTF mismatch and the picture would look completely wrong.
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
        assertEquals(hlg10Bit, result.format)
    }

    @Test
    fun hlg_fallsBackToBt2020LinearWhenHlgAbsent() {
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, bt2020Linear10Bit, pq10Bit),
            HdrDynamicRangeMode.HLG
        )
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, result.tier)
        assertEquals(bt2020Linear10Bit, result.format)
    }

    // ── Tier ordering parity ─────────────────────────────────────────────────

    @Test
    fun tierOrdering_tenBitHdrAlwaysBeatsFp16() {
        val withBoth = listOf(fp16ExtLinear, pq10Bit)
        val result = HdrSwapchainFormatPolicy.resolve(withBoth, HdrDynamicRangeMode.HDR10)
        assertEquals(pq10Bit, result.format)
    }

    @Test
    fun tierOrdering_fp16ExtendedLinearBeatsTenBitSrgb() {
        // Even though both keep some HDR fidelity, FP16 extended-sRGB
        // linear is the more universal HDR path on Vulkan.
        val result = HdrSwapchainFormatPolicy.resolve(
            listOf(srgbBgra8, srgb10Bit, fp16ExtLinear),
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.FP16_HDR_EXTENDED_LINEAR, result.tier)
        assertEquals(fp16ExtLinear, result.format)
    }

    // ── JNI mode-id round-trip ───────────────────────────────────────────────

    @Test
    fun nativeModeId_roundTripsForEveryMode() {
        for (mode in HdrDynamicRangeMode.values()) {
            val id = HdrSwapchainFormatPolicy.toNativeModeId(mode)
            assertEquals(mode, HdrSwapchainFormatPolicy.fromNativeModeId(id))
        }
    }

    @Test
    fun nativeModeId_unknownIdMapsToSdr() {
        assertEquals(
            HdrDynamicRangeMode.SDR,
            HdrSwapchainFormatPolicy.fromNativeModeId(99)
        )
        assertEquals(
            HdrDynamicRangeMode.SDR,
            HdrSwapchainFormatPolicy.fromNativeModeId(-1)
        )
    }

    @Test
    fun nativeModeId_idsAreStableAndDistinct() {
        // These IDs cross the JNI boundary and are read by C++. If you
        // need to add a new mode, append at the end — never reorder.
        assertEquals(0, HdrSwapchainFormatPolicy.toNativeModeId(HdrDynamicRangeMode.SDR))
        assertEquals(1, HdrSwapchainFormatPolicy.toNativeModeId(HdrDynamicRangeMode.HLG))
        assertEquals(2, HdrSwapchainFormatPolicy.toNativeModeId(HdrDynamicRangeMode.HDR10))
        assertEquals(3, HdrSwapchainFormatPolicy.toNativeModeId(HdrDynamicRangeMode.HDR10_PLUS))
        assertEquals(4, HdrSwapchainFormatPolicy.toNativeModeId(HdrDynamicRangeMode.DOLBY_VISION))
        // Distinctness — no two modes collide
        val ids = HdrDynamicRangeMode.values()
            .map { HdrSwapchainFormatPolicy.toNativeModeId(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun resolve_realisticFlagshipDeviceFormatList_picksPqForHdr10() {
        // Snapshot of a realistic Pixel 8 Pro / Galaxy S24 surface format
        // list with VK_EXT_swapchain_colorspace enabled.
        val flagship = listOf(
            srgbBgra8,
            srgbRgba8,
            srgb10Bit,
            pq10Bit,
            hlg10Bit,
            bt2020Linear10Bit,
            fp16ExtLinear,
            fp16Srgb
        )
        val hdr10 = HdrSwapchainFormatPolicy.resolve(flagship, HdrDynamicRangeMode.HDR10)
        assertEquals(pq10Bit, hdr10.format)
        assertEquals(Tier.TEN_BIT_HDR_NATIVE, hdr10.tier)

        val hlg = HdrSwapchainFormatPolicy.resolve(flagship, HdrDynamicRangeMode.HLG)
        assertEquals(hlg10Bit, hlg.format)

        val sdr = HdrSwapchainFormatPolicy.resolve(flagship, HdrDynamicRangeMode.SDR)
        assertEquals(srgbBgra8, sdr.format)
        assertEquals(Tier.EIGHT_BIT_SRGB, sdr.tier)
    }

    @Test
    fun resolve_hdrOnDeviceWithoutSwapchainColorspaceExtension_picksTenBitSrgb() {
        // Driver hasn't enabled VK_EXT_swapchain_colorspace, so HDR
        // colourspaces aren't reported. Best we can do for HDR is the
        // 10-bit packed format with sRGB tagging — bit depth survives,
        // and the system compositor handles transfer-function mapping.
        val withoutHdrColourspace = listOf(srgbBgra8, srgb10Bit)
        val result = HdrSwapchainFormatPolicy.resolve(
            withoutHdrColourspace,
            HdrDynamicRangeMode.HDR10
        )
        assertEquals(Tier.TEN_BIT_SRGB_FALLBACK, result.tier)
        assertEquals(srgb10Bit, result.format)
        // And critically — the bit depth IS preserved.
        assertTrue(
            "10-bit format must be selected even when HDR colourspace is unavailable",
            result.format.format == HdrSwapchainFormatPolicy.VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                result.format.format == HdrSwapchainFormatPolicy.VK_FORMAT_A2R10G10B10_UNORM_PACK32
        )
    }
}
