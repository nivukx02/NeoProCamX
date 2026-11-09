package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllIntraRealtimeAdmissionPolicyTest {
    @Test
    fun `full sensor Main10 at 160 Mbps is rejected without reducing quality`() {
        val decision = AllIntraRealtimeAdmissionPolicy.resolve(
            requested = true,
            hardwareSizeAndRateProven = true,
            width = 3264,
            height = 2448,
            fps = 25,
            bitDepth = 10,
            configuredBitrateBps = 160_000_000
        )

        assertFalse(decision.admitted)
        assertEquals(
            AllIntraRealtimeAdmissionPolicy.REASON_INSUFFICIENT_REALTIME_BITRATE,
            decision.reason
        )
        assertEquals(1_048_723_200L, decision.minimumBitrateBps)
    }

    @Test
    fun `capability proven Main10 all-intra remains available at sustainable bitrate`() {
        val decision = AllIntraRealtimeAdmissionPolicy.resolve(
            requested = true,
            hardwareSizeAndRateProven = true,
            width = 3264,
            height = 2448,
            fps = 25,
            bitDepth = 10,
            configuredBitrateBps = 1_100_000_000
        )

        assertTrue(decision.admitted)
        assertEquals(AllIntraRealtimeAdmissionPolicy.REASON_ADMITTED, decision.reason)
    }

    @Test
    fun `codec declaration without throughput proof cannot activate all-intra`() {
        val decision = AllIntraRealtimeAdmissionPolicy.resolve(
            requested = true,
            hardwareSizeAndRateProven = false,
            width = 1920,
            height = 1080,
            fps = 25,
            bitDepth = 10,
            configuredBitrateBps = 1_000_000_000
        )

        assertFalse(decision.admitted)
        assertEquals(
            AllIntraRealtimeAdmissionPolicy.REASON_CAPABILITY_UNPROVEN,
            decision.reason
        )
    }
}
