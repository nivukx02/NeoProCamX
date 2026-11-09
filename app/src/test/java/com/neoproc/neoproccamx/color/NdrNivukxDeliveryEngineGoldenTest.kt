package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Golden lock for [NdrNivukxDeliveryEngine] — the canonical full-color, nit-accurate
 * PQ delivery for NDR10+ / NIVUKX Vision.
 *
 * These assertions ARE the contract the record shader (`neo_p010_export_enhance.comp`)
 * and the 10-bit PQ preview compositor must each match. They encode the properties
 * that make the delivered signal genuinely the profile's OWN color science over PQ —
 * NOT plain HLG and NOT a bare luma curve:
 *   - true PQ delivery (black→0, diffuse white→203 nits, never the ~9450-nit bug),
 *   - hue-constant RGB (saturated highlights keep their hue angle),
 *   - monotone, bounded to the mode's master peak,
 *   - NIVUKX is a distinct, more-premium render than NDR10+.
 */
class NdrNivukxDeliveryEngineGoldenTest {

    private val ndr = NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
    private val nivukx = NdrNivukxDeliveryEngine.Mode.NIVUKX_VISION

    private fun decodeNits(pq: Double) = HdrImagingMath.pqDecodeToNits(pq)

    @Test
    fun black_deliversZero() {
        for (m in listOf(ndr, nivukx)) {
            val pq = NdrNivukxDeliveryEngine.deliverPq(0.0, 0.0, 0.0, m)
            assertEquals(0.0, pq[0], 1e-9)
            assertEquals(0.0, pq[1], 1e-9)
            assertEquals(0.0, pq[2], 1e-9)
        }
    }

    @Test
    fun diffuseWhite_deliversExactly203Nits() {
        // BT.2408 diffuse-white anchor: the (1,1,1) reference maps to 203 nits per channel.
        val expected = HdrImagingMath.pqEncodeFromNits(203.0)
        for (m in listOf(ndr, nivukx)) {
            val pq = NdrNivukxDeliveryEngine.deliverPq(1.0, 1.0, 1.0, m)
            assertEquals(expected, pq[0], 1e-6)
            assertEquals(expected, pq[1], 1e-6)
            assertEquals(expected, pq[2], 1e-6)
            assertTrue("diffuse white must decode ~203 nits", decodeNits(pq[0]) in 150.0..260.0)
        }
    }

    @Test
    fun neutral_staysNeutral() {
        for (m in listOf(ndr, nivukx)) {
            val pq = NdrNivukxDeliveryEngine.deliverPq(0.35, 0.35, 0.35, m)
            assertEquals(pq[0], pq[1], 1e-9)
            assertEquals(pq[1], pq[2], 1e-9)
        }
    }

    @Test
    fun delivery_neverExceedsMasterPeak() {
        for (m in listOf(ndr, nivukx)) {
            val peak = NdrNivukxDeliveryEngine.targetPeakNits(m)
            // A very bright specular, well past diffuse white.
            val pq = NdrNivukxDeliveryEngine.deliverPq(60.0, 60.0, 60.0, m)
            val brightest = decodeNits(pq.max())
            assertTrue("delivered peak $brightest exceeds master $peak", brightest <= peak * 1.003)
            assertTrue("highlights must reach above diffuse white", brightest > 203.0)
        }
    }

    @Test
    fun delivery_isMonotonicInLuma() {
        for (m in listOf(ndr, nivukx)) {
            var prev = -1.0
            var x = 0.0
            while (x <= 8.0) {
                val pq = NdrNivukxDeliveryEngine.deliverPq(x, x, x, m)
                assertTrue("non-monotone delivery at x=$x mode=$m", pq[0] >= prev - 1e-12)
                prev = pq[0]
                x += 0.01
            }
        }
    }

    @Test
    fun rgbTransfer_preservesHueAngleOnSaturatedHighlight() {
        // A bright saturated orange delivered to PQ nits must keep its hue angle
        // (luma-preserving scale + radial desat), not twist toward another hue.
        val pq = NdrNivukxDeliveryEngine.deliverPq(1.6, 0.5, 0.18, nivukx)
        val outNits = doubleArrayOf(decodeNits(pq[0]), decodeNits(pq[1]), decodeNits(pq[2]))
        val inY = HdrImagingMath.bt2020Luminance(1.6, 0.5, 0.18)
        val inChroma = doubleArrayOf(1.6 - inY, 0.5 - inY, 0.18 - inY)
        val outY = HdrImagingMath.bt2020Luminance(outNits[0], outNits[1], outNits[2])
        val outChroma = doubleArrayOf(outNits[0] - outY, outNits[1] - outY, outNits[2] - outY)
        val cos = dot(inChroma, outChroma) / (norm(inChroma) * norm(outChroma) + 1e-12)
        assertTrue("hue angle twisted on highlight delivery: cos=$cos", cos > 0.999)
    }

    @Test
    fun nivukx_isDistinctAndMorePremiumThanNdr() {
        assertTrue(
            NdrNivukxDeliveryEngine.targetPeakNits(nivukx) >
                NdrNivukxDeliveryEngine.targetPeakNits(ndr)
        )
        // A bright specular reaches higher nits under NIVUKX (more highlight headroom),
        // proving the two modes deliver genuinely different renders — not the same signal.
        val ndrPeak = decodeNits(NdrNivukxDeliveryEngine.deliverPq(30.0, 30.0, 30.0, ndr).max())
        val nivPeak = decodeNits(NdrNivukxDeliveryEngine.deliverPq(30.0, 30.0, 30.0, nivukx).max())
        assertTrue("NIVUKX must deliver a distinct, higher-headroom highlight", nivPeak > ndrPeak)
    }

    @Test
    fun deliverPqCodes_areInRangeAndAnchored() {
        val maxCode = (1 shl 10) - 1
        val codes = NdrNivukxDeliveryEngine.deliverPqCodes(1.0, 1.0, 1.0, ndr, bits = 10)
        for (c in codes) assertTrue("code $c out of 10-bit range", c in 0..maxCode)
        val expected = HdrImagingMath.quantize(HdrImagingMath.pqEncodeFromNits(203.0), 10)
        assertEquals(expected, codes[0])
        assertEquals(expected, codes[1])
        assertEquals(expected, codes[2])
    }

    @Test
    fun allocationFreeDeliveryMatchesCanonicalApiForBothModes() {
        for (mode in listOf(ndr, nivukx)) {
            val expected = NdrNivukxDeliveryEngine.deliverPqCodes(1.6, 0.5, 0.18, mode)
            val actual = IntArray(5)
            NdrNivukxDeliveryEngine.deliverPqCodesInto(
                rLinear = 1.6,
                gLinear = 0.5,
                bLinear = 0.18,
                delivery = NdrNivukxDeliveryEngine.prepare(mode),
                out = actual,
                offset = 1,
                scratch = DoubleArray(6)
            )
            assertEquals(expected[0], actual[1])
            assertEquals(expected[1], actual[2])
            assertEquals(expected[2], actual[3])
        }
    }

    @Test
    fun liveAcesCgToBt2020MatrixPreservesNeutralAxis() {
        val out = DoubleArray(5)
        NdrNivukxDeliveryEngine.acesCgToLinearBt2020Into(0.18, 0.18, 0.18, out, 1)
        assertEquals(0.18, out[1], 1e-8)
        assertEquals(0.18, out[2], 1e-8)
        assertEquals(0.18, out[3], 1e-8)
    }

    @Test
    fun targetPeakDefaults_matchExactProjectMasteringContracts() {
        assertEquals(5_000.0, NdrNivukxDeliveryEngine.targetPeakNits(ndr), 0.0)
        assertEquals(10_000.0, NdrNivukxDeliveryEngine.targetPeakNits(nivukx), 0.0)
    }

    @Test
    fun targetPeakHints_cannotRelabelExactProjectMasters() {
        assertEquals(5_000.0, NdrNivukxDeliveryEngine.resolveTargetPeak(ndr, 4_000.0), 0.0)
        assertEquals(10_000.0, NdrNivukxDeliveryEngine.resolveTargetPeak(nivukx, 8_000.0), 0.0)
        assertEquals(5_000.0, NdrNivukxDeliveryEngine.resolveTargetPeak(ndr, 800.0), 0.0)
        assertEquals(10_000.0, NdrNivukxDeliveryEngine.resolveTargetPeak(nivukx, Double.NaN), 0.0)
    }

    @Test
    fun saturatedRedHighlight_neverExceedsExactModeMasterPeak() {
        val peak = NdrNivukxDeliveryEngine.targetPeakNits(ndr)
        val pq = NdrNivukxDeliveryEngine.deliverPq(
            rLinear = 80.0,
            gLinear = 0.15,
            bLinear = 0.05,
            mode = ndr,
            targetPeakNits = 4_000.0
        )
        pq.forEach { channel ->
            assertTrue("channel exceeded $peak-nit NDR color volume", decodeNits(channel) <= peak * 1.000_001)
        }
    }

    @Test
    fun colorVolumeCompression_preservesBt2020LuminanceAndHueDirection() {
        val input = doubleArrayOf(80.0, 0.15, 0.05)
        val peak = 5_000.0
        val params = NdrToneOperator.ndr10Plus(peak)
        val (shapedR, shapedG, shapedB) =
            NdrToneOperator.mapRgbHuePreserving(input[0], input[1], input[2], params)
        val shapedY = NdrToneOperator.luma(shapedR, shapedG, shapedB)
        val expectedY = NdrToneOperator.signalToDisplayNits(shapedY, params)

        val pq = NdrNivukxDeliveryEngine.deliverPq(input[0], input[1], input[2], ndr)
        val output = DoubleArray(3) { decodeNits(pq[it]) }
        val outputY = HdrImagingMath.bt2020Luminance(output[0], output[1], output[2])
        assertEquals("peak compression changed luminance", expectedY, outputY, expectedY * 1e-6)

        val shapedChroma = doubleArrayOf(shapedR - shapedY, shapedG - shapedY, shapedB - shapedY)
        val outputChroma = doubleArrayOf(output[0] - outputY, output[1] - outputY, output[2] - outputY)
        val cos = dot(shapedChroma, outputChroma) / (norm(shapedChroma) * norm(outputChroma) + 1e-12)
        assertTrue("peak compression twisted hue direction: cos=$cos", cos > 0.999_999)
        assertTrue("test vector did not exercise peak compression", output.max() <= peak * 1.000_001)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
}
