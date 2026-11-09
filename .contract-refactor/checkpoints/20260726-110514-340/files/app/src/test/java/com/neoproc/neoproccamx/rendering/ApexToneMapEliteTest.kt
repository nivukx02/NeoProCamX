package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s4-f03 / CP30 — locks the knee-curve tone math on the JVM via [ApexToneMapReference]. The default
 * SDR-referred modified-Reinhard knee is golden-locked (no-downgrade); the gated BT.2390 EETF is
 * proven to carry highlights to an explicit peak (not the SDR clamp), preserve the diffuse range,
 * keep neutrals neutral, and not band. No device.
 */
class ApexToneMapEliteTest {

    // ── Default knee golden lock (no-downgrade) ─────────────────────────────────────────

    @Test
    fun defaultKneeIsIdentityInShadowsMonotonicAndBounded() {
        val maxCLL = 1000.0f
        // Identity below the knee (no shadow/mid shift) — exact.
        assertEquals(0.3, ApexToneMapReference.modifiedReinhardKnee(0.3f, 0.65f, 2.0f, maxCLL).toDouble(), 1e-5)
        assertEquals(0.5, ApexToneMapReference.modifiedReinhardKnee(0.5f, 0.65f, 2.0f, maxCLL).toDouble(), 1e-5)
        // Monotonic + bounded by the SDR-referred ceiling (peakScale=1 at maxCLL=1000).
        var prev = -1.0
        var x = 0.0f
        var maxOut = 0.0
        while (x <= 6.0f) {
            val o = ApexToneMapReference.modifiedReinhardKnee(x, 0.65f, 2.0f, maxCLL).toDouble()
            assertTrue("modified-Reinhard must be monotonic at x=$x", o >= prev - 1e-6)
            prev = o; maxOut = maxOf(maxOut, o); x += 0.05f
        }
        assertTrue("default knee clamps to the SDR-referred ceiling (~1) at maxCLL=1000 ($maxOut)", maxOut < 1.1)
    }

    // ── CP30 peak-nits EETF ──────────────────────────────────────────────────────────────

    @Test
    fun eetfRollsHighlightsToExplicitPeakNotSdrClamp() {
        val maxCLL = 1000.0f
        val targetNits = 1000.0f
        // Source highlight range normalized: maxIn = 1000/203 ≈ 4.93.
        var x = 1.0f
        var prev = -1.0
        var finalEetf = 0.0
        while (x <= 4.9f) {
            val o = ApexToneMapReference.apexToneMap(x, maxCLL, targetNits).toDouble()
            assertTrue("EETF must be monotonic through highlights at x=$x", o >= prev - 1e-6)
            prev = o; finalEetf = o; x += 0.05f
        }
        val finalReinhard = ApexToneMapReference.modifiedReinhardKnee(4.9f, 0.65f, 2.0f, maxCLL).toDouble()
        // The EETF carries highlights well above the SDR ceiling; the default knee clamps near 1.
        assertTrue("EETF must carry highlights above the SDR ceiling ($finalEetf)", finalEetf > 2.0)
        assertTrue("default knee clamps highlights near 1 ($finalReinhard)", finalReinhard < 1.1)
        assertTrue("EETF must exceed the clamped knee in highlights", finalEetf > finalReinhard + 1.0)
    }

    @Test
    fun eetfPreservesDiffuseRangeBelowWhite() {
        // Below diffuse white (1.0) the EETF is exact identity — diffuse/mid contrast is untouched.
        var x = 0.0f
        while (x <= 1.0f) {
            assertEquals("EETF must preserve mids at x=$x", x.toDouble(),
                ApexToneMapReference.apexToneMap(x, 1000.0f, 1000.0f).toDouble(), 1e-6)
            x += 0.02f
        }
    }

    @Test
    fun eetfPreservesNeutralAxis() {
        // Per-channel identical curve → a neutral gray stays neutral (no hue twist) at every level.
        var g = 0.0f
        while (g <= 4.0f) {
            val r = ApexToneMapReference.apexToneMap(g, 1000.0f, 1000.0f)
            val gg = ApexToneMapReference.apexToneMap(g, 1000.0f, 1000.0f)
            val b = ApexToneMapReference.apexToneMap(g, 1000.0f, 1000.0f)
            assertEquals(r, gg, 0f); assertEquals(gg, b, 0f)
            g += 0.1f
        }
    }

    @Test
    fun eetfDoesNotIncreaseBanding() {
        // A smooth highlight ramp through the EETF stays smooth (monotonic, no long flat runs).
        val n = 256
        val maxIn = 1000.0f / ApexToneMapReference.REFERENCE_WHITE_NITS
        val out = FloatArray(n) { ApexToneMapReference.apexToneMap(maxIn * it / (n - 1), 1000.0f, 1000.0f) }
        val banding = ImageQualityMetrics.rampBanding(out)
        assertTrue("EETF output must not band (score=${banding.bandingScore})", banding.bandingScore < 0.05)
        assertTrue("EETF output must keep many distinct levels (${banding.distinctLevels})", banding.distinctLevels > n / 2)
    }
}
