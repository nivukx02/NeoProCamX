package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.quality.OmegaCapabilityStatus
import com.neoproc.neoproccamx.ui.controls.ManualControlRawClaimPolicy.RawClaimLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f02 (CP-U2) — the RAW tab label cannot advertise RAW the pipeline did not deliver. Default
 * (enforce off) reproduces today's RAW ON/OFF exactly (no-downgrade); enforced mode demands a proven
 * OmegaCapabilityStatus.SUPPORTED, else honest RAW N/A. Pure JVM.
 */
class ManualControlRawClaimPolicyTest {

    @Test
    fun defaultOffReproducesTodaysRawOnOff() {
        assertEquals(RawClaimLabel.RAW_OFF, ManualControlRawClaimPolicy.labelFor(rawToggleOn = false))
        assertEquals("RAW OFF", ManualControlRawClaimPolicy.text(ManualControlRawClaimPolicy.labelFor(false)))
        assertEquals(RawClaimLabel.RAW_ON, ManualControlRawClaimPolicy.labelFor(rawToggleOn = true))
        assertEquals("RAW ON", ManualControlRawClaimPolicy.text(ManualControlRawClaimPolicy.labelFor(true)))
    }

    @Test
    fun enforcedUnprovenRawIsNotAvailable() {
        assertEquals(
            RawClaimLabel.RAW_NOT_AVAILABLE,
            ManualControlRawClaimPolicy.labelFor(true, OmegaCapabilityStatus.NOT_AVAILABLE, enforceProof = true)
        )
        assertEquals(
            RawClaimLabel.RAW_NOT_AVAILABLE,
            ManualControlRawClaimPolicy.labelFor(true, OmegaCapabilityStatus.LIMITED, enforceProof = true)
        )
        assertEquals(
            RawClaimLabel.RAW_NOT_AVAILABLE,
            ManualControlRawClaimPolicy.labelFor(true, proofStatus = null, enforceProof = true)
        )
        assertEquals("RAW N/A", ManualControlRawClaimPolicy.text(RawClaimLabel.RAW_NOT_AVAILABLE))
    }

    @Test
    fun enforcedProvenRawIsOn() {
        assertEquals(
            RawClaimLabel.RAW_ON,
            ManualControlRawClaimPolicy.labelFor(true, OmegaCapabilityStatus.SUPPORTED, enforceProof = true)
        )
    }

    @Test
    fun toggleOffIsAlwaysOffEvenWhenProven() {
        assertEquals(
            RawClaimLabel.RAW_OFF,
            ManualControlRawClaimPolicy.labelFor(false, OmegaCapabilityStatus.SUPPORTED, enforceProof = true)
        )
    }
}
