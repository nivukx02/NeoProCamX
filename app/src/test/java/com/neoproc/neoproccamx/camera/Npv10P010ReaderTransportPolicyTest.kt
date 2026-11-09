package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10P010ReaderTransportPolicyTest {
    @Test
    fun sdrAlwaysRequestsExplicitLimitedRangeBt709WithoutHdrProfile() {
        val plan = Npv10P010ReaderTransportPolicy.resolve(
            mode = HdrDynamicRangeMode.SDR,
            support = HdrDynamicRangePolicy.Support.NONE
        )

        assertTrue(plan.authoritative)
        assertEquals(Npv10P010ReaderTransportPolicy.DATA_SPACE_BT709_LIMITED, plan.dataSpace)
        assertNull(plan.dynamicRangeProfile)
    }

    @Test
    fun supportedHlgRequestsLimitedRangeBt2020Hlg() {
        val plan = Npv10P010ReaderTransportPolicy.resolve(
            mode = HdrDynamicRangeMode.HLG,
            support = support(hlg = true)
        )

        assertTrue(plan.authoritative)
        assertEquals(Npv10P010ReaderTransportPolicy.DATA_SPACE_BT2020_HLG_LIMITED, plan.dataSpace)
        assertEquals(HdrDynamicRangePolicy.PROFILE_HLG10, plan.dynamicRangeProfile)
    }

    @Test
    fun supportedHdr10AndHdr10PlusRequestLimitedRangeBt2020Pq() {
        val hdr10 = Npv10P010ReaderTransportPolicy.resolve(
            mode = HdrDynamicRangeMode.HDR10,
            support = support(hdr10 = true)
        )
        val hdr10Plus = Npv10P010ReaderTransportPolicy.resolve(
            mode = HdrDynamicRangeMode.HDR10_PLUS,
            support = support(hdr10 = true, hdr10Plus = true)
        )
        val staticFallback = Npv10P010ReaderTransportPolicy.resolve(
            mode = HdrDynamicRangeMode.HDR10_PLUS,
            support = support(hdr10 = true)
        )

        listOf(hdr10, hdr10Plus, staticFallback).forEach { plan ->
            assertTrue(plan.authoritative)
            assertEquals(Npv10P010ReaderTransportPolicy.DATA_SPACE_BT2020_PQ_LIMITED, plan.dataSpace)
        }
        assertEquals(HdrDynamicRangePolicy.PROFILE_HDR10, hdr10.dynamicRangeProfile)
        assertEquals(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS, hdr10Plus.dynamicRangeProfile)
        assertEquals(HdrDynamicRangePolicy.PROFILE_HDR10, staticFallback.dynamicRangeProfile)
    }

    @Test
    fun unsupportedHdrRemainsUnknownAndCannotBeRelabelledAsSdr() {
        listOf(
            HdrDynamicRangeMode.HLG,
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangeMode.HDR10_PLUS,
            HdrDynamicRangeMode.DOLBY_VISION
        ).forEach { mode ->
            val plan = Npv10P010ReaderTransportPolicy.resolve(mode, HdrDynamicRangePolicy.Support.NONE)
            assertFalse(plan.authoritative)
            assertEquals(Npv10P010ReaderTransportPlan.DATA_SPACE_UNKNOWN, plan.dataSpace)
            assertNull(plan.dynamicRangeProfile)
        }
    }

    private fun support(
        hlg: Boolean = false,
        hdr10: Boolean = false,
        hdr10Plus: Boolean = false
    ) = HdrDynamicRangePolicy.Support(
        standardOnly = !hlg && !hdr10 && !hdr10Plus,
        hlg10Supported = hlg,
        hdr10Supported = hdr10,
        hdr10PlusSupported = hdr10Plus
    )
}
