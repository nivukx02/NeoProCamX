package com.neoproc.neoproccamx.rendering

import kotlin.math.exp
import kotlin.math.max

/**
 * s4-f03 / CP30 — host-side faithful reference of knee_curve.comp: the default SDR-referred
 * modified-Reinhard knee (today's path) and the gated BT.2390-style peak-nits EETF. Ports the
 * shader math line-for-line so [ApexToneMapEliteTest] can lock both on the JVM with NO device.
 */
object ApexToneMapReference {

    const val DEFAULT_MAX_CLL = 1000.0f
    const val REFERENCE_WHITE_NITS = 203.0f
    const val DEFAULT_TARGET_PEAK_NITS = 1000.0f

    private fun clamp(x: Float, lo: Float, hi: Float) = x.coerceIn(lo, hi)

    fun adaptiveSigmoidKneeWeight(value: Float, kneePoint: Float, kneeHardness: Float): Float {
        val safeKnee = clamp(kneePoint, 0.55f, 0.75f)
        val safeHardness = clamp(kneeHardness, 1.0f, 3.0f)
        val t = clamp((value - safeKnee) / max(1.0f - safeKnee, 0.00001f), 0.0f, 1.0f)
        val centered = (t - 0.5f) * safeHardness * 4.0f
        val sigmoid = 1.0f / (1.0f + exp(-centered))
        val minSigmoid = 1.0f / (1.0f + exp(0.5f * safeHardness * 4.0f))
        val maxSigmoid = 1.0f / (1.0f + exp(-0.5f * safeHardness * 4.0f))
        return clamp((sigmoid - minSigmoid) / max(maxSigmoid - minSigmoid, 0.00001f), 0.0f, 1.0f)
    }

    /** Today's default knee — the no-downgrade path the golden lock freezes. */
    fun modifiedReinhardKnee(channel: Float, kneePoint: Float, kneeHardness: Float, maxCLL: Float): Float {
        val x = max(channel, 0.0f)
        val safeKnee = clamp(kneePoint, 0.55f, 0.75f)
        val safeHardness = clamp(kneeHardness, 1.0f, 3.0f)
        val safeMaxCLL = max(maxCLL, REFERENCE_WHITE_NITS)
        val peakScale = clamp(safeMaxCLL / DEFAULT_MAX_CLL, 0.25f, 4.0f)
        val headroom = max(peakScale - safeKnee, 0.00001f)
        val over = max(x - safeKnee, 0.0f)
        val reinhard = safeKnee + (over * headroom) / (over + headroom / safeHardness)
        val weight = adaptiveSigmoidKneeWeight(x, safeKnee, safeHardness)
        return x + (reinhard - x) * weight // mix(x, reinhard, weight)
    }

    /** CP30 BT.2390 Hermite shoulder — carries highlights to an explicit peak instead of clamping. */
    fun bt2390Eetf(x: Float, ks: Float, maxIn: Float, maxOut: Float): Float {
        if (x <= ks) return max(x, 0.0f)
        val t = clamp((x - ks) / max(maxIn - ks, 0.00001f), 0.0f, 1.0f)
        val t2 = t * t
        val t3 = t2 * t
        return (2.0f * t3 - 3.0f * t2 + 1.0f) * ks +
            (t3 - 2.0f * t2 + t) * (maxIn - ks) +
            (-2.0f * t3 + 3.0f * t2) * maxOut
    }

    /** The full peak-nits ApexToneMapper path (gate ON), mirroring the shader's parameter derivation. */
    fun apexToneMap(x: Float, maxCLL: Float, targetPeakNits: Float = DEFAULT_TARGET_PEAK_NITS): Float {
        val ks = 1.0f
        val safeMaxCLL = max(maxCLL, REFERENCE_WHITE_NITS)
        val maxInN = max(safeMaxCLL / REFERENCE_WHITE_NITS, ks + 0.5f)
        val targetN = if (targetPeakNits > 0.0f) targetPeakNits else DEFAULT_TARGET_PEAK_NITS
        val maxOutN = clamp(targetN / REFERENCE_WHITE_NITS, ks + 0.25f, maxInN)
        return bt2390Eetf(x, ks, maxInN, maxOutN)
    }
}
