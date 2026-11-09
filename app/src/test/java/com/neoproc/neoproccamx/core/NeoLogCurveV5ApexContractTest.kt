package com.neoproc.neoproccamx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NeoLogCurveV5ApexContractTest {

    private data class ExpectedApexProfile(
        val variant: NeoLogCurve.Variant,
        val toeCut: Float,
        val toeHeight: Float,
        val xMax: Float
    )

    private val expectedProfiles = listOf(
        ExpectedApexProfile(NeoLogCurve.Variant.BASE, toeCut = 0.010f, toeHeight = 0.080000f, xMax = 6.843034f),
        ExpectedApexProfile(NeoLogCurve.Variant.SHADOW, toeCut = 0.004f, toeHeight = 0.040000f, xMax = 3.164296f),
        ExpectedApexProfile(NeoLogCurve.Variant.HIGHLIGHT, toeCut = 0.005f, toeHeight = 0.060000f, xMax = 14.041075f),
        ExpectedApexProfile(NeoLogCurve.Variant.CINEMA, toeCut = 0.008f, toeHeight = 0.080000f, xMax = 6.377367f)
    )

    @Test
    fun nLogV5KeepsMeasurementHonestyAndExactApexVersion() {
        assertEquals("N-LOG-v5.0-APEX-LOG", NeoLogCurve.VERSION)
        assertEquals("DEVICE_MEASUREMENT_REQUIRED", NeoLogCurve.DYNAMIC_RANGE_EVIDENCE)
        assertEquals(0.18f, NeoLogCurve.SCENE_GRAY_18, 0f)
        assertEquals(0.50f, NeoLogCurve.GRAY_18_CODE, 0f)
    }

    @Test
    fun nLogV5ExactPromptProfilesHitBlackToeGrayAndCeiling() {
        expectedProfiles.forEach { profile ->
            assertEquals("$profile black must map exactly to code zero", 0f, NeoLogCurve.encode(0f, profile.variant), 0f)
            assertEquals(
                "${profile.variant} toe knee must hit the exact prompt toe height",
                profile.toeHeight,
                NeoLogCurve.encode(profile.toeCut, profile.variant),
                0.0008f
            )
            assertEquals(
                "${profile.variant} 18 percent gray must hit the exact shared v5 anchor",
                0.50f,
                NeoLogCurve.encode(0.18f, profile.variant),
                0.0008f
            )
            assertEquals(
                "${profile.variant} code ceiling must decode to the prompt x_max",
                profile.xMax,
                NeoLogCurve.decode(1f, profile.variant),
                0.08f
            )
        }
    }

    @Test
    fun nLogV5ApexCurveIsStrictlyMonotonicAcrossPromptDomain() {
        expectedProfiles.forEach { profile ->
            var previous = NeoLogCurve.encode(0f, profile.variant)
            for (index in 1..6000) {
                val linear = profile.xMax * index / 6000f
                val encoded = NeoLogCurve.encode(linear, profile.variant)

                assertTrue("${profile.variant} inverted at $linear: $encoded <= $previous", encoded > previous)
                previous = encoded
            }
        }
    }

    @Test
    fun nLogV5ApexCurveIsC2AtEveryToeKnee() {
        expectedProfiles.forEach { profile ->
            val parameters = NeoLogCurve.v5Parameters(profile.variant)
            val valueAtToe = parameters.c1 + parameters.c3 + parameters.c4 + parameters.c5
            val toeSlopeAtKnee =
                (parameters.c1 + 3f * parameters.c3 + 4f * parameters.c4 + 5f * parameters.c5) /
                    parameters.toeCut
            val toeCurvatureAtKnee =
                (6f * parameters.c3 + 12f * parameters.c4 + 20f * parameters.c5) /
                    (parameters.toeCut * parameters.toeCut)

            assertEquals("${profile.variant} C0 value residual", parameters.toeHeight, valueAtToe, 0.00001f)
            assertTrue("${profile.variant} C1 residual too high", abs(toeSlopeAtKnee - parameters.m1) < 0.001f)
            assertTrue("${profile.variant} C2 residual too high", abs(toeCurvatureAtKnee - parameters.m2) < 0.60f)
        }
    }
}
