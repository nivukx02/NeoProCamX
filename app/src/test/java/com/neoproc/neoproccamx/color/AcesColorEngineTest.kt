package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class AcesColorEngineTest {

    @Test
    fun rec709NeutralMapsToAcesCgNeutralWithoutCertificationClaim() {
        val neutral = AcesColorEngine.toAcesCg(
            rgb = AcesRgb(0.18f, 0.18f, 0.18f),
            input = AcesInputColorSpace.REC709
        )

        assertEquals(0.18f, neutral.r, 0.004f)
        assertEquals(0.18f, neutral.g, 0.004f)
        assertEquals(0.18f, neutral.b, 0.004f)
        assertEquals("NON_CERTIFIED_INTERNAL_ACESCG", AcesColorEngine.CERTIFICATION_STATUS)
    }

    @Test
    fun fittedPreviewTransformIsMonotonicAndKeepsHighlightsBounded() {
        val dark = AcesColorEngine.applyFittedRrtOdt(
            acesCg = AcesRgb(0.04f, 0.04f, 0.04f),
            output = AcesOutputColorSpace.REC709_SDR
        )
        val middle = AcesColorEngine.applyFittedRrtOdt(
            acesCg = AcesRgb(0.18f, 0.18f, 0.18f),
            output = AcesOutputColorSpace.REC709_SDR
        )
        val highlight = AcesColorEngine.applyFittedRrtOdt(
            acesCg = AcesRgb(8.0f, 8.0f, 8.0f),
            output = AcesOutputColorSpace.REC709_SDR
        )

        assertTrue(dark.r < middle.r)
        assertTrue(middle.r < highlight.r)
        assertTrue(highlight.r <= 1.0f)
        assertTrue(highlight.g <= 1.0f)
        assertTrue(highlight.b <= 1.0f)
    }

    @Test
    fun sdr10ToneMapperUsesSoftToeShoulderAnd10BitQuantization() {
        val shadow = AcesColorEngine.applySdr10ShoulderToe(0.04f)
        val midtone = AcesColorEngine.applySdr10ShoulderToe(0.40f)
        val shoulder = AcesColorEngine.applySdr10ShoulderToe(0.78f)
        val highlight = AcesColorEngine.applySdr10ShoulderToe(4.0f)
        val encoded = AcesColorEngine.encodeRec2020Sdr10(AcesRgb(1.4f, 1.4f, 1.4f))

        assertTrue(shadow > 0.04f)
        assertTrue(shadow < midtone)
        assertTrue(midtone < shoulder)
        assertTrue(shoulder < highlight)
        assertTrue(highlight < 1.0f)
        assertEquals(encoded.r, encoded.g)
        assertEquals(encoded.g, encoded.b)
        assertTrue(encoded.r in 1..1022)
    }

    @Test
    fun resolvesOnlyNonCertifiedNeoAndStandardsProfiles() {
        val plan = AcesColorEngine.resolvePlan(
            selectedColorSpace = "Rec.2020",
            selectedColorScience = "NIVUKX Vision",
            hdrTransport = true,
            logTransport = false
        )

        assertEquals(AcesInputColorSpace.REC2020, plan.input)
        assertEquals(AcesOutputColorSpace.REC2020_PQ, plan.output)
        assertEquals("NIVUKX Vision", plan.selectedColorScience)
        assertEquals("NON_CERTIFIED_INTERNAL_ACESCG", plan.certificationStatus)
        assertTrue(plan.signature.contains("ACESCG_LINEAR"))
        listOf("Dolby", "HDR10+ certified", "IMAX", "ARRI", "Sony", "RED").forEach { forbidden ->
            assertFalse("Forbidden label leaked: $forbidden", plan.signature.contains(forbidden, ignoreCase = true))
            assertFalse("Forbidden label leaked: $forbidden", plan.truthLabel.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun neoWideGamutMatrixAndInverseArePrecisionStable() {
        val forward = AcesColorEngine.NEO_WIDE_GAMUT_TO_XYZ
        val inverse = AcesColorEngine.XYZ_TO_NEO_WIDE_GAMUT

        assertEquals(9, forward.size)
        assertEquals(9, inverse.size)
        assertEquals(0.774888889f, forward[0], 0.000001f)
        assertEquals(0.098962557f, forward[1], 0.000001f)
        assertEquals(0.082970662f, forward[2], 0.000001f)
        assertEquals(0.279812198f, forward[3], 0.000001f)
        assertEquals(0.709575275f, forward[4], 0.000001f)
        assertEquals(0.010612527f, forward[5], 0.000001f)
        assertEquals(0.000000000f, forward[6], 0.000001f)
        assertEquals(0.050512138f, forward[7], 0.000001f)
        assertEquals(0.871191949f, forward[8], 0.000001f)

        for (row in 0..2) {
            for (col in 0..2) {
                val value = (0..2).sumOf { k ->
                    inverse[row * 3 + k].toDouble() * forward[k * 3 + col].toDouble()
                }.toFloat()
                val expected = if (row == col) 1f else 0f
                assertEquals(expected, value, 0.000001f)
            }
        }
    }

    @Test
    fun resolvePlanRoutesNeoWideGamutThroughD55WorkingTransform() {
        val plan = AcesColorEngine.resolvePlan(
            selectedColorSpace = "Neo Wide Gamut",
            selectedColorScience = "Neo Wide Gamut",
            hdrTransport = false,
            logTransport = false
        )

        assertEquals(AcesInputColorSpace.NEO_WIDE_GAMUT, plan.input)
        assertEquals(AcesOutputColorSpace.REC2020_SDR, plan.output)
        assertEquals("Neo Wide Gamut", plan.selectedColorSpace)
        assertEquals("Neo Wide Gamut", plan.selectedColorScience)
        assertFalse(plan.signature.contains("Display P3"))
    }

    @Test
    fun resolvePlanPreservesUserSelectedNeoAcesHighEndWithoutVendorLabels() {
        val plan = AcesColorEngine.resolvePlan(
            selectedColorSpace = "Neo ACES AP1",
            selectedColorScience = "Neo ACES High End",
            hdrTransport = false,
            logTransport = false
        )

        assertEquals(AcesInputColorSpace.NEO_ACES_AP1, plan.input)
        assertEquals(AcesOutputColorSpace.REC2020_SDR, plan.output)
        assertEquals("Neo ACES AP1", plan.selectedColorSpace)
        assertEquals("Neo ACES High End", plan.selectedColorScience)
        assertEquals("ACESCG_LINEAR_HIGH_END", plan.workingSpace)
        assertTrue(plan.signature.contains("science=Neo ACES High End"))
        listOf("Dolby", "IMAX", "ARRI", "Sony", "RED").forEach { forbidden ->
            assertFalse(plan.signature.contains(forbidden, ignoreCase = true))
            assertFalse(plan.truthLabel.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun acesCgProvidesRoundTripMatricesForD65OklabReferenceProcessing() {
        val matrices = AcesColorEngine.neoOklabReferenceMatrices()
        val product = multiply3x3(
            matrices.d65ReferenceRgbToAcesCg,
            matrices.acesCgToD65ReferenceRgb
        )
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        identity.indices.forEach { index ->
            assertEquals(identity[index], product[index], 0.00001f)
        }
    }

    @Test
    fun neoWideGamutCompressionRollsOffChromaticityAndPreservesXyzLuminance() {
        val source = floatArrayOf(2.40f, 1.0f, -0.32f)
        val compressed = AcesColorEngine.compressNeoWideGamutXyzPreservingY(source)
        val xy = AcesColorEngine.xyChromaticity(compressed)

        assertEquals(source[1], compressed[1], 0.000001f)
        assertTrue(compressed.all { it.isFinite() })
        assertTrue(AcesColorEngine.isInsideNeoWideGamut(xy.x, xy.y))
        assertTrue(xy.x < AcesColorEngine.xyChromaticity(source).x)
    }

    @Test
    fun nivukxApexSignalShapingPreservesOklabHueWhileCompressingHighlightChroma() {
        val lab = AcesColorEngine.nivukxHpeOklabFromXyz(floatArrayOf(0.38f, 0.42f, 1.85f))
        val shaped = AcesColorEngine.applyNivukxApexSignalShaping(
            lab = lab,
            sceneLux = 180f,
            kelvin = 4300f
        )
        val hueBefore = atan2(lab.b, lab.a)
        val hueAfter = atan2(shaped.b, shaped.a)
        val hueDelta = abs(hueBefore - hueAfter)
        val chromaBefore = kotlin.math.sqrt(lab.a * lab.a + lab.b * lab.b)
        val chromaAfter = kotlin.math.sqrt(shaped.a * shaped.a + shaped.b * shaped.b)

        assertTrue(shaped.l.isFinite())
        assertTrue(shaped.a.isFinite())
        assertTrue(shaped.b.isFinite())
        assertTrue("Hue changed by $hueDelta radians", hueDelta < 0.0001f)
        assertTrue("Highlight chroma should be shaped down", chromaAfter < chromaBefore)
    }

    @Test
    fun ndr10DynamicToneMapMovesToeAndShoulderFromSceneHistogram() {
        val highContrastParams = AcesColorEngine.resolveNdr10ToneMapParams(
            sceneMin = 0.0004f,
            sceneMax = 8.0f
        )
        val lowLightParams = AcesColorEngine.resolveNdr10ToneMapParams(
            sceneMin = 0.001f,
            sceneMax = 0.30f
        )
        val mappedHighlight = AcesColorEngine.applyNdr10DynamicToneMap(
            linearSceneLuma = 4.0f,
            sceneMin = 0.0004f,
            sceneMax = 8.0f
        )
        val mappedShadow = AcesColorEngine.applyNdr10DynamicToneMap(
            linearSceneLuma = 0.025f,
            sceneMin = 0.001f,
            sceneMax = 0.30f
        )

        assertTrue(highContrastParams.alpha > lowLightParams.alpha)
        assertTrue(lowLightParams.beta > highContrastParams.beta)
        assertTrue(mappedHighlight in 0f..1f)
        assertTrue(mappedShadow > 0.025f)
    }

    @Test
    fun subtractiveFilmDensityDeepensSaturatedColorsAndLeavesNeutralNearlyStable() {
        val saturated = AcesColorEngine.applySubtractiveFilmDensity(AcesRgb(0.90f, 0.20f, 0.08f))
        val neutral = AcesColorEngine.applySubtractiveFilmDensity(AcesRgb(0.42f, 0.42f, 0.42f))

        assertTrue(saturated.r < 0.90f)
        assertTrue(saturated.g < 0.20f)
        assertTrue(saturated.b < 0.08f)
        assertEquals(0.42f, neutral.r, 0.0001f)
        assertEquals(0.42f, neutral.g, 0.0001f)
        assertEquals(0.42f, neutral.b, 0.0001f)
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            (0..2).sumOf { k ->
                a[row * 3 + k].toDouble() * b[k * 3 + column].toDouble()
            }.toFloat()
        }
}
