package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillSingleEnhancementPolicyTest {

    @Test
    fun neutralHalJpeg_doesNotRunRedundantStrictEnhancement() {
        assertFalse(
            StillSingleEnhancementPolicy.shouldEnhance(
                aiEnhancementEnabled = false,
                strictHighEndEnabled = true,
                neutralLook = true
            )
        )
    }

    @Test
    fun explicitAiEnhancement_isAlwaysHonored() {
        assertTrue(
            StillSingleEnhancementPolicy.shouldEnhance(
                aiEnhancementEnabled = true,
                strictHighEndEnabled = false,
                neutralLook = true
            )
        )
    }

    @Test
    fun strictNonNeutralLook_keepsExistingRefinementPath() {
        assertTrue(
            StillSingleEnhancementPolicy.shouldEnhance(
                aiEnhancementEnabled = false,
                strictHighEndEnabled = true,
                neutralLook = false
            )
        )
    }
}
