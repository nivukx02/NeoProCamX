package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Golden lock for the canonical NDR10+/NIVUKX Vision tone operator.
 *
 * These assertions ARE the contract that the live preview shader, the record
 * shader, and the NPV10 export compute shader must each match. They encode the
 * three correctness fixes the single-source operator was built for:
 *   - nit accuracy (diffuse white == 203 nits, never ~9450),
 *   - C1-continuous, monotone, shadow-lifting shape (no knee Mach band, no crush),
 *   - hue-constant RGB transfer (no per-channel highlight hue twist).
 */
class NdrToneOperatorGoldenTest {

    private val ndr = NdrToneOperator.ndr10Plus()
    private val nivukx = NdrToneOperator.nivukxVision()

    // ── Shape: anchors, monotonicity, shadow lift ────────────────────────────

    @Test
    fun signalShape_anchorsBlackAndDiffuseWhite() {
        for (p in listOf(ndr, nivukx)) {
            assertEquals(0.0, NdrToneOperator.signalShape(0.0, p), 1e-9)
            assertEquals(1.0, NdrToneOperator.signalShape(1.0, p), 1e-9)
        }
    }

    @Test
    fun signalShape_liftsShadowsNeverCrushes() {
        // x^gamma (gamma<1) plus a non-negative toe is >= identity across (0,1].
        for (p in listOf(ndr, nivukx)) {
            var x = 0.001
            while (x <= 1.0 + 1e-12) {
                val s = NdrToneOperator.signalShape(x, p)
                assertTrue("toe must lift not crush at x=$x mode=${p.mode}: $s < $x", s >= x - 1e-9)
                x += 0.001
            }
        }
    }

    @Test
    fun signalShape_isMonotonicAcrossSceneRange() {
        for (p in listOf(ndr, nivukx)) {
            var prev = -1.0
            var x = 0.0
            while (x <= 8.0) {
                val s = NdrToneOperator.signalShape(x, p)
                assertTrue("non-monotone signalShape at x=$x mode=${p.mode}", s >= prev - 1e-12)
                prev = s
                x += 0.002
            }
        }
    }

    @Test
    fun signalShape_isC1ContinuousAtDiffuseWhiteJoin() {
        val h = 1e-5
        for (p in listOf(ndr, nivukx)) {
            val left = (NdrToneOperator.signalShape(1.0, p) - NdrToneOperator.signalShape(1.0 - h, p)) / h
            val right = (NdrToneOperator.signalShape(1.0 + h, p) - NdrToneOperator.signalShape(1.0, p)) / h
            assertEquals("C1 slope break at knee mode=${p.mode}", left, right, 5e-3)
            // and the join slope equals the documented reference slope
            assertEquals(p.signalReferenceSlope, right, 5e-3)
        }
    }

    // ── Nit accuracy: the rank-1 critical fix ────────────────────────────────

    @Test
    fun nitMap_anchorsDiffuseWhiteAt203() {
        for (p in listOf(ndr, nivukx)) {
            assertEquals(203.0, NdrToneOperator.signalToDisplayNits(1.0, p), 1e-6)
            assertEquals(101.5, NdrToneOperator.signalToDisplayNits(0.5, p), 1e-6)
        }
    }

    @Test
    fun nitMap_isC1ContinuousAtDiffuseWhite() {
        val h = 1e-5
        for (p in listOf(ndr, nivukx)) {
            val left = (NdrToneOperator.signalToDisplayNits(1.0, p) -
                NdrToneOperator.signalToDisplayNits(1.0 - h, p)) / h
            val right = (NdrToneOperator.signalToDisplayNits(1.0 + h, p) -
                NdrToneOperator.signalToDisplayNits(1.0, p)) / h
            assertEquals("nit-map slope break mode=${p.mode}", left, right, 1e-2 * p.diffuseWhiteNits)
        }
    }

    @Test
    fun delivery_diffuseWhiteDecodesNear203_notThousandsOfNits() {
        // The historical bug PQ-encoded a [0,1] signal as if 1.0 == 10000 nits,
        // shipping diffuse white at ~9450 nits. Lock the correct behavior.
        for (p in listOf(ndr, nivukx)) {
            val decoded = HdrImagingMath.pqDecodeToNits(NdrToneOperator.encodePq(1.0, p))
            assertTrue("diffuse white must be ~203 nits, got $decoded", decoded in 150.0..260.0)
        }
    }

    @Test
    fun delivery_neverExceedsMasterPeak() {
        for (p in listOf(ndr, nivukx)) {
            val brightest = HdrImagingMath.pqDecodeToNits(NdrToneOperator.encodePq(100.0, p))
            assertTrue("delivered peak $brightest exceeds master ${p.peakNits}", brightest <= p.peakNits * 1.002)
            assertTrue("highlights must reach above diffuse white", brightest > p.diffuseWhiteNits)
        }
    }

    @Test
    fun delivery_isMonotonic() {
        for (p in listOf(ndr, nivukx)) {
            var prev = -1.0
            var x = 0.0
            while (x <= 8.0) {
                val code = NdrToneOperator.encodePq(x, p)
                assertTrue("non-monotone PQ delivery at x=$x mode=${p.mode}", code >= prev - 1e-12)
                prev = code
                x += 0.002
            }
        }
    }

    // ── Hue-constant RGB transfer: the rank-7 fix ────────────────────────────

    @Test
    fun rgbTransfer_preservesHueAngleOnSaturatedHighlight() {
        // A bright saturated orange: luma-preserving + radial desat must keep hue.
        val (or, og, ob) = NdrToneOperator.mapRgbHuePreserving(1.6, 0.5, 0.18, nivukx)
        val mY = NdrToneOperator.luma(or, og, ob)
        val inY = NdrToneOperator.luma(1.6, 0.5, 0.18)
        val inChroma = doubleArrayOf(1.6 - inY, 0.5 - inY, 0.18 - inY)
        val outChroma = doubleArrayOf(or - mY, og - mY, ob - mY)
        val cos = dot(inChroma, outChroma) / (norm(inChroma) * norm(outChroma) + 1e-12)
        assertTrue("hue angle twisted on highlight: cos=$cos", cos > 0.9999)
        // chroma is reduced (desaturated) but hue held — beats per-channel knees.
        assertTrue(
            "highlight relative chroma should be compressed",
            norm(outChroma) / mY < norm(inChroma) / inY
        )
    }

    @Test
    fun rgbTransfer_keepsNeutralNeutral() {
        val (r, g, b) = NdrToneOperator.mapRgbHuePreserving(0.4, 0.4, 0.4, ndr)
        assertEquals(r, g, 1e-9)
        assertEquals(g, b, 1e-9)
    }

    // ── Skin-gated warm-red taming (host-verified in tools/calibration/ndr_live_repro.py) ────

    private fun outSat(r: Double, g: Double, b: Double, tame: Double): Double {
        val (or, og, ob) = NdrToneOperator.mapRgbHuePreserving(r, g, b, ndr, tame)
        val mx = maxOf(or, og, ob); val mn = minOf(or, og, ob)
        return if (mx <= 1e-9) 0.0 else (mx - mn) / mx
    }

    @Test
    fun warmSkinRedTame_zeroIsByteIdentical() {
        // default (0.0) must equal explicit 0.0 -> shader-parity + goldens stay valid.
        val (dr, dg, db) = NdrToneOperator.mapRgbHuePreserving(0.62, 0.42, 0.32, ndr)
        val (zr, zg, zb) = NdrToneOperator.mapRgbHuePreserving(0.62, 0.42, 0.32, ndr, 0.0)
        assertEquals(dr, zr, 1e-12); assertEquals(dg, zg, 1e-12); assertEquals(db, zb, 1e-12)
    }

    @Test
    fun warmSkinRedTame_reconstructsRedClippedWarmSubject() {
        val (br, bg, bb) = NdrToneOperator.mapRgbHuePreserving(1.35, 0.60, 0.30, ndr, 0.0)
        val (tr, tg, tb) = NdrToneOperator.mapRgbHuePreserving(1.35, 0.60, 0.30, ndr, 0.5)
        assertTrue("red overflow must fall", tr / tg < br / bg)
        assertTrue("missing blue must recover", tb / tg > bb / bg)
    }

    @Test
    fun warmSkinRedTame_preservesWarmSubjectLuminance() {
        val (br, bg, bb) = NdrToneOperator.mapRgbHuePreserving(1.35, 0.60, 0.30, ndr, 0.0)
        val (tr, tg, tb) = NdrToneOperator.mapRgbHuePreserving(1.35, 0.60, 0.30, ndr, 0.5)
        assertEquals(NdrToneOperator.luma(br, bg, bb), NdrToneOperator.luma(tr, tg, tb), 1e-9)
    }

    @Test
    fun warmSkinRedTame_leavesNeutralUntouched() {
        val (br, bg, bb) = NdrToneOperator.mapRgbHuePreserving(0.4, 0.4, 0.4, ndr, 0.0)
        val (tr, tg, tb) = NdrToneOperator.mapRgbHuePreserving(0.4, 0.4, 0.4, ndr, 0.6)
        assertEquals(br, tr, 1e-9); assertEquals(bg, tg, 1e-9); assertEquals(bb, tb, 1e-9)
    }

    @Test
    fun warmSkinRedTame_protectsPureSaturatedRedObject() {
        val base = NdrToneOperator.mapRgbHuePreserving(1.20, 0.10, 0.08, ndr, 0.0)
        val tamed = NdrToneOperator.mapRgbHuePreserving(1.20, 0.10, 0.08, ndr, 0.5)
        assertEquals(base.first, tamed.first, 1e-9)
        assertEquals(base.second, tamed.second, 1e-9)
        assertEquals(base.third, tamed.third, 1e-9)
    }

    @Test
    fun warmSkinRedTame_protectsLowBlueOrangeObject() {
        val base = NdrToneOperator.mapRgbHuePreserving(1.25, 0.55, 0.10, ndr, 0.0)
        val tamed = NdrToneOperator.mapRgbHuePreserving(1.25, 0.55, 0.10, ndr, 0.5)
        assertEquals(base.first, tamed.first, 1e-9)
        assertEquals(base.second, tamed.second, 1e-9)
        assertEquals(base.third, tamed.third, 1e-9)
    }

    // ── Mode separation sanity ───────────────────────────────────────────────

    @Test
    fun nivukx_isMorePremiumThanNdr() {
        assertTrue(nivukx.peakNits > ndr.peakNits)
        // stronger midtone lift (lower gamma)
        assertTrue(
            NdrToneOperator.signalShape(0.3, nivukx) > NdrToneOperator.signalShape(0.3, ndr)
        )
        // more highlight headroom toward peak
        assertTrue(
            NdrToneOperator.signalToDisplayNits(20.0, nivukx) >
                NdrToneOperator.signalToDisplayNits(20.0, ndr)
        )
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
}
