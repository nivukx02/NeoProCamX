package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * s4-f07 / CP23 — the STEP-0 audit that decides WIRE vs RETIRE for the test-only scaffolds
 * VulkanJointBilateralFilter.comp and VulkanLogDenoiseFilter.comp. It characterizes each filter's
 * distinct mechanism on the JVM, then records the honest verdict against the incumbent:
 *
 *   VERDICT — RETIRE BOTH. The joint-bilateral's flat-region detail-safe role is already covered by
 *   the dormant denoise.comp wavelet/guided pass (s4-f06/CP23), and the log-domain filter's
 *   tone-adaptive smoothing is exactly the variance-stabilization the live nivukx denoise gained in
 *   s4-f01/CP21 (GAT VST). Wiring either would be a redundant extra pass (lag) for no unique win, so
 *   per "do not wire a redundant pass just because it compiles" they stay retired test-only scaffolds.
 *   RETIRE is zero-live-pixel by construction: neither is compiled to SPV or dispatched.
 */
class BilateralFilterAuditTest {

    private fun gaussianSeq(n: Int, seed: Long): DoubleArray {
        var s = seed
        fun u(): Double { s = s * 6364136223846793005L + 1442695040888963407L; return (s ushr 11).toDouble() / (1L shl 53).toDouble() }
        val out = DoubleArray(n); var i = 0
        while (i < n) {
            val u1 = u().coerceIn(1e-12, 1.0); val u2 = u(); val r = sqrt(-2.0 * ln(u1))
            out[i] = r * cos(2.0 * Math.PI * u2); if (i + 1 < n) out[i + 1] = r * sin(2.0 * Math.PI * u2); i += 2
        }
        return out
    }

    // ── Characterize the joint-bilateral's distinct mechanism (guide-steered denoise) ────

    @Test
    fun jointBilateralDenoisesFlatsWhileTheGuideStopsEdgeBleed() {
        val w = 32; val h = 32; val edge = 16
        val noise = gaussianSeq(w * h, 11L)
        val guide = FloatArray(w * h) { if ((it % w) < edge) 0.2f else 0.8f }
        val input = FloatArray(w * h) { (guide[it] + 0.04 * noise[it]).toFloat() }
        val out = FloatArray(w * h) { i -> JointBilateralLogDenoiseReference.jointBilateral(input, guide, w, h, i % w, i / w) }

        // Left flat region SNR (cols 2..13) must improve (the grain is denoised)…
        fun patch(img: FloatArray): FloatArray {
            val p = ArrayList<Float>()
            for (y in 0 until h) for (x in 2 until 14) p.add(img[y * w + x])
            return p.toFloatArray()
        }
        val snrBefore = ImageQualityMetrics.flatFieldSnrDb(patch(input))
        val snrAfter = ImageQualityMetrics.flatFieldSnrDb(patch(out))
        assertTrue("joint-bilateral must denoise the flat ($snrAfter vs $snrBefore)", snrAfter > snrBefore)

        // …while the guide edge stops bleed: the 0.6 step survives.
        var left = 0.0; var right = 0.0
        for (y in 4 until h - 4) { for (x in 2 until 14) left += out[y * w + x]; for (x in 18 until 30) right += out[y * w + x] }
        val contrast = right / ((h - 8) * 12) - left / ((h - 8) * 12)
        assertTrue("the guide must stop bleed across the edge ($contrast)", contrast > 0.5)
    }

    @Test
    fun logDenoiseSigmaIsToneAdaptiveLikeAVst() {
        // Larger range sigma in shadows than highlights = the variance-stabilization principle the
        // live nivukx denoise already gained via the GAT VST (s4-f01/CP21) → log filter is subsumed.
        val shadow = JointBilateralLogDenoiseReference.logDynamicSigmaR(0.1f)
        val highlight = JointBilateralLogDenoiseReference.logDynamicSigmaR(0.9f)
        assertTrue("log filter smooths shadows more than highlights ($shadow vs $highlight)", shadow > highlight)
    }

    // ── The RETIRE verdict, verified against the incumbent ────────────────────────────────

    @Test
    fun retireRationaleIsVerifiedAgainstTheLivePath() {
        val nivukx = File("src/main/cpp/shaders/vulkan/nivukx_denoise.comp").readText()
        val denoise = File("src/main/cpp/shaders/vulkan/denoise.comp").readText()
        // The log filter's job (tone-adaptive/variance-stabilized smoothing) is now in the live shader…
        assertTrue("s4-f01 added the GAT VST that subsumes the log filter", nivukx.contains("gatForwardScalar"))
        // …and the joint-bilateral's flat-region detail-safe job is in the dormant denoise.comp (s4-f06).
        assertTrue("denoise.comp wavelet/guided covers the flat-region role", denoise.contains("waveletDenoise") && denoise.contains("guidedFilter"))
    }

    @Test
    fun retireIsZeroLivePixelByConstruction() {
        val bridge = File("src/main/cpp/vulkan_bridge.cpp").readText()
        // Neither scaffold is compiled to SPV or referenced by the native bridge — they never touched
        // a pixel, so retiring them cannot change live output.
        assertFalse(bridge.contains("VulkanJointBilateralFilter"))
        assertFalse(bridge.contains("VulkanLogDenoiseFilter"))
        assertFalse("no SPV header was ever generated for the joint filter",
            File("src/main/cpp/generated/VulkanJointBilateralFilter_comp_spv.h").exists())
        // The scaffolds + their contract tests remain as documented, retired test-only references.
        assertTrue(File("src/main/cpp/shaders/vulkan/VulkanJointBilateralFilter.comp").exists())
        assertTrue(File("src/main/cpp/shaders/vulkan/VulkanLogDenoiseFilter.comp").exists())
    }
}
