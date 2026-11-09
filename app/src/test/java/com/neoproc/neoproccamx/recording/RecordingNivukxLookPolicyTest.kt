package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNivukxLookPolicyTest {

    @Test
    fun ndr10PlusPrefersTwelveBitWhenEncoderCapabilityIsProven() {
        val plan = NivukxVersionRenderPolicy.resolve(
            hdrMode = "NDR10+",
            codec = GpuVideoCodec.HEVC,
            sourceBitDepth = 12,
            storageBitDepth = 12,
            tenBitTransport = true,
            hevcMain12Supported = true,
            av1Main12Supported = false
        )

        assertTrue(plan.active)
        assertEquals("NDR10+", plan.sourceProfile)
        assertEquals("NIVUKX-DR_ELITE", plan.productProfile)
        assertEquals("HEVC_MAIN12_BT2020_PQ", plan.transport)
        assertEquals(12, plan.effectiveBitDepth)
        assertEquals("NONE", plan.fallbackReason)
        assertFalse(plan.vendorCertified)
    }

    @Test
    fun ndr10PlusFallsBackTruthfullyToMain10LiteWhenMain12IsNotProven() {
        val plan = NivukxVersionRenderPolicy.resolve(
            hdrMode = "NDR10+",
            codec = GpuVideoCodec.HEVC,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            tenBitTransport = true,
            hevcMain12Supported = false,
            av1Main12Supported = false
        )

        assertTrue(plan.active)
        assertEquals("HEVC_MAIN10_LITE_FALLBACK", plan.transport)
        assertEquals(10, plan.effectiveBitDepth)
        assertEquals("MAIN12_OR_AV1_12_UNAVAILABLE", plan.fallbackReason)
        assertFalse(plan.vendorCertified)
    }

    @Test
    fun recordingLookPolicyAddsNivukxDrEliteMetadataForNdr10PlusRequests() {
        val metadata = RecordingNivukxLookPolicy.resolve(
            request = EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.HDR,
                width = 3840,
                height = 2160,
                fps = 60,
                codec = GpuVideoCodec.HEVC,
                hdrMode = "NDR10+",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true,
                colorSpace = "Rec.2020"
            ),
            normalizedHdrVersion = "NDR10+"
        ).asMetadata()

        assertEquals("true", metadata.getValue("nivukxDrEliteActive"))
        assertEquals("NDR10+", metadata.getValue("nivukxDrSourceProfile"))
        assertEquals("NIVUKX-DR_ELITE", metadata.getValue("nivukxDrProductProfile"))
        assertEquals("ACES_AP1_FP16_SCENE_LINEAR", metadata.getValue("nivukxDrWorkingGamut"))
        assertEquals("JZAZBZ_FP32", metadata.getValue("nivukxDrAppearanceModel"))
        assertEquals("HEVC_MAIN10_LITE_FALLBACK", metadata.getValue("nivukxDrTransport"))
        assertEquals("MAIN12_OR_AV1_12_UNAVAILABLE", metadata.getValue("nivukxDrFallbackReason"))
        assertEquals("false", metadata.getValue("nivukxDrVendorCertified"))
        assertEquals("false", metadata.getValue("nivukxDrVendorRpuWritten"))
    }

    @Test
    fun eliteQualityCertificateIncludesNivukxDrMetadata() {
        val certificate = EliteVideoQualityContract.resolve(
            EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.HDR,
                width = 3840,
                height = 2160,
                fps = 60,
                codec = GpuVideoCodec.HEVC,
                hdrMode = "NDR10+",
                sourceBitDepth = 10,
                storageBitDepth = 10,
                tenBitTransport = true,
                colorSpace = "Rec.2020"
            )
        )
        val metadata = certificate.toMetadata()

        assertEquals("true", metadata.getValue("nivukxDrEliteActive"))
        assertEquals("NDR10+", metadata.getValue("nivukxDrSourceProfile"))
        assertEquals("false", metadata.getValue("nivukxDrVendorCertified"))
    }
}
