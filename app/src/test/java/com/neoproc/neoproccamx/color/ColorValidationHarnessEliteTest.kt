package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * s2-f12 / CP-Q4 — CI-mandatory regression lock for the REAL color-pipeline QC harness. Pins the
 * ColorValidationReport metrics to analytic constants (any weakening of the real transform trips it),
 * extends the memory-colour bounds to sky/foliage, exercises the real processForOutput over a neutral
 * ramp, and guards against committing binary golden media. No @Ignore/Assume anywhere.
 */
class ColorValidationHarnessEliteTest {

    // REGRESSION LOCK: the report metrics are frozen to constants (computed from the real pipeline).
    @Test
    fun `color validation report metrics are pinned to the real pipeline values`() {
        val r = ColorValidationHarness.validateNeoGamutApex()
        // Pinned to the real pipeline's output — orders of magnitude below the acceptance gates, so
        // any weakening of the actual transform (not just a gate breach) trips this lock.
        assertEquals("meanDeltaE2000", 2.2649727E-5f, r.meanDeltaE2000, 1e-6f)
        assertEquals("maxDeltaE2000", 8.9406785E-5f, r.maxDeltaE2000, 1e-6f)
        assertEquals("maxNeutralDeltaUv", 6.143906E-8f, r.maxNeutralDeltaUv, 1e-8f)
        assertEquals("maxSkinHueDriftDegrees", 2.0f, r.maxSkinHueDriftDegrees, 1e-3f)
        assertEquals("maxFrameToFrameDeltaUv", 5.4170843E-4f, r.maxFrameToFrameDeltaUv, 1e-6f)
        assertEquals("maxSensorToAcesNeutralSpread", 3.5762787E-7f, r.maxSensorToAcesNeutralSpread, 1e-7f)
        assertTrue("pipelineV1BitMatchesLegacy", r.pipelineV1BitMatchesLegacy)
    }

    // MEMORY COLOUR: skin/sky/foliage anchors all bound a proposed hue to within the max drift.
    @Test
    fun `memory-colour hue protection bounds skin, sky and foliage`() {
        val drift = ApexColorConfig.MEMORY_HUE_MAX_DRIFT_DEGREES
        data class Case(val cls: NeoGamutMemoryClass, val anchor: Float, val outside: Float)
        listOf(
            Case(NeoGamutMemoryClass.SKIN, 50f, 70f),
            Case(NeoGamutMemoryClass.SKY, 262f, 292f),
            Case(NeoGamutMemoryClass.FOLIAGE, 140f, 170f)
        ).forEach { c ->
            val protectedHue = NeoGamutApexPipeline.protectMemoryHue(c.anchor, c.outside, c.cls)
            assertTrue("${c.cls} clamps within ±$drift of the anchor",
                abs(shortest(protectedHue, c.anchor)) <= drift + 1e-3f)
            // a hue NOT in the memory window is left at the proposal (no spurious protection)
            val unprotected = NeoGamutApexPipeline.protectMemoryHue(c.anchor + 120f, c.outside, c.cls)
            assertEquals("${c.cls} leaves a non-member hue alone", wrap(c.outside), unprotected, 1e-3f)
        }
    }

    // REAL PIXELS: a neutral ramp through the production processForOutput stays on the neutral axis.
    @Test
    fun `a neutral ramp through the real output transform stays neutral`() {
        val grays = listOf(0.05f, 0.18f, 0.35f, 0.5f, 0.7f, 0.9f)
        val v2 = ColorValidationHarness.maxNeutralSpreadThroughOutput(grays, NeoGamutPipeline.V2_APEX, NeoWideGamut2.TargetGamut.REC2020)
        val v1 = ColorValidationHarness.maxNeutralSpreadThroughOutput(grays, NeoGamutPipeline.V1, NeoWideGamut2.TargetGamut.REC2020)
        assertTrue("V2 neutral spread $v2 must be ~0", v2 <= 0.01f)
        assertTrue("V1 neutral spread $v1 must be ~0", v1 <= 0.01f)
    }

    // NO-VENDOR: deterministic synthetic goldens only; no committed binary golden media.
    @Test
    fun `no binary golden media is committed`() {
        val goldenDirs = listOf("app/src/test/resources/color-goldens", "../app/src/test/resources/color-goldens")
            .map { File(it) }.filter { it.exists() }
        val binExt = setOf("dng", "raw", "npraw", "mov", "mp4", "yuv", "p010", "png", "jpg", "jpeg")
        goldenDirs.forEach { dir ->
            val bins = dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in binExt }.toList()
            assertTrue("NO-VENDOR: unexpected committed golden media $bins", bins.isEmpty())
        }
    }

    private fun wrap(d: Float): Float { var h = d % 360f; if (h < 0f) h += 360f; return h }
    private fun shortest(a: Float, b: Float): Float { var d = (a - b) % 360f; if (d > 180f) d -= 360f; if (d < -180f) d += 360f; return d }
}
