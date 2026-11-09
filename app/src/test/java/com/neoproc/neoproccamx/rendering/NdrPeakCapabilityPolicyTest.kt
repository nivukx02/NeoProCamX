package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrPeakCapabilityPolicyTest {
    private val ndr = NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
    private val nivukx = NdrNivukxDeliveryEngine.Mode.NIVUKX_VISION

    @Test
    fun unknownEncoderCapability_keepsEstablishedContentPeak() {
        val plan = NdrPeakCapabilityPolicy.resolve(
            mode = ndr,
            displayMaxLuminanceNits = 4_000f,
            requestedContentPeakNits = 4_000f,
            encoderPeakCapabilityNits = null
        )
        assertEquals(5_000f, plan.contentPeakNits, 0f)
        assertEquals(4_000f, plan.monitorPeakNits, 0f)
        assertFalse(plan.expandedContentPeak)
    }

    @Test
    fun encoderCapabilityHint_cannotRelabelNivukxMaster() {
        val plan = NdrPeakCapabilityPolicy.resolve(
            mode = nivukx,
            displayMaxLuminanceNits = 4_000f,
            requestedContentPeakNits = 6_000f,
            encoderPeakCapabilityNits = 4_000f
        )
        assertEquals(10_000f, plan.contentPeakNits, 0f)
        assertEquals(4_000f, plan.monitorPeakNits, 0f)
        assertFalse(plan.expandedContentPeak)
    }

    @Test
    fun monitorPeak_neverExceedsActualDisplayOrContentMaster() {
        val plan = NdrPeakCapabilityPolicy.resolve(
            mode = ndr,
            displayMaxLuminanceNits = 800f,
            requestedContentPeakNits = 4_000f,
            encoderPeakCapabilityNits = 4_000f
        )
        assertEquals(5_000f, plan.contentPeakNits, 0f)
        assertEquals(800f, plan.monitorPeakNits, 0f)
    }

    @Test
    fun lowOrInvalidRequests_cannotDowngradeModeBaseline() {
        val low = NdrPeakCapabilityPolicy.resolve(ndr, 800f, 800f, 4_000f)
        val invalid = NdrPeakCapabilityPolicy.resolve(nivukx, Float.NaN, Float.NaN, 4_000f)
        assertEquals(5_000f, low.contentPeakNits, 0f)
        assertEquals(10_000f, invalid.contentPeakNits, 0f)
        assertEquals(1_000f, invalid.monitorPeakNits, 0f)
    }
}
