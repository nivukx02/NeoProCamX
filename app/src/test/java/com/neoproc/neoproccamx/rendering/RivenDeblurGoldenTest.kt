package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.rendering.RivenDeblurReference.Plane
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s3-f03 / CP53 — host golden for the Riven directional deconvolution (Path A van Cittert + Path B
 * multiplicative RL). Proves the no-downgrade guarantees: Path A's single step is reproduced exactly,
 * the [lineMin,lineMax] clamp holds at any iteration count (no ringing/halo), the Wiener noise floor
 * suppresses grain (noise-aware), and Path B sharpens like Path A in the low-noise limit without amplifying
 * grain beyond it. Fully in-repo / no device.
 */
class RivenDeblurGoldenTest {

    private val w = 64
    private val h = 24
    private val minKernelPx = 1.5f
    private val kernelLength = 6f

    /** Wiener-style gain exactly as the shader computes it (lengthGate≈1 at this kernel length). */
    private fun gain(strength: Float, snrInverse: Float): Float {
        val noiseReg = 1f / (1f + snrInverse * 4f)
        val t = ((kernelLength - minKernelPx) / (minKernelPx * 3f + 1e-4f - minKernelPx)).coerceIn(0f, 1f)
        val lengthGate = t * t * (3f - 2f * t)
        return strength * lengthGate * noiseReg
    }

    /** Smooth (already-blurred) S-curve edge along x: deblur should steepen it. */
    private fun blurredEdge(): Plane {
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = 0.5f + 0.3f * tanh((x - w / 2f) / 3f)
        return Plane(w, h, data)
    }

    /** Flat gray field + deterministic high-frequency noise (no RNG). */
    private fun flatNoisyField(amplitude: Float): Plane {
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var hsh = x * -1640531527 + y * 2654435761.toInt() + x * y * 1013904223
            hsh = hsh xor (hsh ushr 15)
            hsh *= -2048144789
            hsh = hsh xor (hsh ushr 13)
            val n = ((hsh ushr 8) and 0xFFFF).toFloat() / 0xFFFF.toFloat() - 0.5f // [-0.5,0.5]
            data[y * w + x] = 0.5f + amplitude * 2f * n
        }
        return Plane(w, h, data)
    }

    /** Peak central gradient along x across the interior (an MTF50 proxy: steeper edge = higher value). */
    private fun peakGradientX(p: Plane): Float {
        var peak = 0f
        for (y in 4 until h - 4) for (x in 4 until w - 4) {
            peak = maxOf(peak, abs(p.at(x + 1, y) - p.at(x - 1, y)) * 0.5f)
        }
        return peak
    }

    /** Standard deviation over the interior (a flat-field grain proxy). */
    private fun interiorStd(p: Plane): Float {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 4 until h - 4) for (x in 8 until w - 8) {
            val v = p.at(x, y)
            sum += v; sumSq += v.toDouble() * v; n++
        }
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    @Test
    fun pathARlOneReproducesSingleStepInverse() {
        // No-downgrade lock: one van Cittert iterate == the original single-step directional inverse.
        val g = gain(strength = 0.75f, snrInverse = 0f)
        for (test in listOf(
            floatArrayOf(0.6f, 0.4f, 0.1f, 0.9f), // center, blur, lineMin, lineMax
            floatArrayOf(0.2f, 0.5f, 0.0f, 0.7f),
            floatArrayOf(0.95f, 0.5f, 0.3f, 0.99f),
        )) {
            val (center, blur, lo, hi) = test
            val expected = (center + g * (center - blur)).coerceIn(lo, hi)
            assertEquals(expected, RivenDeblurReference.deblurPixelPathA(center, blur, lo, hi, g, 1), 1e-6f)
        }
    }

    @Test
    fun clampHoldsAtAnyIterationCountForBothPaths() {
        // Anti-ringing invariant: no iterate of EITHER path may escape the input line envelope, for any N.
        val edge = blurredEdge()
        val g = gain(strength = 1.0f, snrInverse = 0f) // aggressive gain stresses the clamp
        for (iters in intArrayOf(1, 2, 5, 20)) for (mult in booleanArrayOf(false, true)) {
            for (y in 0 until h) for (x in 0 until w) {
                val center = edge.at(x, y)
                val s = RivenDeblurReference.tapStats(edge, x, y, 1f, 0f, kernelLength)
                val out = if (mult) RivenDeblurReference.deblurPixelPathB(center, s.blurEstimate, s.lineMin, s.lineMax, g, iters)
                          else RivenDeblurReference.deblurPixelPathA(center, s.blurEstimate, s.lineMin, s.lineMax, g, iters)
                assertTrue("escaped envelope (mult=$mult,N=$iters) at ($x,$y): $out !in [${s.lineMin},${s.lineMax}]",
                    out >= s.lineMin - 1e-4f && out <= s.lineMax + 1e-4f)
            }
        }
    }

    @Test
    fun pathARecoversSharpnessOnBlurredEdge() {
        // MTF50 proxy: deblurring the blurred edge must steepen it (raise the peak gradient).
        val edge = blurredEdge()
        val g = gain(strength = 0.75f, snrInverse = 0f)
        val deblurred = RivenDeblurReference.deblurPlane(edge, 1f, 0f, kernelLength, g, iterations = 1, multiplicative = false)
        assertTrue("Path A must sharpen the edge: blurred=${peakGradientX(edge)} deblurred=${peakGradientX(deblurred)}",
            peakGradientX(deblurred) > peakGradientX(edge) * 1.05f)
    }

    @Test
    fun higherNoiseFloorReducesGrainAmplification() {
        // Noise-aware proof: raising the Wiener 1/SNR floor (a noisier frame) lowers the gain, so the
        // flat-field grain after deblur is smaller — the regularization tracks noise instead of ringing.
        val field = flatNoisyField(amplitude = 0.05f)
        val noFloor = RivenDeblurReference.deblurPlane(field, 1f, 0f, kernelLength, gain(0.75f, 0f), 1, false)
        val withFloor = RivenDeblurReference.deblurPlane(field, 1f, 0f, kernelLength, gain(0.75f, 2f), 1, false)
        assertTrue("higher noise floor must reduce grain: noFloor=${interiorStd(noFloor)} withFloor=${interiorStd(withFloor)}",
            interiorStd(withFloor) < interiorStd(noFloor))
    }

    @Test
    fun pathBSharpensLikePathAInLowNoiseLimit() {
        // Path B (multiplicative RL) must recover sharpness like Path A on a clean blurred edge.
        val edge = blurredEdge()
        val g = gain(strength = 0.75f, snrInverse = 0f)
        val a = peakGradientX(RivenDeblurReference.deblurPlane(edge, 1f, 0f, kernelLength, g, 1, false))
        val b = peakGradientX(RivenDeblurReference.deblurPlane(edge, 1f, 0f, kernelLength, g, 1, true))
        val blurred = peakGradientX(edge)
        assertTrue("Path B must sharpen the edge: blurred=$blurred pathB=$b", b > blurred * 1.05f)
        // ...and stay within a factor of Path A's recovered sharpness (they agree in the low-noise limit).
        assertTrue("Path B sharpness must track Path A: pathA=$a pathB=$b", b in 0.4f * a..2.0f * a)
    }

    @Test
    fun pathBDoesNotAmplifyGrainBeyondPathA() {
        // Path B's clamp + multiplicative form must not amplify flat-field grain beyond Path A.
        val field = flatNoisyField(amplitude = 0.05f)
        val g = gain(strength = 0.75f, snrInverse = 0.5f)
        val a = interiorStd(RivenDeblurReference.deblurPlane(field, 1f, 0f, kernelLength, g, 1, false))
        val b = interiorStd(RivenDeblurReference.deblurPlane(field, 1f, 0f, kernelLength, g, 1, true))
        assertTrue("Path B grain must not exceed Path A grain (×1.2): pathA=$a pathB=$b", b <= a * 1.2f)
    }
}
