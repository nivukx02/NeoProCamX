package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class NivukxVisionMathTest {

    @Test
    fun radialCompressionPreservesJzAndHueWhileBoundingChroma() {
        val original = NivukxVisionMath.JzAzBz(jz = 0.42, az = 0.34, bz = 0.22)
        val compressed = NivukxVisionMath.compressRadialChroma(
            color = original,
            cMax = 0.30,
            p50 = 100.0,
            p99 = 1_000.0,
            lPeak = 10_000.0
        )

        assertEquals(original.jz, compressed.jz, 0.0)
        assertEquals(atan2(original.bz, original.az), atan2(compressed.bz, compressed.az), 1e-12)
        assertTrue(hypot(compressed.az, compressed.bz) <= 0.30 + 1e-12)
    }

    @Test
    fun nearWhiteGuardKeepsAtLeastFifteenPercentChromaAtClip() {
        val scaleAtClip = NivukxVisionMath.nearWhiteChromaScale(
            NivukxVisionMath.Ap1Rgb(1.0, 1.0, 1.0)
        )
        val scaleAwayFromClip = NivukxVisionMath.nearWhiteChromaScale(
            NivukxVisionMath.Ap1Rgb(0.72, 0.63, 0.58)
        )

        assertEquals(0.15, scaleAtClip, 1e-12)
        assertEquals(1.0, scaleAwayFromClip, 1e-12)
    }

    @Test
    fun dynamicSceneParametersStayInsideEliteBounds() {
        val params = NivukxVisionMath.resolveSceneParameters(
            p50 = 120.0,
            p99 = 4_000.0,
            lPeak = 10_000.0
        )

        assertEquals(0.66, params.omega, 1e-12)
        assertEquals(0.4828, params.gammaCompress, 1e-4)
        assertTrue(params.omega in 0.5..1.2)
        assertTrue(params.gammaCompress in 0.25..0.85)
    }

    @Test
    fun nlogs12ForwardInverseRoundTripIsStable() {
        listOf(0.0, 0.01, 0.18, 1.0, 120.0, 1_000.0, 10_000.0).forEach { luminance ->
            val encoded = NivukxVisionMath.NLogs12.encode(luminance)
            val decoded = NivukxVisionMath.NLogs12.decode(encoded)
            assertEquals(luminance.coerceIn(0.0, 10_000.0), decoded, 1e-7)
        }
    }
}
