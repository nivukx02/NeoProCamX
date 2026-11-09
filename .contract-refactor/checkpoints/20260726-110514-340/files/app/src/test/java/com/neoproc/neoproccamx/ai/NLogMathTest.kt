package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NLogMathTest {

    @Test
    fun nLogEncode_isMonotonicAndUsesOriginalCurveFamily() {
        val samples = listOf(0.0f, 0.005f, 0.018f, 0.18f, 0.50f, 1.0f)
        val encoded = samples.map(NLogMath::encode)

        encoded.zipWithNext().forEach { (a, b) ->
            assertTrue("N-Log must be monotonic: $encoded", b >= a)
        }
        assertTrue(encoded.all { it in 0.0f..1.0f })
        assertTrue(abs(NLogMath.encode(0.18f, NLogVariant.CINEMA) - NLogMath.encode(0.18f)) < 0.08f)
        assertTrue(NLogMath.encode(0.18f) in 0.46f..0.66f)
    }

    @Test
    fun nLogRoundTrip_preservesSceneLinearValues() {
        listOf(0.0f, 0.002f, 0.018f, 0.18f, 0.50f, 1.0f).forEach { linear ->
            val decoded = NLogMath.decode(NLogMath.encode(linear))

            assertEquals(linear, decoded, 0.004f)
        }
    }

    @Test
    fun nLogFamily_hitsCalibrated18PercentGrayTarget() {
        NLogVariant.entries.forEach { variant ->
            val encodedGray = NLogMath.encode(NLogMath.SCENE_GRAY_18, variant)
            val decodedGray = NLogMath.decode(NLogMath.GRAY_18_CODE, variant)

            assertEquals(
                "${variant.label} must put 18% gray at the shared grading anchor",
                NLogMath.GRAY_18_CODE,
                encodedGray,
                0.0015f
            )
            assertEquals(
                "${variant.label} must decode the shared gray anchor back to 18% scene linear",
                NLogMath.SCENE_GRAY_18,
                decodedGray,
                0.0015f
            )
        }
    }

    @Test
    fun nLogV5ApexParameterTableUsesExactPromptDefaultsAndLookup() {
        val base = NLogMath.parameters(NLogVariant.BASE)
        val shadow = NLogMath.parameters(NLogVariant.SHADOW)
        val highlight = NLogMath.parameters(NLogVariant.HIGHLIGHT)
        val cinema = NLogMath.parameters(NLogVariant.CINEMA)

        assertEquals(0.010f, base.toeCut, 0f)
        assertEquals(8.0f, base.toeSlope, 0f)
        assertEquals(8.0f, base.toeEntrySlope, 0f)
        assertEquals(120.0f, base.logDensity, 0f)
        assertEquals(0.080000f, base.toeHeight, 0f)
        assertEquals(0.137099f, base.logScale, 0.000001f)

        assertEquals(0.004f, shadow.toeCut, 0f)
        assertEquals(10.0f, shadow.toeSlope, 0f)
        assertEquals(70.0f, shadow.logDensity, 0f)

        assertEquals(0.005f, highlight.toeCut, 0f)
        assertEquals(12.0f, highlight.toeSlope, 0f)
        assertEquals(260.0f, highlight.logDensity, 0f)

        assertEquals(0.008f, cinema.toeCut, 0f)
        assertEquals(10.0f, cinema.toeSlope, 0f)
        assertEquals(110.0f, cinema.logDensity, 0f)

        assertEquals(NLogVariant.BASE, NLogMath.labelToVariant("N-LOG v5 APEX Base"))
        assertEquals(NLogVariant.SHADOW, NLogMath.labelToVariant("nlog shadow"))
        assertEquals(NLogVariant.HIGHLIGHT, NLogMath.labelToVariant("Apex Highlight"))
        assertEquals(NLogVariant.CINEMA, NLogMath.labelToVariant("N-Log Cinema"))
        assertEquals("N-LOG v5 APEX Highlight", NLogMath.labelForVariant(NLogVariant.HIGHLIGHT))
    }

    @Test
    fun nLogFamily_roundTripsDenseSamplesWithoutClipping() {
        NLogVariant.entries.forEach { variant ->
            for (sample in 0..1000) {
                val linear = sample / 1000f
                val decoded = NLogMath.decode(NLogMath.encode(linear, variant), variant)

                assertEquals("${variant.label} round trip failed at $linear", linear, decoded, 0.0015f)
            }
        }
    }

    @Test
    fun nLogShadowVariantLiftsDeepShadowsAboveBaseUsingExactApexToe() {
        assertTrue(
            NLogMath.encode(0.005f, NLogVariant.SHADOW) >
                NLogMath.encode(0.005f, NLogVariant.BASE)
        )
        assertTrue(
            NLogMath.encode(0.006f, NLogVariant.SHADOW) >
                NLogMath.encode(0.006f, NLogVariant.BASE)
        )
    }

    @Test
    fun nLogFamily_isMonotonicAndRoundTripsEveryVariant() {
        val samples = listOf(0.0f, 0.002f, 0.005f, 0.018f, 0.18f, 0.42f, 0.72f, 1.0f)

        NLogVariant.entries.forEach { variant ->
            val encoded = samples.map { NLogMath.encode(it, variant) }

            encoded.zipWithNext().forEach { (a, b) ->
                assertTrue("${variant.label} must be monotonic: $encoded", b >= a)
            }
            samples.forEach { linear ->
                val decoded = NLogMath.decode(NLogMath.encode(linear, variant), variant)
                assertEquals("${variant.label} round trip failed at $linear", linear, decoded, 0.0045f)
            }
        }
    }

    @Test
    fun nLogFamily_placesGrayCardAndSkinInGradingSafeRanges() {
        NLogVariant.entries.forEach { variant ->
            val gray = NLogMath.encode(0.18f, variant)
            val darkerSkin = NLogMath.encode(0.22f, variant)
            val brighterSkin = NLogMath.encode(0.55f, variant)

            assertTrue("${variant.label} gray card must stay gradeable: $gray", gray in 0.46f..0.66f)
            assertTrue("${variant.label} must preserve skin separation", brighterSkin - darkerSkin > 0.10f)
            assertTrue("${variant.label} skin range must stay below clipping", brighterSkin < 0.92f)
        }
    }

    @Test
    fun nLogFamily_keepsSkinToneZoneProtectedForGrading() {
        val representativeSkinLuma = listOf(0.22f, 0.32f, 0.42f, 0.55f)

        NLogVariant.entries.forEach { variant ->
            val encoded = representativeSkinLuma.map { NLogMath.encode(it, variant) }

            assertTrue(
                "${variant.label} skin zone must avoid muddy low codes: $encoded",
                encoded.first() >= NLogMath.SKIN_CODE_MIN
            )
            assertTrue(
                "${variant.label} skin zone must keep highlight headroom: $encoded",
                encoded.last() <= NLogMath.SKIN_CODE_MAX
            )
            encoded.zipWithNext().forEach { (a, b) ->
                assertTrue("${variant.label} must preserve skin luma ordering: $encoded", b > a)
            }
        }
    }

    @Test
    fun nLogFamily_protectsHighlightsWithReversibleRolloff() {
        NLogVariant.entries.forEach { variant ->
            val midtoneStep = NLogMath.encode(0.55f, variant) - NLogMath.encode(0.35f, variant)
            val highlightStep = NLogMath.encode(0.95f, variant) - NLogMath.encode(0.75f, variant)
            val whiteCode = NLogMath.encode(1.0f, variant)

            assertTrue("${variant.label} highlights must remain gradeable", highlightStep > 0.025f)
            assertTrue("${variant.label} highlights must roll off softer than midtones", highlightStep < midtoneStep)
            assertTrue("${variant.label} must leave code headroom above scene white", whiteCode <= 0.995f)
        }
    }

    @Test
    fun nLogShadowAllocation_doesNotLiftNoiseFloorUnnaturally() {
        assertEquals(0f, NLogMath.encode(0f), 0f)
        assertTrue(NLogMath.encode(0.001f) <= 0.0100f)
        assertTrue(
            NLogMath.encode(0.006f, NLogVariant.SHADOW) >
                NLogMath.encode(0.006f, NLogVariant.BASE)
        )
    }

    @Test
    fun nLogVariantsHaveDistinctShadowHighlightAndCinemaBehavior() {
        assertTrue(
            NLogMath.encode(0.006f, NLogVariant.SHADOW) >
                NLogMath.encode(0.006f, NLogVariant.BASE)
        )
        assertTrue(
            NLogMath.encode(0.92f, NLogVariant.HIGHLIGHT) <
                NLogMath.encode(0.92f, NLogVariant.BASE)
        )
        assertTrue(
            NLogMath.encode(0.55f, NLogVariant.CINEMA) -
                NLogMath.encode(0.18f, NLogVariant.CINEMA) >
                NLogMath.encode(0.55f, NLogVariant.BASE) -
                NLogMath.encode(0.18f, NLogVariant.BASE)
        )
    }
}
