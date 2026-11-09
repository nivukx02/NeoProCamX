package com.neoproc.neoproccamx.rendering

import kotlin.math.exp

/**
 * s4-f07 / CP23 — host-side ports of the two test-only scaffolds VulkanJointBilateralFilter.comp and
 * VulkanLogDenoiseFilter.comp, so [BilateralFilterAuditTest] can run the must-beat-the-incumbent
 * audit on the JVM and record the WIRE/RETIRE numbers. Single-channel (luma) ports of the 5x5 kernels.
 */
object JointBilateralLogDenoiseReference {

    const val SIGMA_S = 2.4f
    const val JOINT_SIGMA_R = 0.082f
    const val LOG_BASE_SIGMA_R = 0.045f
    const val LOG_ALPHA = 1.85f

    private fun at(img: FloatArray, w: Int, h: Int, x: Int, y: Int) =
        img[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    private fun gaussian(d2: Float, sigma: Float) = exp(-d2 / (2f * sigma * sigma))

    /** Cross/joint bilateral: the separate [guide] steers the range weight (denoise input, keep guide edges). */
    fun jointBilateral(input: FloatArray, guide: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cg = at(guide, w, h, x, y)
        var sum = 0f; var wsum = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val sc = at(input, w, h, x + dx, y + dy)
            val sg = at(guide, w, h, x + dx, y + dy)
            val sp = gaussian((dx * dx + dy * dy).toFloat(), SIGMA_S)
            val rg = gaussian((sg - cg) * (sg - cg), JOINT_SIGMA_R)
            sum += sc * sp * rg; wsum += sp * rg
        }
        return sum / maxOf(wsum, 1e-6f)
    }

    /** Log-domain range sigma: larger in shadows (where noise is relatively larger) — a VST-like idea. */
    fun logDynamicSigmaR(centerGuide: Float): Float = LOG_BASE_SIGMA_R * (1f + LOG_ALPHA * (1f - centerGuide))

    fun logDenoise(input: FloatArray, guide: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cg = at(guide, w, h, x, y)
        val dynSigmaR = logDynamicSigmaR(cg)
        var sum = 0f; var wsum = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val sc = at(input, w, h, x + dx, y + dy)
            val sg = at(guide, w, h, x + dx, y + dy)
            val sp = gaussian((dx * dx + dy * dy).toFloat(), SIGMA_S)
            val rg = gaussian((sg - cg) * (sg - cg), dynSigmaR)
            sum += sc * sp * rg; wsum += sp * rg
        }
        return sum / maxOf(wsum, 1e-6f)
    }
}
