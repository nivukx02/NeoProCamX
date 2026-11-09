package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIHdrMathTest {

    @Test
    fun shouldApplyAcesTonemap_returnsFalseForStableHdrOutput() {
        assertFalse(AIHdrMath.shouldApplyAcesTonemap(clippedRatio = 0.01f, meanLuma = 0.50f))
    }

    @Test
    fun shouldApplyAcesTonemap_returnsTrueForClippedHighlights() {
        assertTrue(AIHdrMath.shouldApplyAcesTonemap(clippedRatio = 0.03f, meanLuma = 0.50f))
    }

    @Test
    fun acesCurve_mapsInputIntoDisplayRange() {
        assertEquals(0f, AIHdrMath.acesCurve(0f), 0.0001f)
        assertTrue(AIHdrMath.acesCurve(1.0f) in 0.0f..1.0f)
        assertTrue(AIHdrMath.acesCurve(2.0f) >= AIHdrMath.acesCurve(1.0f))
    }

    @Test
    fun extendedReinhardTonemap_compressesHighlightsWithWhitePoint() {
        val mid = AIHdrMath.extendedReinhardTonemap(0.8f, whitePoint = 2.2f)
        val highlight = AIHdrMath.extendedReinhardTonemap(2.5f, whitePoint = 2.2f)

        assertTrue(mid in 0f..1f)
        assertTrue(highlight in 0f..1f)
        assertTrue(highlight > mid)
    }

    @Test
    fun srgbLinear_roundTrip_staysClose() {
        val encoded = 0.53f
        val linear = AIHdrMath.srgbToLinear(encoded)
        val roundTrip = AIHdrMath.linearToSrgb(linear) / 255f

        assertEquals(encoded, roundTrip, 0.015f)
    }

    @Test
    fun hlgOetf_matchesBt2100AnchorPoints() {
        assertEquals(0f, AIHdrMath.hlgOetf(0f), 1e-6f)
        assertEquals(0.5f, AIHdrMath.hlgOetf(1f / 12f), 1e-4f)
        assertEquals(1f, AIHdrMath.hlgOetf(1f), 1e-4f)
    }

    @Test
    fun hlgInvOetf_matchesBt2100AnchorPoints() {
        assertEquals(0f, AIHdrMath.hlgInvOetf(0f), 1e-6f)
        assertEquals(1f / 12f, AIHdrMath.hlgInvOetf(0.5f), 1e-4f)
        assertEquals(1f, AIHdrMath.hlgInvOetf(1f), 1e-4f)
    }

    /**
     * The sRGB / Rec.1886 EOTF is defined piecewise in IEC 61966-2-1:
     *   v ≤ 0.04045 → linear = v / 12.92
     *   else        → linear = ((v + 0.055) / 1.055)^2.4
     *
     * `ColorScience.linearize709` delegates to [AIHdrMath.srgbToLinear], so this
     * test is the regression guard against any drift back to a wrong single-power
     * approximation (such as `pow(2.05f)`, which biases shadows up by several
     * percent and highlights down).
     */
    @Test
    fun srgbToLinear_matchesPiecewiseSpecAtAnchorPoints() {
        // Black anchors exactly to zero in both segments.
        assertEquals(0f, AIHdrMath.srgbToLinear(0f), 1e-6f)
        // Linear segment (v ≤ 0.04045): linear = v / 12.92.
        assertEquals(0.020f / 12.92f, AIHdrMath.srgbToLinear(0.020f), 1e-6f)
        // Boundary value: per spec, both branches converge at v = 0.04045.
        assertEquals(0.04045f / 12.92f, AIHdrMath.srgbToLinear(0.04045f), 5e-5f)
        // Power segment, mid-grey reference: 0.5 sRGB ≈ 0.21404 linear.
        assertEquals(0.21404f, AIHdrMath.srgbToLinear(0.5f), 5e-4f)
        // Power segment, near-white: ((0.95 + 0.055)/1.055)^2.4 ≈ 0.89001 linear.
        assertEquals(0.89001f, AIHdrMath.srgbToLinear(0.95f), 5e-4f)
        // White anchors to 1.0 within the spec piecewise.
        assertEquals(1f, AIHdrMath.srgbToLinear(1f), 5e-4f)
    }

    @Test
    fun linearToSrgbFloat_isExactInverseOfSrgbToLinear_acrossDomain() {
        // Round-trip many points so a bad gamma exponent or missing toe shows up.
        val testPoints = floatArrayOf(
            0f, 0.001f, 0.005f, 0.018f, 0.04f, 0.05f, 0.10f,
            0.18f, 0.25f, 0.40f, 0.50f, 0.65f, 0.80f, 0.95f, 1.0f
        )
        for (encoded in testPoints) {
            val linear = AIHdrMath.srgbToLinear(encoded)
            val roundTrip = AIHdrMath.linearToSrgbFloat(linear)
            // 1e-3 tolerance covers Float precision losses through the 2.4 power.
            assertEquals(
                "Round-trip drift at encoded=$encoded (linear=$linear, back=$roundTrip)",
                encoded, roundTrip, 1e-3f
            )
        }
    }

    /**
     * A single-power-curve `pow(2.05f)` approximation deviates measurably from
     * the spec EOTF at mid-grey. This guards against silent reintroduction of
     * the bug regardless of which file the caller lives in.
     */
    @Test
    fun srgbToLinear_rejectsSinglePowerApproximation() {
        val encoded = 0.5f
        val correct = AIHdrMath.srgbToLinear(encoded)             // ≈ 0.21404
        val wrongApprox = Math.pow(encoded.toDouble(), 2.05).toFloat() // ≈ 0.24199
        // The two must differ by more than 0.01 — i.e., the spec EOTF is NOT
        // the same curve as a flat pow(2.05).
        assertTrue(
            "EOTF must not collapse to pow(2.05); diff was ${Math.abs(correct - wrongApprox)}",
            Math.abs(correct - wrongApprox) > 0.01f
        )
    }

    @Test
    fun temporalConsistencyWeight_penalizesLargeDifferences() {
        val lowDiff = AIHdrMath.temporalConsistencyWeight(diff = 5, alignmentConfidence = 1.0)
        val highDiff = AIHdrMath.temporalConsistencyWeight(diff = 70, alignmentConfidence = 1.0)

        assertTrue(lowDiff > highDiff)
    }

    @Test
    fun exposurePreferenceWeight_prefersBrightFrameForDarkRegionsAndDarkFrameForHighlights() {
        val shadowPreferred = AIHdrMath.exposurePreferenceWeight(normalizedLuma = 0.10f, exposureScale = 2.0f)
        val shadowWrong = AIHdrMath.exposurePreferenceWeight(normalizedLuma = 0.10f, exposureScale = 0.5f)
        val highlightPreferred = AIHdrMath.exposurePreferenceWeight(normalizedLuma = 0.90f, exposureScale = 0.5f)
        val highlightWrong = AIHdrMath.exposurePreferenceWeight(normalizedLuma = 0.90f, exposureScale = 2.0f)

        assertTrue(shadowPreferred > shadowWrong)
        assertTrue(highlightPreferred > highlightWrong)
    }

    @Test
    fun clippingPenalty_strongly_penalizes_nearClippedSamples() {
        val safe = AIHdrMath.clippingPenalty(210, 200, 190)
        val clipped = AIHdrMath.clippingPenalty(253, 240, 238)

        assertTrue(safe > clipped)
    }
}
