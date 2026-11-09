package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSdrGalleryBitratePolicyTest {

    @Test
    fun `direct gallery target is the highest encoder admitted bitrate`() {
        val decision = DirectSdrGalleryBitratePolicy.resolveMaximumAdmittedTarget(
            codecRequestCeilingBps = 2_000_000_000L,
            clampBitrateToEncoderCapabilities = { requested -> requested.coerceAtMost(160_000_000) }
        )

        assertEquals(2_000_000_000, decision.codecPolicyCeilingBps)
        assertEquals(160_000_000, decision.encoderAdmittedMaximumBps)
        assertEquals(160_000_000, decision.targetBitrateBps)
        assertTrue(decision.capabilityBound)
        assertTrue(decision.telemetryLine().contains("admission=STRICT_DEVICE_MAX"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid encoder capability is rejected`() {
        DirectSdrGalleryBitratePolicy.resolveMaximumAdmittedTarget(
            codecRequestCeilingBps = 2_000_000_000L,
            clampBitrateToEncoderCapabilities = { 0 }
        )
    }
}
