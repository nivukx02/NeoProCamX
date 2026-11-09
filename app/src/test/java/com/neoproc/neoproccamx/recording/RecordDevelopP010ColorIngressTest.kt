package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Locks the pixel colour maths of the record-then-develop ingress/egress — the no-downgrade foundation.
 * Decode and encode transfer functions must be exact inverses (≤±1 code round-trip), the YCbCr matrices
 * must be colour-correct per primaries, and the gamut widen must be lossless on neutral white.
 */
class RecordDevelopP010ColorIngressTest {

    private fun word(code: Int, lowPadding: Int = 0): Pair<Byte, Byte> {
        val v = (code shl 6) or (lowPadding and 0x3F)
        return (v and 0xFF).toByte() to ((v ushr 8) and 0xFF).toByte()
    }

    @Test
    fun readP010CodeRecoversMsbJustifiedTenBits() {
        for (code in intArrayOf(0, 1, 63, 512, 700, 1023)) {
            val (lo, hi) = word(code)
            val payload = byteArrayOf(lo, hi)
            assertEquals(code, RecordDevelopP010ColorIngress.readP010Code(payload, 0))
        }
    }

    @Test
    fun readP010CodeIgnoresLowPaddingBits() {
        val (lo, hi) = word(777, lowPadding = 0x3F)
        assertEquals(777, RecordDevelopP010ColorIngress.readP010Code(byteArrayOf(lo, hi), 0))
    }

    @Test
    fun neutralChromaDecodesAchromatic() {
        // Cb == Cr == 512 (neutral) ⇒ R == G == B for both primary sets.
        for (space in DevelopColorSpace.values()) {
            val rgb = RecordDevelopP010ColorIngress.decodeToLinearRgb(700, 512, 512, space, DevelopColorTransfer.SDR)
            assertEquals("R==G ($space)", rgb[0], rgb[1], 1e-5f)
            assertEquals("G==B ($space)", rgb[1], rgb[2], 1e-5f)
            assertTrue("mid-gray > 0 ($space)", rgb[0] > 0f)
        }
    }

    @Test
    fun positiveCrPushesRedAboveBlue() {
        // Cr well above neutral, Cb neutral ⇒ red channel dominates blue.
        val rgb = RecordDevelopP010ColorIngress.decodeToLinearRgb(512, 512, 900, DevelopColorSpace.REC709, DevelopColorTransfer.SDR)
        assertTrue("R>B on high Cr", rgb[0] > rgb[2])
    }

    @Test
    fun rec709EotfOetfRoundTripsWithinOneCode() {
        for (code in 0..1023 step 7) {
            val signal = code / 1023.0
            val linear = RecordDevelopP010ColorIngress.rec709ToLinear(signal)
            val back = RecordDevelopP010ColorIngress.linearToRec709(linear)
            val roundTripCode = (back * 1023.0).roundToInt()
            assertTrue("code $code round-trips (got $roundTripCode)", abs(roundTripCode - code) <= 1)
        }
    }

    @Test
    fun pqOetfInverseRoundTrips() {
        var y = 0.0
        while (y <= 1.0) {
            val signal = RecordDevelopP010ColorIngress.pqOetf(y)
            val back = RecordDevelopP010ColorIngress.pqToLinear(signal)
            assertEquals("PQ round-trip at $y", y, back, 1e-4)
            y += 0.05
        }
    }

    @Test
    fun hlgSignalToSceneAnchors() {
        assertEquals(0.0, RecordDevelopP010ColorIngress.hlgSignalToScene(0.0), 1e-9)
        assertEquals(1.0 / 12.0, RecordDevelopP010ColorIngress.hlgSignalToScene(0.5), 1e-6)
        assertEquals(1.0, RecordDevelopP010ColorIngress.hlgSignalToScene(1.0), 1e-6)
    }

    @Test
    fun rec709ToRec2020IsLosslessOnNeutralWhite() {
        val white = RecordDevelopP010ColorIngress.rec709LinearToRec2020(floatArrayOf(1f, 1f, 1f))
        assertEquals(1f, white[0], 1e-3f)
        assertEquals(1f, white[1], 1e-3f)
        assertEquals(1f, white[2], 1e-3f)
        // Black maps to black.
        val black = RecordDevelopP010ColorIngress.rec709LinearToRec2020(floatArrayOf(0f, 0f, 0f))
        assertEquals(0f, black[0] + black[1] + black[2], 1e-6f)
    }

    @Test
    fun identityDevelopPreservesLinearLightThroughEncode() {
        // No-downgrade at the ingress level: with an identity develop, decode → encode → re-decode must
        // return the same linear light. (The 10-bit CODE changes because the intermediate is limited-range
        // Y'CbCr while r210 output is full-range R'G'B' — a lossless range transcode, not a downgrade.)
        for (code in intArrayOf(64, 128, 300, 512, 800, 940)) {
            val linear = RecordDevelopP010ColorIngress.decodeToLinearRgb(
                code, 512, 512, DevelopColorSpace.REC709, DevelopColorTransfer.SDR
            )
            val encoded = RecordDevelopP010ColorIngress.encodeLinearRgb(
                linear[0], linear[1], linear[2], OutputTransfer.SDR_REC709_OETF, 203f
            )
            // r210 stores full-range R'G'B'; re-decode its code back to linear.
            val reLinear = RecordDevelopP010ColorIngress.rec709ToLinear(encoded[0] / 1023.0)
            assertTrue("linear light preserved for code $code (${linear[0]} vs $reLinear)",
                abs(reLinear - linear[0]) < 0.005)
        }
    }

    // ── Phase-6 (R6): genuine-HDR NDR egress via the canonical delivery engine ─────────────────────────

    /** Decode a 10-bit PQ code (0..1023) back to absolute display nits. */
    private fun codeToNits(code: Int): Double =
        HdrImagingMath.pqDecodeToNits(code / 1023.0)

    /** HLG diffuse (reference) white as scene-linear — the level the develop feeds for a 75%-signal grey. */
    private val hlgDiffuseWhiteScene: Float =
        RecordDevelopP010ColorIngress.hlgSignalToScene(RecordDevelopP010ColorIngress.HLG_REFERENCE_WHITE_SIGNAL).toFloat()

    @Test
    fun ndrGenuineHdrAnchorsHlgDiffuseWhiteTo203Nits() {
        // The 75%-signal HLG grey (BT.2408 reference white) must land at ~203 nits — the canonical anchor,
        // NOT clamped SDR white. This proves the develop re-normalises to diffuse-white == 1.0 before delivery.
        val codes = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(
            hlgDiffuseWhiteScene, hlgDiffuseWhiteScene, hlgDiffuseWhiteScene, DevelopColorTransfer.HLG
        )
        assertEquals("R==G neutral grey", codes[0], codes[1])
        assertEquals("G==B neutral grey", codes[1], codes[2])
        assertEquals("HLG diffuse white → ~203 nits", 203.0, codeToNits(codes[0]), 8.0)
    }

    @Test
    fun ndrGenuineHdrPushesHighlightsAboveDiffuseWhiteButUnderPeak() {
        // HLG peak (scene 1.0) is a real specular; it must reach WELL above 203 nits (genuine HDR headroom)
        // yet never exceed the NDR master peak (1420 nits) — the whole point of R6 vs the flat 203-clamp.
        val peakCodes = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(
            1f, 1f, 1f, DevelopColorTransfer.HLG
        )
        val peakNits = codeToNits(peakCodes[0])
        val masterPeak = NdrNivukxDeliveryEngine.targetPeakNits(NdrNivukxDeliveryEngine.Mode.NDR10_PLUS)
        assertTrue("HLG peak must exceed diffuse white (got $peakNits)", peakNits > 210.0)
        assertTrue("HLG peak must not exceed the NDR master peak (got $peakNits)", peakNits <= masterPeak * 1.003)
    }

    @Test
    fun ndrGenuineHdrBlackDeliversZeroAndIsMonotone() {
        val black = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(0f, 0f, 0f, DevelopColorTransfer.HLG)
        assertEquals(0, black[0]); assertEquals(0, black[1]); assertEquals(0, black[2])

        // Brighter scene-linear ⇒ non-decreasing PQ code (monotone luma delivery).
        var prev = -1
        for (x in intArrayOf(0, 5, 20, 80, 265, 600, 1000)) {
            val v = x / 1000f
            val code = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(v, v, v, DevelopColorTransfer.HLG)[0]
            assertTrue("non-monotone at scene=$v (code $code < prev $prev)", code >= prev)
            prev = code
        }
    }

    @Test
    fun ndrGenuineHdrIsExactlyTheCanonicalEngineDelivery() {
        // Single source of truth: the develop's NDR encode IS NdrNivukxDeliveryEngine after the diffuse-white
        // re-normalisation — so an equivalently-scaled call to the canonical engine yields identical codes.
        val scene = 0.42f
        // Use the EXACT Double normaliser the production encode uses (1 / hlgSignalToScene(0.75)), NOT the
        // Float-rounded field — otherwise the two k values could diverge at a 10-bit quantisation boundary
        // and make this lock flaky.
        val k = 1.0 / RecordDevelopP010ColorIngress.hlgSignalToScene(
            RecordDevelopP010ColorIngress.HLG_REFERENCE_WHITE_SIGNAL
        )
        val viaDevelop = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(
            scene, scene, scene, DevelopColorTransfer.HLG
        )
        val viaEngine = NdrNivukxDeliveryEngine.deliverPqCodes(
            scene.toDouble() * k, scene.toDouble() * k, scene.toDouble() * k,
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS, bits = 10
        )
        assertEquals(viaEngine[0], viaDevelop[0])
        assertEquals(viaEngine[1], viaDevelop[1])
        assertEquals(viaEngine[2], viaDevelop[2])
    }

    @Test
    fun ndrGenuineHdrPqSourceAlsoAnchorsDiffuseWhiteTo203Nits() {
        // Defensive PQ-coherent branch: a PQ intermediate carries diffuse white at scene 203/10000 in the
        // PQ-decoded normalised-linear domain (1.0 == 10000 nits). That level must ALSO anchor to ~203 nits,
        // so the normaliser is transfer-correct, not HLG-only.
        val pqDiffuseWhiteScene = (NdrToneOperatorDiffuseWhiteNits / 10_000.0).toFloat()
        val codes = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(
            pqDiffuseWhiteScene, pqDiffuseWhiteScene, pqDiffuseWhiteScene, DevelopColorTransfer.PQ
        )
        assertEquals("PQ-source diffuse white → ~203 nits", 203.0, codeToNits(codes[0]), 8.0)
    }

    @Test
    fun ndrGenuineHdrPreservesHueAngleOnSaturatedColor() {
        // A saturated warm colour must keep its hue ANGLE through the develop delivery (the canonical engine
        // is luma-preserving with only RADIAL highlight desaturation). Compare in- vs out-chroma direction.
        val r = 0.30f; val g = 0.12f; val b = 0.05f
        val codes = RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(r, g, b, DevelopColorTransfer.HLG)
        val outNits = doubleArrayOf(codeToNits(codes[0]), codeToNits(codes[1]), codeToNits(codes[2]))
        val inY = HdrImagingMath.bt2020Luminance(r.toDouble(), g.toDouble(), b.toDouble())
        val inChroma = doubleArrayOf(r - inY, g - inY, b - inY)
        val outY = HdrImagingMath.bt2020Luminance(outNits[0], outNits[1], outNits[2])
        val outChroma = doubleArrayOf(outNits[0] - outY, outNits[1] - outY, outNits[2] - outY)
        val cos = dot(inChroma, outChroma) / (norm(inChroma) * norm(outChroma) + 1e-12)
        assertTrue("hue angle preserved through develop delivery (cos=$cos)", cos > 0.99)
        assertTrue("out chroma is non-trivial", norm(outChroma) > 1.0)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = kotlin.math.sqrt(dot(a, a))

    private val NdrToneOperatorDiffuseWhiteNits = 203.0

    @Test
    fun chromaScaleUsesFullSpanNotHalfSpan() {
        // Rec.709 LIMITED, Y=512, Cb=512, Cr=650 ⇒ E'_Cr=(650-512)/896=0.154 ⇒ R'≈0.754 ⇒ linear≈0.57.
        // The old half-span bug (/512 or /448) doubled chroma ⇒ R'≈0.94 ⇒ linear≈0.86, which this bounds out.
        val rgb = RecordDevelopP010ColorIngress.decodeToLinearRgb(
            512, 512, 650, DevelopColorSpace.REC709, DevelopColorTransfer.SDR
        )
        assertTrue("R reflects full-span chroma, not doubled (got ${rgb[0]})", rgb[0] in 0.50f..0.62f)
        assertTrue("R > B on positive Cr", rgb[0] > rgb[2])
    }
}
