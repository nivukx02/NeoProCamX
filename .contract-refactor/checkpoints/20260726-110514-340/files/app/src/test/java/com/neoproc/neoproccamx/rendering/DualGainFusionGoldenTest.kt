package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CP31 / s1-f10 — golden lock for NeoProCamX's dual-conversion-gain fusion ([DualGainFusionReference],
 * the line-for-line host port of dual_gain_fusion.comp). Proves the elite dynamic-range properties on
 * the host, NO device:
 *   §4.2 measurable DR gain — the fused frame recovers highlight detail clipped in the HIGH-gain read,
 *        and keeps the shadow from the cleaner read (criterion: both regions improve),
 *   §4.3 SNR-optimal weighting — the per-pixel blend weight tracks σ²(signal) from the s1-f03 model,
 *   §4.5 neutral preserved (achromatic in → achromatic out),
 *   no-vendor — the shader carries no third-party HDR token.
 */
class DualGainFusionGoldenTest {

    private val kGain = 8f
    private val hcgClip = 1.0f // HCG (high conversion gain) saturates early — highlights are lost here.

    private fun params() = DualGainFusionReference.Params(
        kGain = kGain, noiseFloor = 0.01f, saturationThreshold = 0.90f, shadowStretch = 0.10f
    )

    /** Flat neutral scene of radiance [s]: HCG = min(s, clip) (clips highlights), LCG = s/kGain. */
    private fun flatScene(s: Float, w: Int = 16, h: Int = 8): Pair<DualGainFusionReference.Image, DualGainFusionReference.Image> {
        val lcgV = s / kGain
        val hcgV = min(s, hcgClip)
        val lcg = FloatArray(w * h * 3) { lcgV }
        val hcg = FloatArray(w * h * 3) { hcgV }
        return DualGainFusionReference.Image(w, h, lcg) to DualGainFusionReference.Image(w, h, hcg)
    }

    private fun luma(rgb: FloatArray, i: Int): Float {
        val o = i * 3
        return rgb[o] * 0.272229f + rgb[o + 1] * 0.674082f + rgb[o + 2] * 0.053689f
    }

    private fun interiorIndex(w: Int, h: Int) = (h / 2) * w + (w / 2)

    private fun fuseFlat(s: Float): FloatArray {
        val (lcg, hcg) = flatScene(s)
        return DualGainFusionReference.fuse(lcg, hcg, params()).fused
    }

    // ──────────────────────────── §4.2 highlight recovery + detail ────────────────────────────

    @Test
    fun fusedRecoversHighlightDetailClippedInTheHighGainRead() {
        val w = 16; val h = 8
        val idx = interiorIndex(w, h)

        // Two bright scenes the HCG read CANNOT distinguish (both clip to 1.0), but the LCG read holds.
        val dim = fuseFlat(1.6f)
        val bright = fuseFlat(2.8f)

        val hcgLumaDim = min(1.6f, hcgClip)          // = 1.0 (clipped)
        val hcgLumaBright = min(2.8f, hcgClip)        // = 1.0 (clipped) — HCG lost the distinction
        assertEquals("HCG clips both highlights to the same value (detail lost)", hcgLumaDim, hcgLumaBright, 1e-6f)

        val fusedDim = luma(dim, idx)
        val fusedBright = luma(bright, idx)
        // Recovered ABOVE the HCG clip, and the two highlight levels are kept DISTINCT (detail recovered).
        assertTrue("fused must exceed the HCG clip (got $fusedDim)", fusedDim > 1.2f)
        assertTrue("fused must exceed the HCG clip (got $fusedBright)", fusedBright > 1.2f)
        assertTrue("brighter highlight must stay brighter after fusion ($fusedBright vs $fusedDim)",
            fusedBright > fusedDim + 0.5f)
    }

    // ──────────────────────────── §4.2 shadow from the cleaner read ────────────────────────────

    @Test
    fun fusedKeepsShadowFromTheCleanerHighGainRead() {
        val w = 16; val h = 8
        val idx = interiorIndex(w, h)
        val s = 0.06f
        val fused = fuseFlat(s)
        val fusedLuma = luma(fused, idx)
        // In the shadow both reads agree on radiance; the fused result must track it (no crush, no boost).
        assertEquals("shadow radiance must be preserved", s, fusedLuma, 0.02f)

        // The SNR-optimal policy weight (s1-f03 noise model) must put the shadow on the cleaner HCG read.
        val model = DualGainNoiseModel(lcgNoiseS = 0.0012f, lcgNoiseO = 0.00002f, hcgNoiseS = 0.0090f, hcgNoiseO = 0.00001f)
        val shadowWeight = DualGainFusionPolicy.snrOptimalHcgWeight(s, kGain, model)
        assertTrue("shadow must be weighted to the cleaner HCG read (w=$shadowWeight)", shadowWeight > 0.5f)
    }

    // ──────────────────────────── §4.3 SNR-optimal weight tracks σ² ────────────────────────────

    @Test
    fun snrOptimalWeightTracksReadVariance() {
        val model = DualGainNoiseModel(lcgNoiseS = 0.0012f, lcgNoiseO = 0.00002f, hcgNoiseS = 0.0090f, hcgNoiseO = 0.00001f)

        // readVariance is the affine σ²(signal) = noiseS·signal + noiseO — strictly increasing in signal.
        assertTrue(DualGainFusionPolicy.readVariance(0.5f, 0.01f, 0.001f) >
            DualGainFusionPolicy.readVariance(0.1f, 0.01f, 0.001f))

        // HCG weight decreases from shadows to highlights as HCG variance grows relative to LCG-equalized.
        var prev = Float.MAX_VALUE
        for (s in listOf(0.02f, 0.1f, 0.4f, 1.0f, 2.0f)) {
            val wgt = DualGainFusionPolicy.snrOptimalHcgWeight(s, kGain, model)
            assertTrue("weight must be in [0,1]", wgt in 0f..1f)
            assertTrue("HCG weight must be (weakly) decreasing toward highlights at s=$s ($wgt !<= $prev)",
                wgt <= prev + 1e-4f)
            prev = wgt
        }
        // Inverse-variance: the lower-variance read always receives the majority weight.
        assertTrue(DualGainFusionPolicy.prefersLowerVariance(0.001f, 0.010f))
        assertTrue(DualGainFusionPolicy.prefersLowerVariance(0.050f, 0.004f))
    }

    // ──────────────────────────── §4.5 neutral preservation ────────────────────────────

    @Test
    fun fusionPreservesNeutralAcrossRange() {
        val w = 16; val h = 8
        val idx = interiorIndex(w, h)
        for (s in listOf(0.05f, 0.3f, 0.8f, 1.6f, 2.8f)) {
            val fused = fuseFlat(s)
            val o = idx * 3
            val spread = max(max(abs(fused[o] - fused[o + 1]), abs(fused[o + 1] - fused[o + 2])), abs(fused[o] - fused[o + 2]))
            assertTrue("neutral input must stay achromatic at s=$s (spread=$spread)", spread < 1e-4f)
            assertTrue("fused radiance must be positive at s=$s", fused[o + 1] > 0f)
        }
    }

    // ──────────────────────────── no-vendor ────────────────────────────

    @Test
    fun dualGainShaderAndPolicyCarryNoVendorHdrToken() {
        val shader = File("src/main/cpp/shaders/vulkan/dual_gain_fusion.comp").readText()
        val policy = File("src/main/java/com/neoproc/neoproccamx/rendering/DualGainFusionPolicy.kt").readText()
        listOf("Sony", "OmniVision", "Dolby", "HDR10", "ARRI", "RED ").forEach { vendor ->
            assertFalse("dual_gain_fusion.comp must not reference '$vendor'", shader.contains(vendor))
            assertFalse("DualGainFusionPolicy must not reference '$vendor'", policy.contains(vendor))
        }
    }
}
