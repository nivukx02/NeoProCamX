package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

class HdrStaticMetadataPolicyTest {

    // ── chromaticity / luminance encoders ────────────────────────────────────

    @Test
    fun encodeChromaticity_bt2020RedX_matchesSpec() {
        // BT.2020 red x = 0.708 → 0.708 × 50000 = 35400
        val encoded = HdrStaticMetadataPolicy.encodeChromaticity(0.708)
        assertEquals(35400, java.lang.Short.toUnsignedInt(encoded))
    }

    @Test
    fun encodeChromaticity_d65WhiteX_matchesSpec() {
        // D65 white x = 0.3127 → 0.3127 × 50000 = 15635
        val encoded = HdrStaticMetadataPolicy.encodeChromaticity(0.3127)
        assertEquals(15635, java.lang.Short.toUnsignedInt(encoded))
    }

    @Test
    fun encodeChromaticity_clampsOutOfRangeValues() {
        // Negative or >1.31 chromaticities should clamp.
        val low = HdrStaticMetadataPolicy.encodeChromaticity(-0.5)
        val high = HdrStaticMetadataPolicy.encodeChromaticity(2.0)
        assertEquals(0, java.lang.Short.toUnsignedInt(low))
        assertEquals(65535, java.lang.Short.toUnsignedInt(high))
    }

    @Test
    fun encodeMaxLuminance_isInDirectNits() {
        // 1000 cd/m² → 1000 (no scaling).
        assertEquals(
            1000,
            java.lang.Short.toUnsignedInt(HdrStaticMetadataPolicy.encodeMaxLuminance(1000.0))
        )
    }

    @Test
    fun encodeMinLuminance_isInTenThousandthsOfNit() {
        // 0.0001 cd/m² → 1 (×10000).
        assertEquals(
            1,
            java.lang.Short.toUnsignedInt(HdrStaticMetadataPolicy.encodeMinLuminance(0.0001))
        )
        // 0.005 cd/m² → 50.
        assertEquals(
            50,
            java.lang.Short.toUnsignedInt(HdrStaticMetadataPolicy.encodeMinLuminance(0.005))
        )
    }

    @Test
    fun encodeLightLevel_clampsTo16bit() {
        assertEquals(
            65535,
            java.lang.Short.toUnsignedInt(HdrStaticMetadataPolicy.encodeLightLevel(70000))
        )
        assertEquals(
            0,
            java.lang.Short.toUnsignedInt(HdrStaticMetadataPolicy.encodeLightLevel(-1000))
        )
    }

    // ── full 25-byte buffer layout ───────────────────────────────────────────

    @Test
    fun buildHdr10StaticInfo_is25BytesLittleEndian() {
        val buffer = HdrStaticMetadataPolicy.buildHdr10StaticInfo()
        assertEquals(25, buffer.capacity())
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
        assertEquals(0, buffer.position())
    }

    @Test
    fun buildHdr10StaticInfo_typeByteIsZero() {
        val buffer = HdrStaticMetadataPolicy.buildHdr10StaticInfo()
        assertEquals(0x00.toByte(), buffer.get(0))
    }

    @Test
    fun buildHdr10StaticInfo_defaultMatchesBt2020D65At1000Nits() {
        val buffer = HdrStaticMetadataPolicy.buildHdr10StaticInfo()
        // Read each chromaticity field and verify it matches the BT.2020
        // / D65 / 1000-nit reference encoding.
        // R.x at offset 1, R.y at offset 3, ...
        fun shortAt(i: Int): Int =
            java.lang.Short.toUnsignedInt(buffer.getShort(i))
        assertEquals(35400, shortAt(1))   // R.x = 0.708 × 50000
        assertEquals(14600, shortAt(3))   // R.y = 0.292 × 50000
        assertEquals(8500, shortAt(5))    // G.x = 0.170 × 50000
        assertEquals(39850, shortAt(7))   // G.y = 0.797 × 50000
        assertEquals(6550, shortAt(9))    // B.x = 0.131 × 50000
        assertEquals(2300, shortAt(11))   // B.y = 0.046 × 50000
        assertEquals(15635, shortAt(13))  // W.x = 0.3127 × 50000
        assertEquals(16450, shortAt(15))  // W.y = 0.3290 × 50000
        assertEquals(1000, shortAt(17))   // max display luminance
        assertEquals(1, shortAt(19))      // min display luminance × 10000
        assertEquals(1000, shortAt(21))   // MaxCLL
        assertEquals(400, shortAt(23))    // MaxFALL
    }

    @Test
    fun buildHdr10StaticInfo_4000NitMasterRaisesPeakAndMaxCll() {
        val buffer = HdrStaticMetadataPolicy.buildHdr10StaticInfo(
            HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_4000_NITS
        )
        fun shortAt(i: Int): Int =
            java.lang.Short.toUnsignedInt(buffer.getShort(i))
        assertEquals(4000, shortAt(17))  // max display luminance
        assertEquals(4000, shortAt(21))  // MaxCLL
        assertEquals(1000, shortAt(23))  // MaxFALL
    }

    // ── StaticMetadataMode.fromColorEncoding ─────────────────────────────────
    // (these tests use the Android constants — running them requires being
    // on a JVM with API 33+ media constants available, which the project
    // already uses for other recording tests.)

    @Test
    fun fromColorEncoding_pqAndBt2020IsHdr10() {
        val mode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
            colorTransfer = android.media.MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = android.media.MediaFormat.COLOR_STANDARD_BT2020,
            hdr10PlusRequested = false
        )
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HDR10, mode)
    }

    @Test
    fun fromColorEncoding_pqAndBt2020WithHdr10PlusFlagIsHdr10Plus() {
        val mode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
            colorTransfer = android.media.MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = android.media.MediaFormat.COLOR_STANDARD_BT2020,
            hdr10PlusRequested = true
        )
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HDR10_PLUS, mode)
    }

    @Test
    fun fromColorEncoding_pqAndBt2020WithDolbyFallbackFlagIsDolbyFallback() {
        val mode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
            colorTransfer = android.media.MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = android.media.MediaFormat.COLOR_STANDARD_BT2020,
            certifiedVendorFallbackRequested = true
        )
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK, mode)
    }

    @Test
    fun fromColorEncoding_hlgAndBt2020IsHlg() {
        val mode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
            colorTransfer = android.media.MediaFormat.COLOR_TRANSFER_HLG,
            colorStandard = android.media.MediaFormat.COLOR_STANDARD_BT2020
        )
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HLG, mode)
    }

    @Test
    fun fromColorEncoding_pqWithoutBt2020FallsBackToSdr() {
        // Mismatched: PQ transfer but BT.709 colour standard isn't a valid
        // HDR10 stream — must fall back to SDR.
        val mode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
            colorTransfer = android.media.MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = android.media.MediaFormat.COLOR_STANDARD_BT709
        )
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, mode)
    }

    @Test
    fun fromColorEncoding_sdrIsSdr() {
        val mode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
            colorTransfer = android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = android.media.MediaFormat.COLOR_STANDARD_BT709
        )
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, mode)
    }

    // ── default mastering display values ─────────────────────────────────────

    @Test
    fun masteringDisplay_defaultsAreBt2020D65At1000Nits() {
        val md = HdrStaticMetadataPolicy.MasteringDisplay()
        assertEquals(0.708, md.redX, 1e-6)
        assertEquals(0.292, md.redY, 1e-6)
        assertEquals(0.170, md.greenX, 1e-6)
        assertEquals(0.797, md.greenY, 1e-6)
        assertEquals(0.131, md.blueX, 1e-6)
        assertEquals(0.046, md.blueY, 1e-6)
        assertEquals(0.3127, md.whiteX, 1e-6)
        assertEquals(0.3290, md.whiteY, 1e-6)
        assertEquals(1000.0, md.maxLuminanceNits, 1e-6)
        assertEquals(0.0001, md.minLuminanceNits, 1e-6)
        assertEquals(1000, md.maxContentLightLevelNits)
        assertEquals(400, md.maxFrameAverageLightLevelNits)
    }

    @Test
    fun masteringDisplay_4000NitVariantIsValidPresetVariant() {
        val md = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_4000_NITS
        // Same primaries — only luminance changes
        val baseline = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_1000_NITS
        assertEquals(baseline.redX, md.redX, 1e-6)
        assertEquals(baseline.greenX, md.greenX, 1e-6)
        assertEquals(baseline.blueX, md.blueX, 1e-6)
        assertEquals(baseline.whiteX, md.whiteX, 1e-6)
        assertTrue(md.maxLuminanceNits > baseline.maxLuminanceNits)
        assertTrue(md.maxContentLightLevelNits > baseline.maxContentLightLevelNits)
    }

    @Test
    fun sceneMetadata_fromDescriptionParsesHdrSignalFields() {
        val metadata = HdrStaticMetadataPolicy.SceneMetadata.fromDescription(
            "mode=HDR;avgLuma=0.390;highlight=0.180;dynamicRange=0.860;hdrMaxCLL=2440;hdrMaxFALL=530;hdrMasterPeak=4000"
        )

        assertEquals(0.390, metadata.averageLuma, 1e-6)
        assertEquals(0.180, metadata.highlightRatio, 1e-6)
        assertEquals(0.860, metadata.dynamicRangeScore, 1e-6)
        assertEquals(2440.0, metadata.maxCllNits ?: 0.0, 1e-6)
        assertEquals(530.0, metadata.maxFallNits ?: 0.0, 1e-6)
        assertEquals(4000.0, metadata.masteringPeakNits ?: 0.0, 1e-6)
    }

    @Test
    fun resolveMasteringDisplay_usesSceneAdaptiveLightLevelsForHdrVersions() {
        val scene = HdrStaticMetadataPolicy.SceneMetadata(
            averageLuma = 0.40,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.86
        )
        val hdr10 = HdrStaticMetadataPolicy.resolveMasteringDisplay(
            mode = HdrStaticMetadataPolicy.StaticMetadataMode.HDR10,
            sceneMetadata = scene
        )
        val hdr10Plus = HdrStaticMetadataPolicy.resolveMasteringDisplay(
            mode = HdrStaticMetadataPolicy.StaticMetadataMode.HDR10_PLUS,
            sceneMetadata = scene
        )
        val dolby = HdrStaticMetadataPolicy.resolveMasteringDisplay(
            mode = HdrStaticMetadataPolicy.StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK,
            sceneMetadata = scene
        )

        assertEquals(4000.0, hdr10.maxLuminanceNits, 1e-6)
        assertTrue(hdr10.maxContentLightLevelNits > 1000)
        assertTrue(hdr10Plus.maxContentLightLevelNits >= hdr10.maxContentLightLevelNits)
        assertTrue(dolby.maxContentLightLevelNits >= hdr10Plus.maxContentLightLevelNits)
        assertTrue(dolby.maxFrameAverageLightLevelNits >= hdr10.maxFrameAverageLightLevelNits)
    }
}
