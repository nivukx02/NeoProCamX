package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdrMasterCaptureQualityPolicyTest {
    @Test
    fun `inactive path never changes camera processing`() {
        assertNull(NdrMasterCaptureQualityPolicy.resolve(false, null))
    }

    @Test
    fun `master keeps high quality edge reconstruction with minimal denoise`() {
        val availability = availability(
            edge = listOf(CaptureRequest.EDGE_MODE_OFF, CaptureRequest.EDGE_MODE_HIGH_QUALITY),
            noise = listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            ),
            hot = listOf(CaptureRequest.HOT_PIXEL_MODE_FAST, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        )

        val plan = NdrMasterCaptureQualityPolicy.resolve(true, availability)!!

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, plan.hotPixelMode)
    }

    @Test
    fun `unsupported minimal denoise falls back without selecting destructive high quality first`() {
        val availability = availability(
            edge = listOf(CaptureRequest.EDGE_MODE_FAST),
            noise = listOf(
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            ),
            hot = listOf(CaptureRequest.HOT_PIXEL_MODE_FAST)
        )

        val plan = NdrMasterCaptureQualityPolicy.resolve(true, availability)!!

        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_FAST, plan.noiseReductionMode)
    }

    private fun availability(
        edge: List<Int>,
        noise: List<Int>,
        hot: List<Int>
    ) = IspBypassAvailability(
        edgeModes = edge,
        noiseReductionModes = noise,
        hotPixelModes = hot,
        shadingModes = emptyList(),
        tonemapModes = emptyList(),
        colorAberrationModes = emptyList(),
        distortionCorrectionModes = emptyList()
    )
}
