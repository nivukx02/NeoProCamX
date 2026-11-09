package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdrSubjectMeteringFallbackPolicyTest {

    @Test
    fun `hdr without subject uses center weighted exposure and white balance fallback`() {
        val plan = HdrSubjectMeteringFallbackPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
            activeArray = CameraRect(0, 0, 4000, 3000),
            persistentExposureRegion = null,
            persistentAwbRegion = null,
            maxAeRegions = 3,
            maxAwbRegions = 2
        )

        assertEquals(CameraRect(1240, 870, 2760, 2130), plan.exposureRegion)
        assertEquals(CameraRect(1080, 720, 2920, 2280), plan.awbRegion)
    }

    @Test
    fun `subject exposure keeps hdr white balance fallback independent from tap exposure`() {
        val plan = HdrSubjectMeteringFallbackPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
            activeArray = CameraRect(0, 0, 4000, 3000),
            persistentExposureRegion = CameraRect(1800, 1300, 2200, 1700),
            persistentAwbRegion = null,
            maxAeRegions = 3,
            maxAwbRegions = 2
        )

        assertNull(plan.exposureRegion)
        assertEquals(CameraRect(1080, 720, 2920, 2280), plan.awbRegion)
    }

    @Test
    fun `awb only subject state still receives hdr exposure fallback`() {
        val plan = HdrSubjectMeteringFallbackPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
            activeArray = CameraRect(0, 0, 4000, 3000),
            persistentExposureRegion = null,
            persistentAwbRegion = CameraRect(1800, 1300, 2200, 1700),
            maxAeRegions = 3,
            maxAwbRegions = 2
        )

        assertEquals(CameraRect(1240, 870, 2760, 2130), plan.exposureRegion)
        assertNull(plan.awbRegion)
    }

    @Test
    fun `sdr does not apply hdr metering fallback`() {
        val plan = HdrSubjectMeteringFallbackPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            activeArray = CameraRect(0, 0, 4000, 3000),
            persistentExposureRegion = null,
            persistentAwbRegion = null,
            maxAeRegions = 3,
            maxAwbRegions = 2
        )

        assertNull(plan.exposureRegion)
        assertNull(plan.awbRegion)
    }

    @Test
    fun `unsupported hal region counts keep fallback disabled per control`() {
        val plan = HdrSubjectMeteringFallbackPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HLG,
            activeArray = CameraRect(0, 0, 4000, 3000),
            persistentExposureRegion = null,
            persistentAwbRegion = null,
            maxAeRegions = 0,
            maxAwbRegions = 0
        )

        assertNull(plan.exposureRegion)
        assertNull(plan.awbRegion)
    }
}
