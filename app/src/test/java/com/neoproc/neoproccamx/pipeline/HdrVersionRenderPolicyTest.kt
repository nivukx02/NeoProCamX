package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrVersionRenderPolicyTest {

    @Test
    fun resolve_returnsNeutralBt2100ProfileForEveryHdrVersion() {
        val hlg = HdrVersionRenderPolicy.resolve("HLG", hdrEnabled = true, recording = true)
        val hdr10 = HdrVersionRenderPolicy.resolve("HDR10", hdrEnabled = true, recording = true)
        val hdr10Plus = HdrVersionRenderPolicy.resolve("HDR10+", hdrEnabled = true, recording = true)
        val vendorText = HdrVersionRenderPolicy.resolve("DOLBYVISION", hdrEnabled = true, recording = true)
        val ndr10Plus = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = true)
        val nivukx = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = true)

        assertEquals("NDR10+", hlg?.versionLabel)
        assertEquals("NDR10+", hdr10?.versionLabel)
        assertEquals("NDR10+", hdr10Plus?.versionLabel)
        assertEquals(null, vendorText)
        assertEquals("NDR10+", ndr10Plus?.versionLabel)
        assertEquals("NIVUKX Vision", nivukx?.versionLabel)
        listOfNotNull(hlg, hdr10, hdr10Plus, ndr10Plus, nivukx).forEach { profile ->
            assertTrue(profile.contrastMin <= 1.0f)
            assertTrue(profile.contrastMax <= 1.05f)
            assertTrue(profile.saturationMax <= 1.05f)
            assertTrue(profile.shadowBoostMax <= 0.60f)
            assertTrue(profile.highlightCompressionFloor >= 0.22f)
        }
    }

    @Test
    fun resolve_ordersFusionAndHighlightProtectionByHdrVersion() {
        val hlg = HdrVersionRenderPolicy.resolve("HLG", hdrEnabled = true, recording = false)!!
        val hdr10 = HdrVersionRenderPolicy.resolve("HDR10", hdrEnabled = true, recording = false)!!
        val hdr10Plus = HdrVersionRenderPolicy.resolve("HDR10+", hdrEnabled = true, recording = false)!!
        val vendorText = HdrVersionRenderPolicy.resolve("DOLBYVISION", hdrEnabled = true, recording = false)
        val ndr10Plus = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = false)!!
        val nivukx = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = false)!!

        assertEquals(ndr10Plus.fusionFloor, hlg.fusionFloor, 0.0001f)
        assertEquals(ndr10Plus.fusionFloor, hdr10.fusionFloor, 0.0001f)
        assertEquals(ndr10Plus.fusionFloor, hdr10Plus.fusionFloor, 0.0001f)
        assertEquals(null, vendorText)
        assertTrue(ndr10Plus.fusionFloor <= nivukx.fusionFloor)
        assertEquals(ndr10Plus.highlightCompressionFloor, hlg.highlightCompressionFloor, 0.0001f)
        assertEquals(ndr10Plus.highlightCompressionFloor, hdr10.highlightCompressionFloor, 0.0001f)
        assertEquals(ndr10Plus.highlightCompressionFloor, hdr10Plus.highlightCompressionFloor, 0.0001f)
        assertTrue(ndr10Plus.highlightCompressionFloor <= nivukx.highlightCompressionFloor)
        assertTrue(nivukx.saturationMax <= hdr10.saturationMax)
    }

    @Test
    fun resolve_capsNdrAndNivukxSaturationForSkinToneSafeHdr() {
        val ndr10Plus = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = true)!!
        val nivukx = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = true)!!

        assertTrue("ndr saturationMax=${ndr10Plus.saturationMax}", ndr10Plus.saturationMax <= 0.94f)
        assertTrue("nivukx saturationMax=${nivukx.saturationMax}", nivukx.saturationMax <= 0.93f)
        assertTrue("ndr chroma=${ndr10Plus.chromaDenoiseFloor}", ndr10Plus.chromaDenoiseFloor >= 0.74f)
        assertTrue("nivukx chroma=${nivukx.chromaDenoiseFloor}", nivukx.chromaDenoiseFloor >= 0.78f)
    }

    @Test
    fun resolve_givesNdr10PlusAnExplicitEliteDetailContractForPreviewAndRecording() {
        val preview = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = false)!!
        val recording = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = true)!!

        assertTrue("preview detail floor=${preview.detailSharpeningFloor}", preview.detailSharpeningFloor >= 0.30f)
        assertTrue("recording detail floor=${recording.detailSharpeningFloor}", recording.detailSharpeningFloor >= 0.34f)
        assertTrue("recording detail ceiling=${recording.detailSharpeningCeiling}", recording.detailSharpeningCeiling >= 0.54f)
        assertTrue(
            "recording should carry at least preview-grade detail",
            recording.detailSharpeningFloor >= preview.detailSharpeningFloor
        )
        assertTrue("micro contrast=${recording.microContrastDetailFloor}", recording.microContrastDetailFloor >= 0.70f)
        assertTrue("texture guard=${recording.texturePreservationFloor}", recording.texturePreservationFloor >= 0.90f)
    }

    @Test
    fun resolve_givesNivukxVisionAStrongerEliteDetailContractThanNdr10Plus() {
        val ndrRecording = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = true)!!
        val preview = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = false)!!
        val recording = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = true)!!

        assertTrue("preview detail floor=${preview.detailSharpeningFloor}", preview.detailSharpeningFloor >= 0.40f)
        assertTrue("recording detail floor=${recording.detailSharpeningFloor}", recording.detailSharpeningFloor >= 0.44f)
        assertTrue("recording detail ceiling=${recording.detailSharpeningCeiling}", recording.detailSharpeningCeiling >= 0.66f)
        assertTrue(
            "NIVUKX recording detail should exceed NDR10+ recording detail",
            recording.detailSharpeningFloor > ndrRecording.detailSharpeningFloor
        )
        assertTrue("micro contrast=${recording.microContrastDetailFloor}", recording.microContrastDetailFloor >= 0.82f)
        assertTrue("texture guard=${recording.texturePreservationFloor}", recording.texturePreservationFloor >= 0.96f)
    }

    @Test
    fun resolve_givesApexProfilesDedicatedDetailContractsAboveNivukxVision() {
        val nivukxRecording = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = true)!!
        val sharp = HdrVersionRenderPolicy.resolve("APEX Sharp", hdrEnabled = true, recording = true)!!
        val zero = HdrVersionRenderPolicy.resolve("APEX Zero", hdrEnabled = true, recording = true)!!
        val cinema = HdrVersionRenderPolicy.resolve("APEX Cinema", hdrEnabled = true, recording = true)!!

        assertEquals("NIVUKX APEX SHARP", sharp.versionLabel)
        assertEquals("NIVUKX APEX ZERO", zero.versionLabel)
        assertEquals("NIVUKX APEX CINEMA", cinema.versionLabel)
        assertTrue(sharp.detailSharpeningFloor > nivukxRecording.detailSharpeningFloor)
        assertTrue(zero.chromaDenoiseFloor >= nivukxRecording.chromaDenoiseFloor)
        assertTrue(cinema.microContrastDetailFloor >= nivukxRecording.microContrastDetailFloor)
        assertTrue(sharp.texturePreservationFloor >= 0.99f)
    }

    @Test
    fun scaleForFocus_scalesSharpeningAndMicroContrastAccordingly() {
        val baseProfile = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = true)!!
        
        // High focus (V = 0.95 >= 0.75)
        val highFocusProfile = baseProfile.scaleForFocus(0.95f)
        assertTrue(
            "High focus should elevate sharpening floor",
            highFocusProfile.detailSharpeningFloor > baseProfile.detailSharpeningFloor
        )
        assertTrue(
            "High focus should elevate sharpening ceiling",
            highFocusProfile.detailSharpeningCeiling > baseProfile.detailSharpeningCeiling
        )
        assertTrue(
            "High focus should elevate microcontrast floor",
            highFocusProfile.microContrastDetailFloor > baseProfile.microContrastDetailFloor
        )

        // Background/Low focus (V = 0.20 < 0.35)
        val lowFocusProfile = baseProfile.scaleForFocus(0.20f)
        assertTrue(
            "Low focus should throttle sharpening floor",
            lowFocusProfile.detailSharpeningFloor < baseProfile.detailSharpeningFloor
        )
        assertTrue(
            "Low focus should throttle sharpening ceiling",
            lowFocusProfile.detailSharpeningCeiling < baseProfile.detailSharpeningCeiling
        )
        assertTrue(
            "Low focus should throttle microcontrast floor",
            lowFocusProfile.microContrastDetailFloor < baseProfile.microContrastDetailFloor
        )

        // Neutral focus (V = 0.50)
        val neutralProfile = baseProfile.scaleForFocus(0.50f)
        assertEquals(baseProfile.detailSharpeningFloor, neutralProfile.detailSharpeningFloor, 0.0001f)
        assertEquals(baseProfile.detailSharpeningCeiling, neutralProfile.detailSharpeningCeiling, 0.0001f)
        assertEquals(baseProfile.microContrastDetailFloor, neutralProfile.microContrastDetailFloor, 0.0001f)
    }
}

