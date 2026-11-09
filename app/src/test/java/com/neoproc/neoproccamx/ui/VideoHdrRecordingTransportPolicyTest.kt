package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.camera.HdrDynamicRangeState
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoHdrRecordingTransportPolicyTest {

    @Test
    fun hdr10RequestRejectsHlgTransportForNdr() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "HDR10",
            requestedColorSpace = "Rec.2020-PQ",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HLG10
            )
        )

        assertEquals("OFF", transport.hdrMode)
        assertEquals("Rec.709", transport.colorSpace)
        assertEquals(null, transport.colorProfile)
    }

    @Test
    fun hdr10RequestRecordsAsNdrWhenEncoderProfileUsesCompatiblePqTransport() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "HDR10",
            requestedColorSpace = "Rec.2020-PQ",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HLG10,
                encoderProfile = HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )

        assertEquals("NDR10+", transport.hdrMode)
        assertEquals("Rec.2020-NDR10+", transport.colorSpace)
        assertEquals("NDR10+ Native", transport.colorProfile)
    }

    @Test
    fun hdrRequestFallsBackToSdrMetadataWhenCamera2TransportIsStandard() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "HDR10",
            requestedColorSpace = "Rec.2020-PQ",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = null
            )
        )

        assertEquals("OFF", transport.hdrMode)
        assertEquals("Rec.709", transport.colorSpace)
        assertEquals(null, transport.colorProfile)
    }

    @Test
    fun sdrAndLogRequestsKeepResolvedDeliveryColorSpace() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "OFF",
            requestedColorSpace = "Rec.2020-LOG",
            hdrState = hdrState(requestedMode = HdrDynamicRangeMode.SDR)
        )

        assertEquals("OFF", transport.hdrMode)
        assertEquals("Rec.2020-LOG", transport.colorSpace)
        assertEquals(null, transport.colorProfile)
    }

    @Test
    fun originalNeoHdrRequestsKeepOriginalIdentityWhenPqTransportIsActive() {
        val ndr10Plus = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "NDR10+",
            requestedColorSpace = "Rec.2020-NDR10+",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )
        val nivukx = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "NIVUKX Vision",
            requestedColorSpace = "Rec.2020-NIVUKX",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )

        assertEquals("NDR10+", ndr10Plus.hdrMode)
        assertEquals("Rec.2020-NDR10+", ndr10Plus.colorSpace)
        assertEquals("NDR10+ Native", ndr10Plus.colorProfile)
        assertEquals(true, ndr10Plus.proprietaryProfilePreserved)
        assertEquals(true, ndr10Plus.androidBt2100CarrierOnly)
        assertEquals(false, ndr10Plus.vendorDynamicRangeSubstitution)
        assertEquals(false, ndr10Plus.vendorDynamicMetadataAllowed)
        assertEquals("NIVUKXVISION", nivukx.hdrMode)
        assertEquals("Rec.2020-NIVUKX", nivukx.colorSpace)
        assertEquals("NIVUKX Vision", nivukx.colorProfile)
        assertEquals(true, nivukx.proprietaryProfilePreserved)
        assertEquals(true, nivukx.androidBt2100CarrierOnly)
        assertEquals(false, nivukx.vendorDynamicRangeSubstitution)
        assertEquals(false, nivukx.vendorDynamicMetadataAllowed)
    }

    @Test
    fun neoHdrRequestFallsBackWhenOnlyVendorDynamicRangeProfileIsObserved() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "NIVUKX Vision",
            requestedColorSpace = "Rec.2020-NIVUKX",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10_PLUS,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM,
                encoderProfile = HdrDynamicRangePolicy.PROFILE_DOLBY_VISION_10B_HDR_OEM
            )
        )

        assertEquals("OFF", transport.hdrMode)
        assertEquals("Rec.709", transport.colorSpace)
        assertEquals(null, transport.colorProfile)
    }

    @Test
    fun neoHdrRequestRejectsAndroidHdr10PlusDynamicCarrier() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "NDR10+",
            requestedColorSpace = "Rec.2020-NDR10+",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10_PLUS,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS
            )
        )

        assertEquals("OFF", transport.hdrMode)
        assertEquals("Rec.709", transport.colorSpace)
        assertEquals(null, transport.colorProfile)
        assertEquals(false, transport.vendorDynamicRangeSubstitution)
        assertEquals(false, transport.vendorDynamicMetadataAllowed)
    }

    @Test
    fun projectOwnedPqPreservesNivukxIdentityWhenCamera2TransportIsSdr() {
        val transport = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "NIVUKX Vision",
            requestedColorSpace = "Rec.2020-NIVUKX",
            hdrState = hdrState(requestedMode = HdrDynamicRangeMode.SDR),
            projectOwnedPqTransportActive = true
        )

        assertEquals("NIVUKXVISION", transport.hdrMode)
        assertEquals("Rec.2020-NIVUKX", transport.colorSpace)
        assertEquals("NIVUKX Vision", transport.colorProfile)
        assertEquals(true, transport.proprietaryProfilePreserved)
        assertEquals(true, transport.androidBt2100CarrierOnly)
        assertEquals(false, transport.vendorDynamicRangeSubstitution)
        assertEquals(false, transport.vendorDynamicMetadataAllowed)
    }

    @Test
    fun projectOwnedPqRequiresEveryContractProof() {
        val accepted = ProjectOwnedPqRecordingTransportPolicy.isActive(
            sensorLinearNdrActive = true,
            deliveryKind = VideoDeliveryKind.HDR,
            exactTenBitTransportRequested = true,
            encoderHdrMode = "NIVUKXVISION",
            encoderColorSpace = "Rec.2020-NIVUKX",
            activeHdrMode = "NIVUKX Vision",
            activeColorSpace = "Rec.2020-NIVUKX",
            feedPath = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        )
        assertEquals(true, accepted)

        val base = listOf(
            ProjectOwnedPqRecordingTransportPolicy.isActive(
                false, VideoDeliveryKind.HDR, true, "NIVUKXVISION", "Rec.2020-NIVUKX",
                "NIVUKXVISION", "Rec.2020-NIVUKX", RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ),
            ProjectOwnedPqRecordingTransportPolicy.isActive(
                true, VideoDeliveryKind.SDR, true, "NIVUKXVISION", "Rec.2020-NIVUKX",
                "NIVUKXVISION", "Rec.2020-NIVUKX", RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ),
            ProjectOwnedPqRecordingTransportPolicy.isActive(
                true, VideoDeliveryKind.HDR, false, "NIVUKXVISION", "Rec.2020-NIVUKX",
                "NIVUKXVISION", "Rec.2020-NIVUKX", RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ),
            ProjectOwnedPqRecordingTransportPolicy.isActive(
                true, VideoDeliveryKind.HDR, true, "NDR10+", "Rec.2020-NIVUKX",
                "NIVUKXVISION", "Rec.2020-NIVUKX", RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ),
            ProjectOwnedPqRecordingTransportPolicy.isActive(
                true, VideoDeliveryKind.HDR, true, "NIVUKXVISION", "Rec.709",
                "NIVUKXVISION", "Rec.2020-NIVUKX", RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ),
            ProjectOwnedPqRecordingTransportPolicy.isActive(
                true, VideoDeliveryKind.HDR, true, "NIVUKXVISION", "Rec.2020-NIVUKX",
                "NIVUKXVISION", "Rec.2020-NIVUKX", RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
            )
        )
        assertEquals(listOf(false, false, false, false, false, false), base)
    }

    @Test
    fun apexRequestsKeepApexIdentityWhenPqTransportIsActive() {
        val sharp = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "APEX Sharp",
            requestedColorSpace = "Rec.2020-NIVUKX-APEX",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )
        val zero = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "APEX Zero",
            requestedColorSpace = "Rec.2020-NIVUKX-APEX",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )
        val cinema = VideoHdrRecordingTransportPolicy.resolve(
            requestedHdrMode = "APEX Cinema",
            requestedColorSpace = "Rec.2020-NIVUKX-APEX",
            hdrState = hdrState(
                requestedMode = HdrDynamicRangeMode.HDR10,
                resolvedProfile = HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )

        assertEquals("APEX_SHARP", sharp.hdrMode)
        assertEquals("APEX_ZERO", zero.hdrMode)
        assertEquals("APEX_CINEMA", cinema.hdrMode)
        assertEquals("Rec.2020-NIVUKX-APEX", sharp.colorSpace)
        assertEquals("NIVUKX APEX SHARP", sharp.colorProfile)
        assertEquals("NIVUKX APEX ZERO", zero.colorProfile)
        assertEquals("NIVUKX APEX CINEMA", cinema.colorProfile)
    }

    private fun hdrState(
        requestedMode: HdrDynamicRangeMode,
        resolvedProfile: Long? = null,
        encoderProfile: Long? = null
    ): HdrDynamicRangeState =
        HdrDynamicRangeState(
            requestedMode = requestedMode,
            previewProfile = resolvedProfile,
            encoderProfile = encoderProfile,
            resolvedProfileForCurrentMode = resolvedProfile,
            support = HdrDynamicRangePolicy.Support(
                standardOnly = false,
                hlg10Supported = resolvedProfile == HdrDynamicRangePolicy.PROFILE_HLG10,
                hdr10Supported = resolvedProfile == HdrDynamicRangePolicy.PROFILE_HDR10 ||
                    encoderProfile == HdrDynamicRangePolicy.PROFILE_HDR10,
                hdr10PlusSupported = resolvedProfile == HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                certifiedVendorDynamicRangeProfile = null
            )
        )
}
