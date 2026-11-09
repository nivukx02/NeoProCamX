package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEncoderBitrateCapabilityPolicyTest {

    @Test
    fun upperBoundUsesStrongestHardwareEncoderInsteadOfWeakestCandidate() {
        val upper = RecordingEncoderBitrateCapabilityPolicy.resolveUpperBound(
            listOf(
                capability(upper = 160_000_000, hardwareAccelerated = true, softwareOnly = false),
                capability(upper = 600_000_000, hardwareAccelerated = true, softwareOnly = false),
                capability(upper = 900_000_000, hardwareAccelerated = false, softwareOnly = true)
            )
        )

        assertEquals(600_000_000, upper)
    }

    @Test
    fun upperBoundFallsBackToSoftwareWhenNoHardwareEncoderIsPresent() {
        val upper = RecordingEncoderBitrateCapabilityPolicy.resolveUpperBound(
            listOf(
                capability(upper = 220_000_000, hardwareAccelerated = false, softwareOnly = true),
                capability(upper = 420_000_000, hardwareAccelerated = false, softwareOnly = true)
            )
        )

        assertEquals(420_000_000, upper)
    }

    @Test
    fun lowerBoundUsesPreferredHardwarePool() {
        val lower = RecordingEncoderBitrateCapabilityPolicy.resolveLowerBound(
            listOf(
                capability(lower = 1, upper = 800_000_000, hardwareAccelerated = false, softwareOnly = true),
                capability(lower = 64_000, upper = 160_000_000, hardwareAccelerated = true, softwareOnly = false),
                capability(lower = 128_000, upper = 600_000_000, hardwareAccelerated = true, softwareOnly = false)
            )
        )

        assertEquals(128_000, lower)
    }

    @Test
    fun lowerAndUpperBoundsComeFromTheSamePreferredHardwareEncoder() {
        val capabilities = listOf(
            capability(
                lower = 64_000,
                upper = 600_000_000,
                hardwareAccelerated = true,
                softwareOnly = false
            ),
            capability(
                lower = 128_000,
                upper = 160_000_000,
                hardwareAccelerated = true,
                softwareOnly = false
            ),
            capability(
                lower = 1,
                upper = 900_000_000,
                hardwareAccelerated = false,
                softwareOnly = true
            )
        )

        assertEquals(600_000_000, RecordingEncoderBitrateCapabilityPolicy.resolveUpperBound(capabilities))
        assertEquals(64_000, RecordingEncoderBitrateCapabilityPolicy.resolveLowerBound(capabilities))
    }

    @Test
    fun bitrateSupportRequiresRequestedRateInsideRange() {
        assertTrue(
            RecordingEncoderBitrateCapabilityPolicy.isBitrateSupported(
                lower = 64_000,
                upper = 600_000_000,
                requestedBitrate = 420_000_000
            )
        )
        assertFalse(
            RecordingEncoderBitrateCapabilityPolicy.isBitrateSupported(
                lower = 64_000,
                upper = 160_000_000,
                requestedBitrate = 420_000_000
            )
        )
    }

    private fun capability(
        lower: Int = 64_000,
        upper: Int,
        hardwareAccelerated: Boolean,
        softwareOnly: Boolean
    ): RecordingEncoderBitrateCapability =
        RecordingEncoderBitrateCapability(
            lower = lower,
            upper = upper,
            hardwareAccelerated = hardwareAccelerated,
            softwareOnly = softwareOnly
        )
}
