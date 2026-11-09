package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ISPBypassRequestPlanPolicyTest {

    @Test
    fun `complete support applies every isp off key and tonemap curve`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            )
        )

        assertTrue(plan.complete)
        assertEquals(7, plan.supportedFeatureCount)
        assertEquals(7, plan.totalFeatureCount)
        assertEquals(null, plan.userWarning)
        assertTrue(plan.supportedFeatures.contains(IspBypassFeature.TONEMAP_CONTRAST_CURVE))
        assertTrue(plan.supportedFeatures.contains(IspBypassFeature.NOISE_REDUCTION_OFF))
        assertTrue(plan.supportedFeatures.contains(IspBypassFeature.DISTORTION_CORRECTION_OFF))
        assertEquals(IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF, plan.distortionCorrectionPreference)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF, plan.distortionCorrectionMode)
        assertEquals(7, plan.supportedFeatures.size)
        assertTrue(plan.unsupportedReasons.isEmpty())
    }

    @Test
    fun `partial support never claims complete bypass`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            )
        )

        assertFalse(plan.complete)
        assertEquals(6, plan.supportedFeatureCount)
        assertEquals(7, plan.totalFeatureCount)
        assertEquals(
            "Requested ISP processing disablement unavailable: NOISE_REDUCTION_MODE_OFF unavailable.",
            plan.userWarning
        )
        assertFalse(plan.supportedFeatures.contains(IspBypassFeature.NOISE_REDUCTION_OFF))
        assertTrue(plan.unsupportedReasons.any { it.contains("NOISE_REDUCTION_MODE_OFF") })
    }

    @Test
    fun `strict full bypass requires distortion correction off by default`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = listOf(
                    CaptureRequest.DISTORTION_CORRECTION_MODE_FAST,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                )
            )
        )

        assertFalse(plan.complete)
        assertEquals(6, plan.supportedFeatureCount)
        assertEquals(7, plan.totalFeatureCount)
        assertEquals(IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF, plan.distortionCorrectionPreference)
        assertEquals(null, plan.distortionCorrectionMode)
        assertFalse(plan.supportedFeatures.contains(IspBypassFeature.DISTORTION_CORRECTION_ON))
        assertFalse(plan.supportedFeatures.contains(IspBypassFeature.DISTORTION_CORRECTION_OFF))
        assertTrue(plan.unsupportedReasons.any { it.contains("DISTORTION_CORRECTION_MODE_OFF") })
    }

    @Test
    fun `corrected bypass accepts distortion correction on when off is unavailable`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            availability = IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = listOf(
                    CaptureRequest.DISTORTION_CORRECTION_MODE_FAST,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                )
            ),
            distortionCorrectionPreference = IspBypassDistortionCorrectionPreference.CORRECTED_ON
        )

        assertTrue(plan.complete)
        assertEquals(7, plan.supportedFeatureCount)
        assertEquals(7, plan.totalFeatureCount)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, plan.distortionCorrectionMode)
        assertTrue(plan.supportedFeatures.contains(IspBypassFeature.DISTORTION_CORRECTION_ON))
        assertFalse(plan.supportedFeatures.contains(IspBypassFeature.DISTORTION_CORRECTION_OFF))
        assertTrue(plan.unsupportedReasons.isEmpty())
        assertEquals(null, plan.userWarning)
    }

    @Test
    fun `missing tonemap curve still allows supported processing disables`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_FAST),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = emptyList(),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = emptyList(),
                distortionCorrectionModes = emptyList()
            )
        )

        assertFalse(plan.complete)
        assertFalse(plan.supportedFeatures.contains(IspBypassFeature.TONEMAP_CONTRAST_CURVE))
        assertTrue(plan.supportedFeatures.contains(IspBypassFeature.EDGE_OFF))
        assertTrue(plan.supportedFeatures.contains(IspBypassFeature.NOISE_REDUCTION_OFF))
    }
}
