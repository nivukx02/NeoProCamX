package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatDeliveryPolicyTest {

    @Test
    fun `normal pro video uses normal preview without hdr log or raw delivery`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF"
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertEquals("Rec.709", state.effectiveColorSpace)
        assertTrue(state.neutralPreview)
        assertTrue(state.truthfulColorDelivery)
        assertTrue(state.tenBitActive)
        assertFalse(state.logVideoActive)
        assertFalse(state.rawVideoActive)
        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, state.pipelineContract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.SDR, state.pipelineContract.previewKind)
    }

    @Test
    fun `ten bit pro video remains rec709 sdr unless hdr or log is selected`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.709",
            tenBitActive = true
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertEquals("Rec.709", state.effectiveColorSpace)
        assertEquals(VideoPipelinePreviewKind.SDR, state.pipelineContract.previewKind)
        assertFalse(state.requiresHdrContainerMetadata)
        assertFalse(state.pipelineContract.hdrToneMapActive)
    }

    @Test
    fun `wide gamut pro sdr preserves color space and requests ten bit without hdr metadata`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitActive = false
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertEquals("Rec.2020", state.effectiveColorSpace)
        assertTrue(state.tenBitActive)
        assertTrue(state.truthfulColorDelivery)
        assertFalse(state.requiresHdrContainerMetadata)
        assertFalse(state.pipelineContract.hdrToneMapActive)
        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, state.pipelineContract.deliveryKind)
        assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, state.pipelineContract.toneMap)
        assertEquals(NeoRadianceDeliveryTarget.SDR_BT2020, state.neoRadianceDeliveryPlan.target)
        assertEquals("BT.2020", state.neoRadianceDeliveryPlan.colorStandard)
        assertEquals("SDR_VIDEO", state.neoRadianceDeliveryPlan.transferFunction)
    }

    @Test
    fun `aces and neo wide manual sdr delivery keep canonical identity while using bt2020 container tags`() {
        val ap0 = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "ACES AP0",
            tenBitActive = true
        )
        val ap1 = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "ACEScg",
            tenBitActive = true
        )
        val neoWide = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Display P3",
            tenBitActive = true
        )

        assertEquals("Neo ACES AP0", ap0.effectiveColorSpace)
        assertEquals("Neo ACES AP1", ap1.effectiveColorSpace)
        assertEquals("Neo Wide Gamut", neoWide.effectiveColorSpace)
        listOf(ap0, ap1, neoWide).forEach { state ->
            assertFalse(state.neutralPreview)
            assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, state.pipelineContract.toneMap)
            assertEquals(NeoRadianceDeliveryTarget.SDR_BT2020, state.neoRadianceDeliveryPlan.target)
            assertEquals("BT.2020", state.neoRadianceDeliveryPlan.colorStandard)
            assertEquals("SDR_VIDEO", state.neoRadianceDeliveryPlan.transferFunction)
            assertFalse(state.requiresHdrContainerMetadata)
        }
    }

    @Test
    fun `requested pro sdr ten bit is not reported active when p010 is unavailable`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.709",
            tenBitActive = true,
            gpu10BitSupported = false
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertFalse(state.tenBitActive)
        assertFalse(state.requiresHdrContainerMetadata)
        assertFalse(state.pipelineContract.tenBitTransportRequested)
        assertTrue(state.pipelineContract.strictSdrTenBitBlocked)
    }

    @Test
    fun `wide gamut pro sdr is not marked truthful ten bit when gpu p010 is unavailable`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true,
            gpu10BitSupported = false,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                tenBitSupported = true,
                wideGamutSdrSupported = true,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertEquals("Rec.2020", state.effectiveColorSpace)
        assertFalse(state.tenBitActive)
        assertFalse(state.truthfulColorDelivery)
        assertFalse(state.requiresHdrContainerMetadata)
        assertTrue(state.pipelineContract.strictSdrTenBitBlocked)
    }

    @Test
    fun `ndr preview stays original when legacy hdr input maps to ndr mode`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HDR10"
        )

        assertEquals("NDR10+", state.effectiveHdrMode)
        assertTrue(state.truthfulColorDelivery)
        assertFalse(state.neutralPreview)
        assertFalse(state.logVideoActive)
        assertFalse(state.rawVideoActive)
    }

    @Test
    fun `ndr delivery maps legacy dynamic range aliases to neo profiles`() {
        val cases = mapOf(
            "HLG" to "NDR10+",
            "HDR10" to "NDR10+",
            "HDR10PLUS" to "NDR10+",
            "HDR10+" to "NDR10+",
            "NDR10+" to "NDR10+",
            "NIVUKX Vision" to "NIVUKXVISION"
        )

        for ((input, expected) in cases) {
            val state = VideoFormatDeliveryPolicy.resolve(
                uiMode = AppMode.VIDEO,
                cinemaMode = CinemaVideoMode.PRO,
                rawVideoActive = false,
                logEnabled = false,
                requestedHdrMode = input
            )

            assertEquals("Input $input", expected, state.effectiveHdrMode)
            assertTrue("Input $input should use original NDR delivery", state.truthfulColorDelivery)
            assertFalse("Input $input should not use normal preview", state.neutralPreview)
            assertTrue("Input $input should require NDR metadata", state.requiresHdrContainerMetadata)
            assertEquals("Input $input should use NDR preview contract", VideoPipelinePreviewKind.HDR_10BIT, state.pipelineContract.previewKind)
        }
    }

    @Test
    fun `log delivery overrides stale hdr toggle while keeping truthful log preview`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoActive = false,
            logEnabled = true,
            requestedHdrMode = "HDR10"
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertTrue(state.truthfulColorDelivery)
        assertFalse(state.neutralPreview)
        assertTrue(state.logVideoActive)
        assertFalse(state.rawVideoActive)
        assertEquals(VideoPipelineDeliveryKind.LOG_10BIT, state.pipelineContract.deliveryKind)
        assertTrue(state.pipelineContract.logToneMapActive)
        assertFalse(state.pipelineContract.hdrToneMapActive)
        assertFalse(state.requiresHdrContainerMetadata)
    }

    @Test
    fun `raw mode suppresses hdr and log delivery while keeping truthful raw preview`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = true,
            requestedHdrMode = "HDR10+"
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertTrue(state.truthfulColorDelivery)
        assertFalse(state.neutralPreview)
        assertTrue(state.rawVideoActive)
        assertFalse(state.logVideoActive)
        assertEquals(VideoPipelineDeliveryKind.RAW_SENSOR, state.pipelineContract.deliveryKind)
        assertTrue(state.pipelineContract.rawSensorOriginal)
    }

    @Test
    fun `legacy vendor vision text is rejected instead of activating nivukx`() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "DolbyVision"
        )

        assertEquals("OFF", state.effectiveHdrMode)
        assertEquals("Rec.709", state.effectiveColorSpace)
        assertTrue(state.truthfulColorDelivery)
        assertTrue(state.tenBitActive)
        assertFalse(state.requiresHdrContainerMetadata)
        assertFalse(state.requiresNeoDynamicMetadataSidecar)
        assertFalse(state.requiresCertifiedVendorEnhancementEncoding)
    }

    @Test
    fun `original neo hdr delivery keeps pq transport and original color space identity`() {
        val ndr10Plus = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NDR10+"
        )
        val nivukx = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NIVUKX Vision"
        )

        assertEquals("NDR10+", ndr10Plus.effectiveHdrMode)
        assertEquals("Rec.2020-NDR10+", ndr10Plus.effectiveColorSpace)
        assertEquals("NIVUKXVISION", nivukx.effectiveHdrMode)
        assertEquals("Rec.2020-NIVUKX", nivukx.effectiveColorSpace)
        assertEquals(VideoPipelineDeliveryKind.HDR_PQ, ndr10Plus.pipelineContract.deliveryKind)
        assertEquals(VideoPipelineDeliveryKind.HDR_PQ, nivukx.pipelineContract.deliveryKind)
        assertTrue(ndr10Plus.requiresNeoDynamicMetadataSidecar)
        assertTrue(nivukx.requiresNeoDynamicMetadataSidecar)
    }

    @Test
    fun `apex delivery keeps selected apex mode identity and pq metadata contract`() {
        val sharp = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "APEX Sharp"
        )
        val zero = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "APEX Zero"
        )
        val cinema = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "APEX Cinema"
        )

        assertEquals("APEX_SHARP", sharp.effectiveHdrMode)
        assertEquals("APEX_ZERO", zero.effectiveHdrMode)
        assertEquals("APEX_CINEMA", cinema.effectiveHdrMode)
        assertEquals("Rec.2020-NIVUKX-APEX", sharp.effectiveColorSpace)
        assertEquals(VideoPipelineDeliveryKind.HDR_PQ, sharp.pipelineContract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.HDR_10BIT, sharp.pipelineContract.previewKind)
        assertEquals(com.neoproc.neoproccamx.camera.HdrDynamicRangeMode.SDR, sharp.pipelineContract.cameraDynamicRangeMode)
        assertTrue(sharp.requiresHdrContainerMetadata)
        assertTrue(sharp.requiresPqStaticMetadata)
        assertTrue(sharp.requiresNeoDynamicMetadataSidecar)
        assertFalse(sharp.requiresCertifiedVendorEnhancementEncoding)
    }
}
