package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NdrNivukxCalibrationAnalyzerTest {

    @Test
    fun `target contains required tone and color roles`() {
        val patches = NdrNivukxCalibrationTarget.patches

        assertTrue("Calibration chart must provide enough measurement points", patches.size >= 36)
        assertEquals(
            "Patch ids must be stable and unique",
            patches.size,
            patches.map { it.id }.toSet().size
        )

        val roles = patches.map { it.role }.toSet()
        assertTrue(roles.contains(CalibrationPatchRole.NEUTRAL))
        assertTrue(roles.contains(CalibrationPatchRole.SHADOW))
        assertTrue(roles.contains(CalibrationPatchRole.HIGHLIGHT))
        assertTrue(roles.contains(CalibrationPatchRole.SKIN))
        assertTrue(roles.contains(CalibrationPatchRole.SKY))
        assertTrue(roles.contains(CalibrationPatchRole.FOLIAGE))
        assertTrue(roles.contains(CalibrationPatchRole.SATURATED_RED))
        assertTrue(roles.contains(CalibrationPatchRole.SATURATED_CYAN))
        assertTrue(roles.contains(CalibrationPatchRole.TEXTURE))
    }

    @Test
    fun `analyzer returns neutral correction for accurate capture`() {
        val measurements = NdrNivukxCalibrationTarget.patches.map {
            CalibrationPatchMeasurement(it.id, it.referenceSrgb)
        }

        val profile = NdrNivukxCalibrationAnalyzer.analyze(measurements)

        assertEquals(1.0f, profile.redScale, 0.0001f)
        assertEquals(1.0f, profile.greenScale, 0.0001f)
        assertEquals(1.0f, profile.blueScale, 0.0001f)
        assertEquals(0.0f, profile.highlightRolloffBias, 0.0001f)
        assertEquals(0.0f, profile.shadowLiftBias, 0.0001f)
        assertEquals(1.0f, profile.saturationScale, 0.0001f)
        assertTrue(profile.confidence >= 0.99f)
    }

    @Test
    fun `analyzer detects red overflow highlight clipping and shadow crush`() {
        val measurements = NdrNivukxCalibrationTarget.patches.map { patch ->
            val measured = when (patch.role) {
                CalibrationPatchRole.SKIN,
                CalibrationPatchRole.SATURATED_RED -> patch.referenceSrgb.copy(
                    r = (patch.referenceSrgb.r + 0.22f).coerceAtMost(1f),
                    g = (patch.referenceSrgb.g * 0.90f).coerceIn(0f, 1f),
                    b = (patch.referenceSrgb.b * 0.86f).coerceIn(0f, 1f)
                )
                CalibrationPatchRole.HIGHLIGHT -> CalibrationRgb(
                    r = 1.0f,
                    g = (patch.referenceSrgb.g + 0.10f).coerceAtMost(1f),
                    b = (patch.referenceSrgb.b * 0.78f).coerceIn(0f, 1f)
                )
                CalibrationPatchRole.SHADOW -> patch.referenceSrgb.copy(
                    r = patch.referenceSrgb.r * 0.55f,
                    g = patch.referenceSrgb.g * 0.55f,
                    b = patch.referenceSrgb.b * 0.55f
                )
                CalibrationPatchRole.SKY -> patch.referenceSrgb.copy(
                    r = (patch.referenceSrgb.r * 0.82f).coerceIn(0f, 1f),
                    g = (patch.referenceSrgb.g + 0.10f).coerceAtMost(1f),
                    b = (patch.referenceSrgb.b + 0.04f).coerceAtMost(1f)
                )
                else -> patch.referenceSrgb
            }
            CalibrationPatchMeasurement(patch.id, measured)
        }

        val profile = NdrNivukxCalibrationAnalyzer.analyze(measurements)

        assertTrue("Red overflow should reduce red scale", profile.redScale < 0.96f)
        assertTrue("Highlight clipping should increase rolloff", profile.highlightRolloffBias > 0.05f)
        assertTrue("Shadow crush should lift shadows", profile.shadowLiftBias > 0.04f)
        assertTrue("Skin drift should request correction", abs(profile.skinHueCorrectionDegrees) > 0.5f)
        assertTrue("Sky drift should request correction", abs(profile.skyHueCorrectionDegrees) > 0.5f)
        assertTrue("Saturation must remain bounded", profile.saturationScale in 0.82f..1.12f)
    }

    @Test
    fun `profile clamps unsafe measurements into safe production bounds`() {
        val measurements = NdrNivukxCalibrationTarget.patches.map { patch ->
            CalibrationPatchMeasurement(
                patch.id,
                CalibrationRgb(
                    r = if (patch.role == CalibrationPatchRole.SATURATED_RED) 1.8f else -0.2f,
                    g = if (patch.role == CalibrationPatchRole.SKY) 1.7f else -0.1f,
                    b = if (patch.role == CalibrationPatchRole.HIGHLIGHT) 1.6f else -0.4f
                )
            )
        }

        val profile = NdrNivukxCalibrationAnalyzer.analyze(measurements)

        assertTrue(profile.redScale in 0.72f..1.08f)
        assertTrue(profile.greenScale in 0.82f..1.08f)
        assertTrue(profile.blueScale in 0.82f..1.08f)
        assertTrue(profile.highlightRolloffBias in 0f..0.22f)
        assertTrue(profile.shadowLiftBias in 0f..0.18f)
        assertTrue(profile.skinHueCorrectionDegrees in -8f..8f)
        assertTrue(profile.skyHueCorrectionDegrees in -8f..8f)
        assertTrue(profile.confidence in 0f..1f)
    }
}
