package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * s1-f07 / CP10c — golden lock for the RAW-preview cinema-debayer math ([RawDemosaicReference], a
 * line-for-line port of demosaic_vulkan.comp). Proves the elite-spec algorithm properties on the host,
 * with NO device:
 *   - a neutral patch stays achromatic in ACEScg (ΔE2000 < 1.0) through the CP11 forward matrices,
 *   - green is edge-directed (CP14): on a horizontal edge the carrier is interpolated ALONG the edge,
 *   - edge-directed green has no zippering/maze where bilinear does,
 *   - the edge-directed demosaic resolves finer detail than bilinear (higher MTF).
 */
class RawDemosaicGoldenTest {

    private val whiteLevel = 1023
    private val pattern = 0 // RGGB

    // ──────────────────────────── helpers ────────────────────────────

    private fun mosaic(w: Int, h: Int, luma: (Int, Int) -> Int): RawDemosaicReference.Mosaic {
        val raw = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) raw[y * w + x] = luma(x, y).coerceIn(0, 65535)
        return RawDemosaicReference.Mosaic(w, h, raw)
    }

    private fun identityWbParams(mSensorToXyz: FloatArray, mXyzToAcescg: FloatArray) =
        RawDemosaicReference.Params(
            bayerPattern = pattern,
            whiteLevel = whiteLevel,
            blackLevelRggb = floatArrayOf(0f, 0f, 0f, 0f),
            whiteBalanceRggb = floatArrayOf(1f, 1f, 1f, 1f),
            mSensorToXyz = mSensorToXyz,
            mXyzToAcescg = mXyzToAcescg
        )

    private val identity3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // A real published XYZ(D60)→ACEScg(AP1) matrix, used here purely as a golden fixture: it proves
    // neutrality survives a genuine non-identity colour transform. The PRODUCTION second leg is
    // NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix() (s1-f09); this constant is test-local only.
    private val acesXyzToAcesCgFixture = floatArrayOf(
        1.6410234f, -0.3248033f, -0.2364247f,
        -0.6636629f, 1.6153316f, 0.0167563f,
        0.0117219f, -0.0082844f, 0.9883949f
    )

    private fun invert3x3(m: FloatArray): FloatArray {
        val a = DoubleArray(9) { m[it].toDouble() }
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[2] * a[7] - a[1] * a[8]
        val c02 = a[1] * a[5] - a[2] * a[4]
        val c10 = a[5] * a[6] - a[3] * a[8]
        val c11 = a[0] * a[8] - a[2] * a[6]
        val c12 = a[2] * a[3] - a[0] * a[5]
        val c20 = a[3] * a[7] - a[4] * a[6]
        val c21 = a[1] * a[6] - a[0] * a[7]
        val c22 = a[0] * a[4] - a[1] * a[3]
        val det = a[0] * c00 + a[1] * c10 + a[2] * c20
        val inv = 1.0 / det
        return floatArrayOf(
            (c00 * inv).toFloat(), (c01 * inv).toFloat(), (c02 * inv).toFloat(),
            (c10 * inv).toFloat(), (c11 * inv).toFloat(), (c12 * inv).toFloat(),
            (c20 * inv).toFloat(), (c21 * inv).toFloat(), (c22 * inv).toFloat()
        )
    }

    private fun mul3(m: FloatArray, v0: Double, v1: Double, v2: Double): DoubleArray = doubleArrayOf(
        m[0] * v0 + m[1] * v1 + m[2] * v2,
        m[3] * v0 + m[4] * v1 + m[5] * v2,
        m[6] * v0 + m[7] * v1 + m[8] * v2
    )

    // ──────────────────────────── 1. neutral preserved (ΔE2000) ────────────────────────────

    @Test
    fun neutralPatchStaysAchromaticInAcesCgWithinDeltaE2000() {
        val w = 32
        val h = 32
        val gray = 512
        val m = mosaic(w, h) { _, _ -> gray }

        // M_XYZ_TO_ACESCG = the production constant; M_SENSOR_TO_XYZ = its numeric inverse so the
        // combined sensor→ACEScg transform is identity (sensor primaries assumed ACEScg).
        val xyzToAces = acesXyzToAcesCgFixture
        val sensorToXyz = invert3x3(xyzToAces)
        val params = identityWbParams(sensorToXyz, xyzToAces)

        val aces = RawDemosaicReference.demosaicAcesCg(m, params)

        // Sample an interior pixel (away from clamp edges) — the field is flat so any interior pixel works.
        val cx = w / 2
        val cy = h / 2
        val o = (cy * w + cx) * 3
        val r = aces[o].toDouble()
        val g = aces[o + 1].toDouble()
        val b = aces[o + 2].toDouble()

        // Strong, white-independent achromaticity: channels equal.
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue("ACEScg neutral spread too large: $spread (rgb=$r,$g,$b)", spread < 1e-3)

        // Lab reference white = ACEScg neutral axis, so a true neutral has a*=b*=0.
        val acescgToXyz = invert3x3(xyzToAces)
        val whiteXyz = mul3(acescgToXyz, 1.0, 1.0, 1.0)
        val labOut = xyzToLab(mul3(acescgToXyz, r, g, b), whiteXyz)
        // Ideal neutral of the same input value through the same pipeline.
        val cVal = gray.toDouble() / whiteLevel.toDouble()
        val labIdeal = xyzToLab(mul3(acescgToXyz, cVal, cVal, cVal), whiteXyz)

        assertTrue("neutral a* not ~0: ${labOut[1]}", abs(labOut[1]) < 0.5)
        assertTrue("neutral b* not ~0: ${labOut[2]}", abs(labOut[2]) < 0.5)
        val dE = ciede2000(labOut, labIdeal)
        assertTrue("neutral patch ΔE2000 must be < 1.0 but was $dE", dE < 1.0)
    }

    @Test
    fun whiteBalanceAmplifiedHighlightsRetainFp16Headroom() {
        val m = mosaic(8, 8) { _, _ -> whiteLevel }
        val params = RawDemosaicReference.Params(
            bayerPattern = pattern,
            whiteLevel = whiteLevel,
            blackLevelRggb = floatArrayOf(0f, 0f, 0f, 0f),
            whiteBalanceRggb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.25f),
            mSensorToXyz = identity3,
            mXyzToAcescg = identity3
        )

        val sensorRgb = RawDemosaicReference.demosaicSensorRgb(m, params)
        val redSite = (4 * 8 + 4) * 3
        assertTrue("post-WB red highlight must survive above 1.0", sensorRgb[redSite] > 1.5f)
        assertTrue("negative interpolation lobes remain clamped", sensorRgb.all { it >= 0f })
    }

    // ──────────────────────────── 2. edge-directed green (CP14) ────────────────────────────

    @Test
    fun greenIsInterpolatedAlongAHorizontalEdge() {
        val w = 24
        val h = 16
        val lo = 100
        val hi = 900
        // Horizontal edge: luma varies only in y (constant in x), boundary at y = h/2.
        val m = mosaic(w, h) { _, y -> if (y < h / 2) lo else hi }
        val params = identityWbParams(identity3, identity3)

        val x = 8
        val y = h / 2 // even row → R site under RGGB
        assertEquals("test setup: expected a Red site", 0, RawDemosaicReference.bayerChannel(x, y, pattern))

        val center = RawDemosaicReference.calibratedRaw(m, params, x, y)
        val hGrad = RawDemosaicReference.horizontalGradient(m, params, x, y, center)
        val vGrad = RawDemosaicReference.verticalGradient(m, params, x, y, center)
        // Along the edge (horizontal) the signal is constant → hGrad ≈ 0 ≪ vGrad.
        assertTrue("edge should be detected as vertical-gradient dominant: h=$hGrad v=$vGrad", hGrad < vGrad)

        // The shader must interpolate green ALONG the edge (gH), not across it (gV).
        val gH = 0.5f * (RawDemosaicReference.calibratedRaw(m, params, x - 1, y) +
            RawDemosaicReference.calibratedRaw(m, params, x + 1, y)) +
            0.25f * (2f * center - RawDemosaicReference.calibratedRaw(m, params, x - 2, y) -
                RawDemosaicReference.calibratedRaw(m, params, x + 2, y))
        val gV = 0.5f * (RawDemosaicReference.calibratedRaw(m, params, x, y - 1) +
            RawDemosaicReference.calibratedRaw(m, params, x, y + 1)) +
            0.25f * (2f * center - RawDemosaicReference.calibratedRaw(m, params, x, y - 2) -
                RawDemosaicReference.calibratedRaw(m, params, x, y + 2))
        val green = RawDemosaicReference.reconstructGreen(m, params, x, y, center)

        assertEquals("green must equal the along-edge interpolation gH", gH, green, 1e-5f)
        assertTrue("along-edge and across-edge interpolation must actually differ", abs(gH - gV) > 0.05f)
        // gH equals the true (constant-in-x) value at this row; gV is the cross-edge blur.
        assertEquals("along-edge green should recover the row value", center, green, 1e-5f)
    }

    // ──────────────────────────── 3. no zippering vs bilinear ────────────────────────────

    @Test
    fun edgeDirectedGreenHasNoZipperWhereBilinearDoes() {
        val w = 48
        val h = 16
        // Vertical bars: gray scene varying only in x (constant in y) → a correct green field is flat in y.
        val m = mosaic(w, h) { x, _ -> 512 + (412.0 * sin(2.0 * Math.PI * x / 4.0)).toInt() }
        val params = identityWbParams(identity3, identity3)

        val edgeGreen = RawDemosaicReference.reconstructGreenField(m, params)
        val biGreen = RawDemosaicReference.bilinearGreenField(m, params)

        // For each interior column, variance over y measures vertical (maze/zipper) artifact: the scene
        // is constant in y, so any y-variance is reconstruction zippering.
        var edgeVar = 0.0
        var biVar = 0.0
        var cols = 0
        for (x in 4 until w - 4) {
            edgeVar += columnYVariance(edgeGreen, w, h, x)
            biVar += columnYVariance(biGreen, w, h, x)
            cols++
        }
        edgeVar /= cols
        biVar /= cols

        assertTrue("edge-directed green should be essentially zipper-free (var=$edgeVar)", edgeVar < 1e-6)
        assertTrue("bilinear must exhibit measurable zippering (var=$biVar)", biVar > 1e-3)
        assertTrue("edge-directed zipper ($edgeVar) must be far below bilinear ($biVar)", edgeVar < biVar * 1e-2)
    }

    // ──────────────────────────── 4. MTF: edge-directed resolves finer than bilinear ────────────────────────────

    @Test
    fun edgeDirectedDemosaicHasHigherMtfThanBilinear() {
        // Normalized R-site green modulation vs spatial frequency (period T px). Edge-directed sustains
        // contrast to higher frequency (smaller T) than bilinear → higher MTF / MTF50.
        val periods = intArrayOf(16, 12, 10, 8, 6)
        val threshold = 0.8

        var edgeCutoffT = Int.MAX_VALUE
        var biCutoffT = Int.MAX_VALUE
        var sawStrictWin = false

        for (t in periods) {
            val edgeMod = rSiteModulation(t, edgeDirected = true)
            val biMod = rSiteModulation(t, edgeDirected = false)
            assertTrue("edge-directed modulation must dominate bilinear at T=$t (edge=$edgeMod bi=$biMod)",
                edgeMod >= biMod - 1e-4)
            if (edgeMod >= threshold) edgeCutoffT = minOf(edgeCutoffT, t)
            if (biMod >= threshold) biCutoffT = minOf(biCutoffT, t)
            if (edgeMod > biMod * 1.1) sawStrictWin = true
        }

        // Smaller cutoff period = higher resolving frequency = higher MTF50.
        assertTrue("edge-directed MTF50 (cutoffT=$edgeCutoffT) must resolve finer than bilinear (cutoffT=$biCutoffT)",
            edgeCutoffT <= biCutoffT)
        assertTrue("edge-directed must strictly out-resolve bilinear at some frequency", sawStrictWin)
    }

    /** Normalized modulation of reconstructed green sampled at Red sites along a Red row, for period [t]. */
    private fun rSiteModulation(t: Int, edgeDirected: Boolean): Double {
        val w = 64
        val h = 16
        val base = 512.0
        val amp = 400.0
        val m = mosaic(w, h) { x, _ -> (base + amp * sin(2.0 * Math.PI * x / t)).toInt() }
        val params = identityWbParams(identity3, identity3)
        val green = if (edgeDirected) {
            RawDemosaicReference.reconstructGreenField(m, params)
        } else {
            RawDemosaicReference.bilinearGreenField(m, params)
        }
        val y = 8 // even → Red/Gr row under RGGB
        var gMin = Double.MAX_VALUE
        var gMax = -Double.MAX_VALUE
        var inMin = Double.MAX_VALUE
        var inMax = -Double.MAX_VALUE
        // Sample interior Red sites only (even x, away from clamp edges).
        for (x in 8 until w - 8 step 2) {
            if (RawDemosaicReference.bayerChannel(x, y, pattern) != 0) continue
            val g = green[y * w + x].toDouble()
            gMin = minOf(gMin, g); gMax = maxOf(gMax, g)
            val inV = (m.raw[y * w + x].toDouble() / whiteLevel.toDouble()).coerceIn(0.0, 1.0)
            inMin = minOf(inMin, inV); inMax = maxOf(inMax, inV)
        }
        val inputModulation = inMax - inMin
        if (inputModulation < 1e-6) return 0.0
        return (gMax - gMin) / inputModulation
    }

    private fun columnYVariance(field: FloatArray, w: Int, h: Int, x: Int): Double {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 2 until h - 2) {
            val v = field[y * w + x].toDouble()
            sum += v; sumSq += v * v; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    // ──────────────────────────── color helpers ────────────────────────────

    private fun xyzToLab(xyz: DoubleArray, white: DoubleArray): DoubleArray {
        fun f(t: Double): Double = if (t > 0.008856) Math.cbrt(t) else (7.787 * t + 16.0 / 116.0)
        val fx = f(xyz[0] / white[0])
        val fy = f(xyz[1] / white[1])
        val fz = f(xyz[2] / white[2])
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun ciede2000(lab1: DoubleArray, lab2: DoubleArray): Double {
        val (l1, a1, b1) = lab1
        val (l2, a2, b2) = lab2
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) / 2.0
        val cBar7 = Math.pow(cBar, 7.0)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + Math.pow(25.0, 7.0))))
        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        fun hp(ap: Double, b: Double): Double {
            if (ap == 0.0 && b == 0.0) return 0.0
            var h = Math.toDegrees(atan2(b, ap))
            if (h < 0) h += 360.0
            return h
        }
        val h1p = hp(a1p, b1)
        val h2p = hp(a2p, b2)
        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p - h1p > 180.0 -> h2p - h1p - 360.0
            else -> h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))
        val lpBar = (l1 + l2) / 2.0
        val cpBar = (c1p + c2p) / 2.0
        val hpBar = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }
        val t = 1.0 - 0.17 * cos(Math.toRadians(hpBar - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hpBar)) +
            0.32 * cos(Math.toRadians(3.0 * hpBar + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hpBar - 63.0))
        val dTheta = 30.0 * exp(-Math.pow((hpBar - 275.0) / 25.0, 2.0))
        val cpBar7 = Math.pow(cpBar, 7.0)
        val rc = 2.0 * sqrt(cpBar7 / (cpBar7 + Math.pow(25.0, 7.0)))
        val sl = 1.0 + (0.015 * Math.pow(lpBar - 50.0, 2.0)) / sqrt(20.0 + Math.pow(lpBar - 50.0, 2.0))
        val sc = 1.0 + 0.045 * cpBar
        val sh = 1.0 + 0.015 * cpBar * t
        val rt = -sin(Math.toRadians(2.0 * dTheta)) * rc
        return sqrt(
            Math.pow(dLp / sl, 2.0) +
                Math.pow(dCp / sc, 2.0) +
                Math.pow(dHp / sh, 2.0) +
                rt * (dCp / sc) * (dHp / sh)
        )
    }

    private operator fun DoubleArray.component1() = this[0]
    private operator fun DoubleArray.component2() = this[1]
    private operator fun DoubleArray.component3() = this[2]
}
