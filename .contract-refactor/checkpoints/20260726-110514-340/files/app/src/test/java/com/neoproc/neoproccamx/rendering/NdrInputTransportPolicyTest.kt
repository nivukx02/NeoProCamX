package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrInputTransportPolicyTest {
    @Test
    fun `temporary missing preview output uses requested mode profile for egl handoff`() {
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            NdrInputTransportPolicy.effectivePreviewProfile(
                appliedPreviewProfile = null,
                resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS
            )
        )
    }

    @Test
    fun `explicit standard preview profile is never disguised as pq`() {
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_STANDARD,
            NdrInputTransportPolicy.effectivePreviewProfile(
                appliedPreviewProfile = HdrDynamicRangePolicy.PROFILE_STANDARD,
                resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS
            )
        )
    }


    @Test
    fun `buffer dataspace overrides a stale pq assumption`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_BT709,
            appliedProfile = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            preferPqWhenUnknown = true
        )

        assertEquals(NdrInputTransfer.SDR, decision.transfer)
        assertEquals(NdrInputPrimaries.BT709, decision.primaries)
        assertEquals(NdrInputRange.LIMITED, decision.range)
        assertTrue(decision.dataSpaceKnown)
        assertFalse(decision.isStrictNdrTransport)
    }

    @Test
    fun `pq and hlg dataspaces remain distinct`() {
        val pq = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ,
            appliedProfile = null,
            preferPqWhenUnknown = false
        )
        val hlg = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_HLG,
            appliedProfile = null,
            preferPqWhenUnknown = true
        )

        assertEquals(NdrInputTransfer.PQ, pq.transfer)
        assertEquals(NdrInputTransfer.HLG, hlg.transfer)
        assertEquals(NdrInputPrimaries.BT2020, pq.primaries)
        assertEquals(NdrInputPrimaries.BT2020, hlg.primaries)
        assertTrue(pq.isStrictNdrTransport)
        assertFalse(hlg.isStrictNdrTransport)
    }

    @Test
    fun `i2405 pq dataspace resolves bt2020 pq limited range`() {
        val decision = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ_LIMITED,
            appliedProfile = null,
            preferPqWhenUnknown = false
        )

        assertEquals(NdrInputTransfer.PQ, decision.transfer)
        assertEquals(NdrInputPrimaries.BT2020, decision.primaries)
        assertEquals(NdrInputRange.LIMITED, decision.range)
        assertTrue(decision.isStrictNdrTransport)
    }

    @Test
    fun `unknown dataspace follows the applied camera profile`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            appliedProfile = HdrDynamicRangePolicy.PROFILE_HDR10,
            preferPqWhenUnknown = false
        )

        assertEquals(NdrInputTransfer.PQ, decision.transfer)
        assertEquals(NdrInputRange.UNKNOWN, decision.range)
        assertFalse(decision.dataSpaceKnown)
        assertEquals("CAMERA2_PROFILE", decision.source)
    }

    @Test
    fun `only pq profiles are admitted for ndr compositor`() {
        assertTrue(NdrInputTransportPolicy.isPqProfile(HdrDynamicRangePolicy.PROFILE_HDR10))
        assertTrue(NdrInputTransportPolicy.isPqProfile(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS))
        assertFalse(NdrInputTransportPolicy.isPqProfile(HdrDynamicRangePolicy.PROFILE_HLG10))
        assertFalse(NdrInputTransportPolicy.isPqProfile(null))
    }
}
