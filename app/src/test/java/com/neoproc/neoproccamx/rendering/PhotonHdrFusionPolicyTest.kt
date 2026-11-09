package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonHdrFusionPolicyTest {

    private fun resolve(
        dualExposure: Boolean = true,
        dualGain: Boolean = false,
        longNs: Long = 20_000L,
        shortNs: Long = 10_000L,
        motion: Boolean = true,
        maxFlow: Float = 12f
    ) = PhotonHdrFusionPolicy.resolve(
        dualExposureSupported = dualExposure,
        dualGainSupported = dualGain,
        longExposureNs = longNs,
        shortExposureNs = shortNs,
        motionFieldAvailable = motion,
        maxFlow = maxFlow
    )

    @Test
    fun defaultRequestIsOnButUnsupportedHardwareFallsBack() {
        val plan = resolve(dualExposure = false, dualGain = false)

        assertTrue(PhotonHdrFusionPolicy.DEFAULT_REQUESTED)
        assertEquals(PhotonFusionMode.SINGLE_FALLBACK, plan.mode)
        assertFalse(plan.active)
        assertEquals("SINGLE_EXPOSURE_FALLBACK", plan.reason)
    }

    @Test
    fun explicitDisableBlocksFusionEvenWhenHardwareCouldSupportIt() {
        val plan = PhotonHdrFusionPolicy.resolve(
            dualExposureSupported = true,
            dualGainSupported = true,
            longExposureNs = 20_000L,
            shortExposureNs = 10_000L,
            motionFieldAvailable = true,
            maxFlow = 12f,
            requested = false
        )

        assertEquals(PhotonFusionMode.SINGLE_FALLBACK, plan.mode)
        assertFalse(plan.active)
        assertEquals("PHOTON_HDR_DISABLED", plan.reason)
    }

    @Test
    fun dualExposureComputesRadianceRatioAndMotionCompensates() {
        val plan = resolve(longNs = 20_000L, shortNs = 10_000L)
        assertEquals(PhotonFusionMode.DUAL_EXPOSURE, plan.mode)
        assertTrue(plan.active)
        assertEquals(1f, plan.params.longScale, 0.0f)
        assertEquals(2f, plan.params.shortScale, 0.0001f) // 20000 / 10000
        assertTrue(plan.params.motionCompEnabled)
        assertEquals("MOTION_COMPENSATED_FUSION", plan.reason)
    }

    @Test
    fun dualGainUsedWhenNoAlternatingExposure() {
        val plan = resolve(dualExposure = false, dualGain = true)
        assertEquals(PhotonFusionMode.DUAL_GAIN, plan.mode)
        assertTrue(plan.active)
    }

    @Test
    fun noFlowDegradesToStaticFusion() {
        val plan = resolve(motion = false)
        assertTrue(plan.active)
        assertFalse(plan.params.motionCompEnabled)
        assertEquals("STATIC_FUSION_NO_FLOW", plan.reason)
    }

    @Test
    fun exposureRatioIsBoundedAndPushConstantsAreWellFormed() {
        val extreme = resolve(longNs = 100_000_000L, shortNs = 10L)
        assertEquals(64f, extreme.params.shortScale, 0.0f) // clamped to MAX_EXPOSURE_RATIO
        val packed = extreme.params.toPushConstants()
        assertEquals(6, packed.size)
        assertEquals(extreme.params.highlightThreshold, packed[0], 0.0f)
        assertEquals(extreme.params.shortScale, packed[3], 0.0f)
        assertEquals(1f, packed[4], 0.0f) // motionCompEnabled
        // Degenerate exposure metadata never produces a NaN/zero ratio.
        assertEquals(1f, resolve(longNs = 0L, shortNs = 0L).params.shortScale, 0.0f)
    }
}
