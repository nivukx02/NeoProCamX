package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase-1 lock for the genuine NDR10+/NIVUKX full-RGB → PQ delivery baked into the
 * record shader `neo_p010_export_enhance.comp`.
 *
 * Two guarantees:
 *  1. SOURCE PARITY — the shader keeps the canonical delivery math (peaks, diffuse
 *     anchor, PQ M2, BT.2020 NCL coefficients) and the compile-gate, so the GPU
 *     path cannot silently diverge from [NdrNivukxDeliveryEngine].
 *  2. BEHAVIOUR — this test re-implements the exact GLSL chain
 *     (limited-range Y'CbCr → R'G'B' → HLG inverse-OETF scene-linear → engine →
 *     PQ → Y'CbCr) in Kotlin and pins the load-bearing SAFETY property: a NEUTRAL
 *     pixel round-trips to neutral (Cb==Cr==512) — no color cast — and colored
 *     pixels keep their chroma sign. If the GLSL and this replica drift, the fix
 *     is deliberate, not accidental.
 */
class NeoPqDeliveryShaderParityTest {

    private val shaderSource =
        File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

    // ── GLSL replica: limited-range 10-bit BT.2020 NCL + HLG-linearize ───────
    private val hlgDiffuseScene = HdrImagingMath.bt2100HlgInverseOetf(0.75)

    private fun ycbcrCodeToRgb(yCode: Double, cbCode: Double, crCode: Double): DoubleArray {
        val y = ((yCode - 64.0) / 876.0).coerceIn(0.0, 1.0)
        val pb = (cbCode - 512.0) / 896.0
        val pr = (crCode - 512.0) / 896.0
        return doubleArrayOf(
            y + 1.4746 * pr,
            y - 0.16455 * pb - 0.57135 * pr,
            y + 1.8814 * pb
        )
    }

    private fun luma(c: DoubleArray) = 0.2627 * c[0] + 0.6780 * c[1] + 0.0593 * c[2]

    private fun rgbToChromaCodes(c: DoubleArray): DoubleArray {
        val y = luma(c)
        return doubleArrayOf(
            (c[2] - y) / 1.8814 * 896.0 + 512.0, // Cb
            (c[0] - y) / 1.4746 * 896.0 + 512.0  // Cr
        )
    }

    private fun hlgSignalToEngineLinear(c: DoubleArray) = DoubleArray(3) {
        HdrImagingMath.bt2100HlgInverseOetf(c[it].coerceIn(0.0, 1.0)) / hlgDiffuseScene
    }

    /** Full GLSL delivery chain, PQ R'G'B' per channel. */
    private fun deliverFromCodes(
        yCode: Double,
        cbCode: Double,
        crCode: Double,
        mode: NdrNivukxDeliveryEngine.Mode
    ): DoubleArray {
        val signal = ycbcrCodeToRgb(yCode, cbCode, crCode)
        val lin = hlgSignalToEngineLinear(signal)
        return NdrNivukxDeliveryEngine.deliverPq(lin[0], lin[1], lin[2], mode)
    }

    @Test
    fun neutralPixelRoundTripsToNeutral_noColorCast() {
        // The single most important safety property of the full-RGB path.
        for (m in NdrNivukxDeliveryEngine.Mode.values()) {
            for (yCode in listOf(120.0, 300.0, 500.0, 720.0, 900.0)) {
                val pq = deliverFromCodes(yCode, 512.0, 512.0, m)
                assertEquals("R==G neutral mode=$m y=$yCode", pq[0], pq[1], 1e-6)
                assertEquals("G==B neutral mode=$m y=$yCode", pq[1], pq[2], 1e-6)
                val chroma = rgbToChromaCodes(pq)
                assertEquals("Cb must stay neutral", 512.0, chroma[0], 1.0)
                assertEquals("Cr must stay neutral", 512.0, chroma[1], 1.0)
            }
        }
    }

    @Test
    fun deliveredCodesStayInVideoRange() {
        for (m in NdrNivukxDeliveryEngine.Mode.values()) {
            for (yCode in listOf(64.0, 400.0, 940.0)) {
                for (cb in listOf(300.0, 512.0, 720.0)) {
                    val pq = deliverFromCodes(yCode, cb, 1024.0 - cb, m)
                    val yOut = luma(pq) * 876.0 + 64.0
                    assertTrue("Y code $yOut out of range", yOut in 0.0..1023.5)
                    val chroma = rgbToChromaCodes(pq)
                    assertTrue("Cb ${chroma[0]} out of range", chroma[0] in 0.0..1023.5)
                    assertTrue("Cr ${chroma[1]} out of range", chroma[1] in 0.0..1023.5)
                }
            }
        }
    }

    @Test
    fun warmPixelKeepsWarmChromaSign() {
        // A warm/red-biased pixel (Cr > 512) must remain warm after delivery.
        val pq = deliverFromCodes(520.0, 470.0, 600.0, NdrNivukxDeliveryEngine.Mode.NIVUKX_VISION)
        val chroma = rgbToChromaCodes(pq)
        assertTrue("warm pixel must keep Cr>512, got ${chroma[1]}", chroma[1] > 512.0)
    }

    @Test
    fun hlgDiffuseSceneConstantMatchesShader() {
        // The shader hardcodes NEO_HLG_DIFFUSE_SCENE = 0.264967; keep it honest.
        assertEquals(0.264967, hlgDiffuseScene, 1e-4)
        assertTrue(shaderSource.contains("NEO_HLG_DIFFUSE_SCENE"))
    }

    // ── Source parity: the shader keeps the canonical delivery math + gate ────
    @Test
    fun shaderKeepsCanonicalDeliveryMathAndGate() {
        listOf(
            "NEO_PQ_DELIVERY_ENABLED",
            "ACTIVE_TRANSPORT_BT2100_PQ",
            "neoDeliverPq(",
            "neoPqYcbcrCodeToRgb(",
            "neoPqRgbToChromaCodes(",
            "neoPqHlgInvOetf(",
            "1680.0",   // NIVUKX peak
            "1420.0",   // NDR10+ peak
            "203.0",    // diffuse-white anchor
            "78.84375", // PQ M2
            "1.4746",   // BT.2020 NCL Pr->R
            "1.8814"    // BT.2020 NCL Pb->B
        ).forEach {
            assertTrue("shader must retain `$it`", shaderSource.contains(it))
        }
    }

    @Test
    fun deliveryIsActiveInPhase2() {
        // Phase 2 activates the genuine PQ delivery path (compile-gate flipped to 1),
        // paired with the encoder/container ST.2084 retag in RecordingHdrVideoLookPolicy.
        assertTrue(
            "NEO_PQ_DELIVERY_ENABLED must be 1 in Phase 2",
            Regex("""#define\s+NEO_PQ_DELIVERY_ENABLED\s+1""").containsMatchIn(shaderSource)
        )
    }
}
