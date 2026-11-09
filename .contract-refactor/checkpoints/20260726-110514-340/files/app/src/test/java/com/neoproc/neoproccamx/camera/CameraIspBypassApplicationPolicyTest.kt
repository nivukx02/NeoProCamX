package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraIspBypassApplicationPolicyTest {

    @Test
    fun `raw request is owned by centralized hal profile instead of secondary isp override`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = true,
            recordingActive = true,
            readoutSidecarActive = true,
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR
        )

        assertFalse(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
        assertTrue(decision.reason.contains("HardwareAbstractionLayer"))
    }

    @Test
    fun `hdr never applies isp bypass even if raw sidecar state is stale`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = true,
            recordingActive = true,
            readoutSidecarActive = true,
            hdrDynamicRangeMode = HdrDynamicRangeMode.HLG
        )

        assertFalse(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
    }

    @Test
    fun `hdr encoded recording keeps high quality isp path instead of total bypass`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = false,
            recordingActive = true,
            readoutSidecarActive = true,
            hdrDynamicRangeMode = HdrDynamicRangeMode.HLG
        )

        assertFalse(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
    }

    @Test
    fun `app owned gpu hdr input requests exposed low processing controls without changing transfer`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = false,
            recordingActive = true,
            readoutSidecarActive = true,
            hdrDynamicRangeMode = HdrDynamicRangeMode.HLG,
            appOwnedGpuProcessingEnabled = true
        )

        assertTrue(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
        assertTrue(decision.reason.contains("Vulkan"))
        assertTrue(decision.reason.contains("HDR"))
    }

    @Test
    fun `sdr encoded recording keeps high quality isp path instead of total bypass`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = false,
            recordingActive = true,
            readoutSidecarActive = false,
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR
        )

        assertFalse(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
    }

    @Test
    fun `app owned gpu sdr input requests exposed low processing controls without changing transfer`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = false,
            recordingActive = true,
            readoutSidecarActive = true,
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            appOwnedGpuProcessingEnabled = true
        )

        assertTrue(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
        assertTrue(decision.reason.contains("Vulkan"))
    }

    @Test
    fun `strict high end processed sdr blocks gpu isp off override`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = true,
            rawVideoCaptureEnabled = false,
            recordingActive = true,
            readoutSidecarActive = true,
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            appOwnedGpuProcessingEnabled = true,
            strictHighEndEnabled = true
        )

        assertFalse(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
        assertTrue(decision.reason.contains("strict high-end"))
        assertTrue(decision.reason.contains("ISP cleanup"))
    }

    @Test
    fun `gpu owned standard video input requests low processing controls without cinema override`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = false,
            rawVideoCaptureEnabled = false,
            recordingActive = true,
            readoutSidecarActive = false,
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            appOwnedGpuProcessingEnabled = true
        )

        assertTrue(decision.applyRequestKeys)
        assertFalse(decision.includeLinearTonemap)
        assertTrue(decision.reason.contains("Vulkan"))
    }

    @Test
    fun `ordinary preview without cinema fidelity does not apply bypass keys`() {
        val decision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = false,
            rawVideoCaptureEnabled = false,
            recordingActive = false,
            readoutSidecarActive = false,
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR
        )

        assertFalse(decision.applyRequestKeys)
    }
}
