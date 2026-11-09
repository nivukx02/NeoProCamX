package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Host-golden for the hue-preserving highlight knee application in knee_curve.comp.
 *
 * The shader previously applied modifiedReinhardKnee independently to R, G and B.
 * On a saturated highlight (e.g. a clipping red channel) that pulled one channel
 * down alone, desaturating and twisting hue (audit rank 7). The fix drives the
 * knee from the max RGB channel and scales all channels equally, so hue is
 * invariant while the clipping channel is still compressed. The knee CURVE
 * function (modifiedReinhardKnee) is unchanged — only the application. These
 * goldens lock the hue invariance and neutral parity.
 */
class KneeCurveHuePreservingTest {

    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun adaptiveSigmoidKneeWeight(value: Double, kneePoint: Double, kneeHardness: Double): Double {
        val safeKnee = kneePoint.coerceIn(0.55, 0.75)
        val safeHardness = kneeHardness.coerceIn(1.0, 3.0)
        val t = ((value - safeKnee) / maxOf(1.0 - safeKnee, 1e-5)).coerceIn(0.0, 1.0)
        val centered = (t - 0.5) * safeHardness * 4.0
        val sigmoid = 1.0 / (1.0 + exp(-centered))
        val zeroPoint = 1.0 / (1.0 + exp(0.5 * safeHardness * 4.0))
        val onePoint = 1.0 / (1.0 + exp(-0.5 * safeHardness * 4.0))
        return ((sigmoid - zeroPoint) / maxOf(onePoint - zeroPoint, 1e-5)).coerceIn(0.0, 1.0)
    }

    private fun knee(channel: Double, kneePoint: Double, kneeHardness: Double, maxCllNits: Double): Double {
        val x = maxOf(channel, 0.0)
        val safeKnee = kneePoint.coerceIn(0.55, 0.75)
        val safeHardness = kneeHardness.coerceIn(1.0, 3.0)
        val safeMaxCll = maxOf(maxCllNits, 203.0)
        val peakScale = (safeMaxCll / 1000.0).coerceIn(0.25, 4.0)
        val kneeHeadroom = maxOf(peakScale - safeKnee, 1e-5)
        val over = maxOf(x - safeKnee, 0.0)
        val reinhard = safeKnee + (over * kneeHeadroom) / (over + kneeHeadroom / safeHardness)
        val weight = adaptiveSigmoidKneeWeight(x, safeKnee, safeHardness)
        return mix(x, reinhard, weight)
    }

    private val kp = 0.65
    private val kh = 2.0
    private val mc = 1000.0

    /** The fixed shader application: max-RGB drive, uniform scale. */
    private fun huePreserving(rgb: DoubleArray): DoubleArray {
        val drive = maxOf(rgb[0], maxOf(rgb[1], rgb[2]))
        val mapped = knee(drive, kp, kh, mc)
        val scale = if (drive > 1e-6) mapped / drive else 1.0
        return DoubleArray(3) { rgb[it] * scale }
    }

    /** The old per-channel application. */
    private fun perChannel(rgb: DoubleArray): DoubleArray =
        DoubleArray(3) { knee(rgb[it], kp, kh, mc) }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        return dot / (na * nb + 1e-12)
    }

    @Test
    fun neutralMatchesPerChannel() {
        // On a neutral grey the two are identical (drive == every channel).
        val n = doubleArrayOf(0.8, 0.8, 0.8)
        val hp = huePreserving(n)
        val pc = perChannel(n)
        for (c in 0..2) assertEquals(pc[c], hp[c], 1e-9)
    }

    @Test
    fun saturatedHighlightKeepsHueAndCompresses() {
        val saturated = doubleArrayOf(1.4, 0.2, 0.1)
        val hp = huePreserving(saturated)
        // Hue (color-vector direction) is invariant under the uniform scale.
        assertTrue("hue twisted: cos=${cosine(hp, saturated)}", cosine(hp, saturated) > 0.999999)
        // The clipping channel is still compressed below its input.
        assertTrue("max channel not compressed", hp[0] < saturated[0])
        // The old per-channel path measurably twisted hue — proves the fix matters.
        assertTrue("per-channel should twist hue", cosine(perChannel(saturated), saturated) < 0.999)
    }

    @Test
    fun multipleSaturatedHuesHeld() {
        for (rgb in listOf(
            doubleArrayOf(0.2, 1.3, 0.15),
            doubleArrayOf(0.1, 0.25, 1.5),
            doubleArrayOf(1.2, 0.9, 0.2)
        )) {
            assertTrue(cosine(huePreserving(rgb), rgb) > 0.999999)
        }
    }
}
