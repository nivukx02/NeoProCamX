package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Test

class NivukxVisionRenderPolicyTest {
    @Test
    fun eightBitFallbackReportsActualEightBitDepthInsteadOfCoercingToTen() {
        val plan = NivukxVisionRenderPolicy.resolve(
            NivukxVisionRenderRequest(
                active = true,
                requestedBitDepth = 12,
                sourceBitDepth = 8,
                storageBitDepth = 8,
                codec = "HEVC",
                classProtectionAvailable = true,
                metadataSigningAvailable = true,
                nlogs12Requested = true
            )
        )

        assertEquals(NivukxVisionConformance.LITE, plan.conformance)
        assertEquals(8, plan.honoredBitDepth)
        assertEquals(NivukxVisionPrimaryDelivery.PQ, plan.primaryDelivery)
        assertEquals("MAIN10_OR_MAIN12_UNAVAILABLE", plan.fallbackReason)
    }

    @Test
    fun main10FallbackReportsTenBitWhenTwelveBitVisionIsUnavailable() {
        val plan = NivukxVisionRenderPolicy.resolve(
            NivukxVisionRenderRequest(
                active = true,
                requestedBitDepth = 12,
                sourceBitDepth = 10,
                storageBitDepth = 10,
                codec = "HEVC",
                classProtectionAvailable = true,
                metadataSigningAvailable = true,
                nlogs12Requested = true
            )
        )

        assertEquals(NivukxVisionConformance.LITE, plan.conformance)
        assertEquals(10, plan.honoredBitDepth)
        assertEquals(NivukxVisionPrimaryDelivery.PQ, plan.primaryDelivery)
        assertEquals("MAIN12_OR_AV1_12_UNAVAILABLE", plan.fallbackReason)
    }
}
