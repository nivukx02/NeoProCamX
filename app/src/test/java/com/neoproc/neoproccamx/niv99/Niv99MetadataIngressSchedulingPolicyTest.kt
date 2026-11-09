package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Test

class Niv99MetadataIngressSchedulingPolicyTest {
    @Test
    fun `recording critical tier requires both active recording and NPV10`() {
        assertEquals(
            Niv99MetadataIngressSchedulingPolicy.Tier.RECORDING_CRITICAL,
            Niv99MetadataIngressSchedulingPolicy.resolve(
                recordingActive = true,
                npv10TenBitCaptureEnabled = true
            )
        )
    }

    @Test
    fun `NPV10 preview prearm retains baseline tier`() {
        assertEquals(
            Niv99MetadataIngressSchedulingPolicy.Tier.BASELINE,
            Niv99MetadataIngressSchedulingPolicy.resolve(
                recordingActive = false,
                npv10TenBitCaptureEnabled = true
            )
        )
    }

    @Test
    fun `non NPV10 recording retains baseline tier`() {
        assertEquals(
            Niv99MetadataIngressSchedulingPolicy.Tier.BASELINE,
            Niv99MetadataIngressSchedulingPolicy.resolve(
                recordingActive = true,
                npv10TenBitCaptureEnabled = false
            )
        )
    }

    @Test
    fun `stopping NPV10 recording restores baseline tier`() {
        val active = Niv99MetadataIngressSchedulingPolicy.resolve(
            recordingActive = true,
            npv10TenBitCaptureEnabled = true
        )
        val stopped = Niv99MetadataIngressSchedulingPolicy.resolve(
            recordingActive = false,
            npv10TenBitCaptureEnabled = true
        )

        assertEquals(Niv99MetadataIngressSchedulingPolicy.Tier.RECORDING_CRITICAL, active)
        assertEquals(Niv99MetadataIngressSchedulingPolicy.Tier.BASELINE, stopped)
    }

    @Test
    fun `critical tier accepts an OEM boost stronger than requested`() {
        assertEquals(
            true,
            Niv99MetadataIngressSchedulingPolicy.isApplied(
                tier = Niv99MetadataIngressSchedulingPolicy.Tier.RECORDING_CRITICAL,
                requestedNice = -8,
                effectiveNice = -10
            )
        )
    }

    @Test
    fun `baseline restoration requires the exact requested priority`() {
        assertEquals(
            false,
            Niv99MetadataIngressSchedulingPolicy.isApplied(
                tier = Niv99MetadataIngressSchedulingPolicy.Tier.BASELINE,
                requestedNice = -4,
                effectiveNice = -8
            )
        )
        assertEquals(
            true,
            Niv99MetadataIngressSchedulingPolicy.isApplied(
                tier = Niv99MetadataIngressSchedulingPolicy.Tier.BASELINE,
                requestedNice = -4,
                effectiveNice = -4
            )
        )
    }

    @Test
    fun `P010 image ingress is critical only during active NPV10 recording`() {
        assertEquals(
            Niv99ImageIngressSchedulingPolicy.Tier.RECORDING_CRITICAL,
            Niv99ImageIngressSchedulingPolicy.resolve(
                recordingActive = true,
                npv10TenBitCaptureEnabled = true
            )
        )
        assertEquals(
            Niv99ImageIngressSchedulingPolicy.Tier.BASELINE,
            Niv99ImageIngressSchedulingPolicy.resolve(
                recordingActive = false,
                npv10TenBitCaptureEnabled = true
            )
        )
        assertEquals(
            Niv99ImageIngressSchedulingPolicy.Tier.BASELINE,
            Niv99ImageIngressSchedulingPolicy.resolve(
                recordingActive = true,
                npv10TenBitCaptureEnabled = false
            )
        )
    }

    @Test
    fun `P010 image ingress baseline is restored exactly after recording`() {
        assertEquals(
            true,
            Niv99ImageIngressSchedulingPolicy.isApplied(
                tier = Niv99ImageIngressSchedulingPolicy.Tier.RECORDING_CRITICAL,
                requestedNice = -8,
                effectiveNice = -10
            )
        )
        assertEquals(
            false,
            Niv99ImageIngressSchedulingPolicy.isApplied(
                tier = Niv99ImageIngressSchedulingPolicy.Tier.BASELINE,
                requestedNice = -1,
                effectiveNice = -8
            )
        )
        assertEquals(
            true,
            Niv99ImageIngressSchedulingPolicy.isApplied(
                tier = Niv99ImageIngressSchedulingPolicy.Tier.BASELINE,
                requestedNice = -1,
                effectiveNice = -1
            )
        )
    }
}
