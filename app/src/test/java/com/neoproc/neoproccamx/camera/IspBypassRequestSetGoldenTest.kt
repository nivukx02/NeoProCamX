package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP17 — golden lock for the requested ISP-block mode set (Phase-0, no-downgrade discipline).
 *
 * The CP17 read-back verification is observe-only: it must NOT change which modes are requested.
 * This golden freezes today's requested ISP-block modes for the two strict sensor-native bypass
 * profiles ([HardwareAbstractionLayer.BEYOND_IMAX_RAW], [HardwareAbstractionLayer.SUPREME_FIDELITY_BYPASS])
 * and two processed profiles ([HardwareAbstractionLayer.CONSUMER_AUTO_VIDEO],
 * [HardwareAbstractionLayer.BEYOND_IMAX_CINEMA]), plus the [ISPBypassRequestPlanPolicy] full-bypass
 * output, so any drift in request behaviour trips CI before it can reach pixels.
 *
 * Distortion/aberration modes are API-gated (P / M); on the plain-JVM unit runner `Build.VERSION.SDK_INT`
 * is below those levels, so the per-profile fields resolve to null — asserted via the same SDK branch
 * the sibling [HardwareAbstractionLayerDistortionTest] uses, while the API-explicit
 * `strictBypassDistortionCorrectionMode(P)` pins the OFF geometry deterministically.
 *
 * Test-only: no production code is changed.
 */
class IspBypassRequestSetGoldenTest {

    // The non-API-gated ISP-block fields that must stay frozen for each profile.
    private data class ProfileGolden(
        val name: String,
        val profile: HardwareOverrideProfile,
        val edgeMode: Int,
        val noiseReductionMode: Int,
        val hotPixelMode: Int,
        val shadingMode: Int,
        val tonemapMode: Int,
        val applyLinearTonemapCurve: Boolean
    )

    private val bypassProfiles = listOf(
        ProfileGolden(
            name = "BEYOND_IMAX_RAW",
            profile = HardwareAbstractionLayer.BEYOND_IMAX_RAW,
            edgeMode = CaptureRequest.EDGE_MODE_OFF,
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_OFF,
            shadingMode = CaptureRequest.SHADING_MODE_OFF,
            tonemapMode = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
            applyLinearTonemapCurve = true
        ),
        ProfileGolden(
            name = "SUPREME_FIDELITY_BYPASS",
            profile = HardwareAbstractionLayer.SUPREME_FIDELITY_BYPASS,
            edgeMode = CaptureRequest.EDGE_MODE_OFF,
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_OFF,
            shadingMode = CaptureRequest.SHADING_MODE_OFF,
            tonemapMode = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
            applyLinearTonemapCurve = true
        )
    )

    private val processedProfiles = listOf(
        ProfileGolden(
            name = "CONSUMER_AUTO_VIDEO",
            profile = HardwareAbstractionLayer.CONSUMER_AUTO_VIDEO,
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            applyLinearTonemapCurve = false
        ),
        ProfileGolden(
            name = "BEYOND_IMAX_CINEMA",
            profile = HardwareAbstractionLayer.BEYOND_IMAX_CINEMA,
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            applyLinearTonemapCurve = false
        )
    )

    @Test
    fun `requested isp-block modes are frozen for every locked profile`() {
        (bypassProfiles + processedProfiles).forEach { g ->
            assertEquals("${g.name} edgeMode", g.edgeMode, g.profile.edgeMode)
            assertEquals("${g.name} noiseReductionMode", g.noiseReductionMode, g.profile.noiseReductionMode)
            assertEquals("${g.name} hotPixelMode", g.hotPixelMode, g.profile.hotPixelMode)
            assertEquals("${g.name} shadingMode", g.shadingMode, g.profile.shadingMode)
            assertEquals("${g.name} tonemapMode", g.tonemapMode, g.profile.tonemapMode)
            assertEquals("${g.name} applyLinearTonemapCurve", g.applyLinearTonemapCurve, g.profile.applyLinearTonemapCurve)
        }
    }

    @Test
    fun `strict bypass geometry intent is sensor-native off at api P`() {
        assertEquals(
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
            HardwareAbstractionLayer.strictBypassDistortionCorrectionMode(Build.VERSION_CODES.P)
        )
        assertNull(HardwareAbstractionLayer.strictBypassDistortionCorrectionMode(Build.VERSION_CODES.O))
        // Cross-check the geometry label the CP17 recorder will write for the bypass profiles.
        assertEquals(
            IspBypassGeometry.SENSOR_NATIVE,
            CameraIspBypassVerificationPolicy.geometryOf(
                HardwareAbstractionLayer.strictBypassDistortionCorrectionMode(Build.VERSION_CODES.P)
            )
        )
    }

    @Test
    fun `bypass profiles request distortion off and aberration off under the active sdk branch`() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bypassProfiles.forEach {
                assertEquals(
                    "${it.name} distortionCorrectionMode",
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                    it.profile.distortionCorrectionMode
                )
            }
        } else {
            bypassProfiles.forEach { assertNull("${it.name} distortionCorrectionMode", it.profile.distortionCorrectionMode) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bypassProfiles.forEach {
                assertEquals(
                    "${it.name} aberrationCorrectionMode",
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
                    it.profile.aberrationCorrectionMode
                )
            }
        } else {
            bypassProfiles.forEach { assertNull("${it.name} aberrationCorrectionMode", it.profile.aberrationCorrectionMode) }
        }
    }

    @Test
    fun `full bypass request plan locks every public block off with native geometry`() {
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
        assertNull(plan.userWarning)
        assertEquals(IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF, plan.distortionCorrectionPreference)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF, plan.distortionCorrectionMode)
        setOf(
            IspBypassFeature.TONEMAP_CONTRAST_CURVE,
            IspBypassFeature.EDGE_OFF,
            IspBypassFeature.NOISE_REDUCTION_OFF,
            IspBypassFeature.HOT_PIXEL_OFF,
            IspBypassFeature.SHADING_OFF,
            IspBypassFeature.COLOR_ABERRATION_OFF,
            IspBypassFeature.DISTORTION_CORRECTION_OFF
        ).forEach { feature ->
            assertTrue("plan supports $feature", plan.supports(feature))
        }
    }
}
