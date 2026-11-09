package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictBitrateCapabilityAdmissionTest {
    @Test
    fun strictModeAcceptsAnEncoderThatMeetsTheCompleteFloor() {
        val decision = StrictBitrateCapabilityAdmission.resolve(
            strictHighEnd = true,
            requiredBitrateBps = 340_000_000,
            availableBitrateBps = 420_000_000
        )

        assertTrue(decision.accepted)
        assertEquals(StrictBitrateCapabilityAdmission.Status.ACCEPTED, decision.status)
    }

    @Test
    fun strictModeFailsClosedInsteadOfClampingBelowTheQualityFloor() {
        val decision = StrictBitrateCapabilityAdmission.resolve(
            strictHighEnd = true,
            requiredBitrateBps = 1_000_000_000,
            availableBitrateBps = 240_000_000
        )

        assertFalse(decision.accepted)
        assertEquals(
            StrictBitrateCapabilityAdmission.Status.REJECTED_BELOW_REQUIRED_FLOOR,
            decision.status
        )
        assertTrue(decision.rejectionMessage("HEVC Main10 encoder").contains("refused instead of downgraded"))
    }

    @Test
    fun compatibilityModeMayUseTheAdvertisedCapabilityClamp() {
        val decision = StrictBitrateCapabilityAdmission.resolve(
            strictHighEnd = false,
            requiredBitrateBps = 340_000_000,
            availableBitrateBps = 120_000_000
        )

        assertTrue(decision.accepted)
    }

    @Test
    fun invalidCapabilityNeverEntersMediaCodecEvenOutsideStrictMode() {
        val decision = StrictBitrateCapabilityAdmission.resolve(
            strictHighEnd = false,
            requiredBitrateBps = 340_000_000,
            availableBitrateBps = 0
        )

        assertFalse(decision.accepted)
        assertEquals(
            StrictBitrateCapabilityAdmission.Status.REJECTED_INVALID_CAPABILITY,
            decision.status
        )
    }
}
