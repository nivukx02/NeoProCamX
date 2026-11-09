package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoRadianceDeliveryPolicyTest {

    @Test
    fun heliosDeliveryPrefersHdr10PqWithStaticMetadataWhenHdr10IsAvailable() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NDR10+",
            hardwareCapabilities = VideoModeHardwareCapabilities(
                supportedDynamicRangeProfiles = setOf(
                    VideoDynamicRangeProfile.SDR,
                    VideoDynamicRangeProfile.HDR10
                )
            )
        )

        assertEquals(NeoRadianceDeliveryTarget.HDR10_PQ, state.neoRadianceDeliveryPlan.target)
        assertEquals(5000, state.neoRadianceDeliveryPlan.maxCllNits)
        assertEquals(1250, state.neoRadianceDeliveryPlan.maxFallNits)
        assertTrue(state.neoRadianceDeliveryPlan.requiresStaticMetadata)
        assertFalse(state.neoRadianceDeliveryPlan.usesBt2390SdrDownmap)
    }

    @Test
    fun heliosDeliveryUsesSensorLinearPqWhenOnlyHlgCameraIngressIsAdvertised() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NDR10+",
            hardwareCapabilities = VideoModeHardwareCapabilities(
                supportedDynamicRangeProfiles = setOf(
                    VideoDynamicRangeProfile.SDR,
                    VideoDynamicRangeProfile.HLG10
                )
            )
        )

        assertEquals(NeoRadianceDeliveryTarget.HDR10_PQ, state.neoRadianceDeliveryPlan.target)
        assertEquals("NDR10+", state.effectiveHdrMode)
        assertEquals(
            com.neoproc.neoproccamx.camera.HdrDynamicRangeMode.SDR,
            state.pipelineContract.cameraDynamicRangeMode
        )
        assertTrue(state.neoRadianceDeliveryPlan.requiresStaticMetadata)
        assertFalse(state.neoRadianceDeliveryPlan.usesBt2390SdrDownmap)
    }

    @Test
    fun heliosDeliveryFallsBackToSdrBt2390WhenSensorLinearPqEgressIsUnavailable() {
        val state = VideoFormatDeliveryPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NDR10+",
            hardwareCapabilities = VideoModeHardwareCapabilities(
                tenBitSupported = false,
                tenBitEncoderSupported = false,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals(NeoRadianceDeliveryTarget.SDR_REC709, state.neoRadianceDeliveryPlan.target)
        assertEquals("OFF", state.effectiveHdrMode)
        assertEquals("Rec.709", state.neoRadianceDeliveryPlan.colorStandard)
        assertTrue(state.neoRadianceDeliveryPlan.usesBt2390SdrDownmap)
        assertFalse(state.neoRadianceDeliveryPlan.requiresStaticMetadata)
    }
}
