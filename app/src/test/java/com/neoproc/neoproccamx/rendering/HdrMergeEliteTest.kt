package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * s4-f04 / CP35 — locks the elite still-HDR merge on the JVM via [HdrMergeReference]. Proves the
 * Debevec radiometric weighting, the ACEScg-radiance dynamic-range recovery beyond any single
 * bracket, the absence of 8-bit posterization at the 16f I/O boundary, and per-pixel deghosting.
 * No device. The live 8-bit JPEG path is unchanged (gate default OFF).
 */
class HdrMergeEliteTest {

    // sRGB-quantized capture of a true linear radiance at a given relative exposure.
    private fun capture(trueLinear: Float, exposureScale: Float): Float {
        val captured = (trueLinear * exposureScale).coerceIn(0f, 1f)
        return HdrMergeReference.linearToSrgb(captured).let { srgb ->
            val code = (srgb * 255f + 0.5f).toInt().coerceIn(0, 255); code / 255f
        }
    }

    @Test
    fun wellExposednessDownweightsClippedAndBlack() {
        val mid = HdrMergeReference.wellExposedness(0.5f)
        val black = HdrMergeReference.wellExposedness(0.0f)
        val clipped = HdrMergeReference.wellExposedness(1.0f)
        assertTrue("black must be down-weighted ($black vs $mid)", black < mid * 0.05f)
        assertTrue("clipped must be down-weighted ($clipped vs $mid)", clipped < mid * 0.05f)
    }

    @Test
    fun mergeRecoversDynamicRangeBeyondAnySingleFrame() {
        val w = 64; val h = 1
        val scales = floatArrayOf(0.03f, 1.0f, 12.0f) // deep-under, mid, over
        // True radiance: a ~16-stop log ramp from deep shadow to bright highlight.
        val trueR = FloatArray(w) { x -> exp(ln(0.0002) + (ln(20.0) - ln(0.0002)) * x / (w - 1)).toFloat() }
        val frames = scales.map { es -> FloatArray(w * 3) { i -> capture(trueR[i / 3], es) } }
        val merged = HdrMergeReference.mergeRadiance(frames, scales, w, h).rgb

        val bright = w - 1; val dark = 0
        val midEstBright = HdrMergeReference.srgbToLinear(frames[1][bright * 3]) / 1.0f // mid frame alone
        val underEstDark = HdrMergeReference.srgbToLinear(frames[0][dark * 3]) / 0.03f   // under frame alone
        // The merge sees the true high radiance the single LDR mid frame saturates away…
        assertTrue("mid frame alone saturates at the highlight ($midEstBright)", midEstBright < 1.05f)
        assertTrue("merge recovers highlight radiance > 5 (${merged[bright * 3]})", merged[bright * 3] > 5.0f)
        // …and the shadow the under frame crushes to black.
        assertTrue("under frame alone crushes the shadow ($underEstDark)", underEstDark < 1e-4f)
        assertTrue("merge recovers shadow radiance > 0 (${merged[dark * 3]})", merged[dark * 3] > 5e-5f)
    }

    @Test
    fun sixteenFloatMergeHasNoEightBitPosterization() {
        val w = 1024; val h = 1
        val scales = floatArrayOf(0.7f, 0.85f, 1.0f, 1.2f)
        val trueR = FloatArray(w) { x -> 0.10f + 0.50f * x / (w - 1) }
        val frames = scales.map { es -> FloatArray(w * 3) { i -> capture(trueR[i / 3], es) } }
        val merged = HdrMergeReference.mergeRadiance(frames, scales, w, h).rgb
        val mergedRamp = FloatArray(w) { merged[it * 3] }
        // A single 8-bit frame's recovered radiance (the old precision).
        val singleRamp = FloatArray(w) { HdrMergeReference.srgbToLinear(frames[2][it * 3]) }
        // The 8-bit OUTPUT floor re-applied to the float merge.
        val refloored = FloatArray(w) { HdrMergeReference.quantize8(mergedRamp[it]) }

        val mergedBand = ImageQualityMetrics.rampBanding(mergedRamp)
        val singleBand = ImageQualityMetrics.rampBanding(singleRamp)
        val reflooredBand = ImageQualityMetrics.rampBanding(refloored)
        // Multi-frame fusion resolves more tonal levels than one 8-bit frame…
        assertTrue("merge must beat a single 8-bit frame's tonal resolution (${mergedBand.distinctLevels} vs ${singleBand.distinctLevels})",
            mergedBand.distinctLevels > singleBand.distinctLevels)
        // …and keeping the 16f result avoids the posterization the 8-bit output floor re-introduces.
        assertTrue("8-bit output floor must posterize what the float merge resolved (${reflooredBand.bandingScore} vs ${mergedBand.bandingScore})",
            reflooredBand.bandingScore > mergedBand.bandingScore)
    }

    @Test
    fun deghostingRejectsMovingSubjectWithoutDoubleImage() {
        val w = 16; val h = 1
        val scales = floatArrayOf(1.0f, 1.0f)
        fun frame(squareStart: Int) = FloatArray(w * 3) { idx ->
            val x = (idx / 3); if (x >= squareStart && x < squareStart + 4) 0.8f else 0.2f
        }
        val frames = listOf(frame(4), frame(8)) // the bright square moved from [4,8) to [8,12)

        val ghosted = HdrMergeReference.mergeRadiance(frames, scales, w, h, deghost = false).rgb
        val deghosted = HdrMergeReference.mergeRadiance(frames, scales, w, h, deghost = true, refIndex = 0).rgb

        // The ghost region = where frame1's square is but the reference (frame0) is dark.
        var ghostMean = 0.0; var deghostMean = 0.0
        for (x in 8 until 12) { ghostMean += ghosted[x * 3]; deghostMean += deghosted[x * 3] }
        ghostMean /= 4; deghostMean /= 4
        assertTrue("deghosting must suppress the moved-subject ghost ($deghostMean vs $ghostMean)", deghostMean < 0.35 * ghostMean)
        // The real square (frame0) must survive deghosting.
        var realSquare = 0.0
        for (x in 4 until 8) realSquare += deghosted[x * 3]
        assertTrue("the reference subject must survive (${realSquare / 4})", realSquare / 4 > 0.4)
    }
}
