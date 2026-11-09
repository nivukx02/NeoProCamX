package com.neoproc.neoproccamx.stabilization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EisModeAvailabilityTest {

    @Test
    fun defaultModeIsOff() {
        // No-downgrade default: stabilization is OFF until enabled device-side.
        assertEquals(StabilizationModePolicy.StabilizationMode.OFF, EisModeAvailability.DEFAULT_MODE)
    }

    @Test
    fun offStandardLockedAreAllSelectable() {
        assertTrue(EisModeAvailability.SELECTABLE_MODES.containsAll(
            listOf(
                StabilizationModePolicy.StabilizationMode.OFF,
                StabilizationModePolicy.StabilizationMode.STANDARD,
                StabilizationModePolicy.StabilizationMode.LOCKED,
            )
        ))
    }

    @Test
    fun horizonLevelIsNotAvailableOnGyroOnlyData() {
        // Honest gate: horizon-levelling needs absolute roll from gravity fusion the gyro stream lacks.
        assertEquals(EisModeAvailability.Availability.NOT_AVAILABLE, EisModeAvailability.horizonLevel())
        assertEquals(EisModeAvailability.Availability.NOT_AVAILABLE, EisModeAvailability.horizonLevel(hasGravityFusion = false))
        // It only becomes available once a real gravity-fusion source exists (device-gated).
        assertEquals(EisModeAvailability.Availability.AVAILABLE, EisModeAvailability.horizonLevel(hasGravityFusion = true))
    }
}
