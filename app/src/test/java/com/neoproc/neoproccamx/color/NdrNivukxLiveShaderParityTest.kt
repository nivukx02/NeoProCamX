package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s9-f03 / CP-QV3 — locks the LIVE `ycbcr_preview.frag` NDR10+/NIVUKX delivery constants to the
 * host-golden [NdrToneOperator] oracle.
 *
 * NdrToneOperator is the single-source, host-golden tone definition (nit-accurate delivery, C1
 * monotone shape, hue-constant highlights — the three things that put NDR/NIVUKX above HDR10+/DV),
 * but it has NO live caller: the shipped preview look is re-implemented in the fragment shader, so
 * the oracle's goldens ([NdrToneOperatorGoldenTest]) governed nothing on-device — the two were free
 * to silently diverge. This test makes the oracle GOVERN the live look: if the shader's target peak
 * nits or the diffuse-white anchor drift from the oracle (or vice-versa) the build fails.
 *
 * Pure source-parity lock: it reads the shader text and compares numbers. No shader edit, no manifest
 * re-pin, no SPV regen, no pixel change — this is the golden-first half of "promote NDR/NIVUKX",
 * making G3 provable rather than vibes. (The default-profile flip + on-device ΔE2000/HDR-ramp
 * measurement remain device-gated.)
 */
class NdrNivukxLiveShaderParityTest {

    private val fragSource =
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()

    private fun floatConst(name: String): Double {
        val m = Regex("""const\s+float\s+$name\s*=\s*([0-9]+\.?[0-9]*)""").find(fragSource)
            ?: error("live shader constant `$name` not found in ycbcr_preview.frag")
        return m.groupValues[1].toDouble()
    }

    @Test
    fun liveShaderDeliveryPeakNitsMatchTheOracle() {
        assertEquals(
            "NDR10+ live shader peak nits must match NdrToneOperator.ndr10Plus()",
            NdrToneOperator.ndr10Plus().peakNits,
            floatConst("NDR10_TARGET_PEAK_NITS"),
            0.5,
        )
        assertEquals(
            "NIVUKX live shader peak nits must match NdrToneOperator.nivukxVision()",
            NdrToneOperator.nivukxVision().peakNits,
            floatConst("NIVUKX_TARGET_PEAK_NITS"),
            0.5,
        )
    }

    @Test
    fun liveShaderDiffuseWhiteAnchorMatchesTheOracle() {
        // The nit-accuracy correction: both anchor diffuse white at BT.2408 203 nits (never PQ-encode a
        // normalized signal as if 1.0 == 10000 nits, the historical ~9450-nit diffuse-white bug).
        assertEquals(
            NdrToneOperator.DIFFUSE_WHITE_NITS,
            floatConst("HELIOS_DIFFUSE_WHITE_NITS"),
            0.5,
        )
    }

    @Test
    fun liveShaderToneAndColorPacketMatchesTheOracle() {
        val ndr = NdrToneOperator.ndr10Plus()
        val nivukx = NdrToneOperator.nivukxVision()
        val expected = listOf(
            "NDR10_SIGNAL_GAMMA" to ndr.signalGamma,
            "NDR10_TOE_WIDTH" to ndr.toeWidth,
            "NDR10_TOE_AMOUNT" to ndr.toeAmount,
            "NDR10_HIGHLIGHT_DESAT" to ndr.highlightDesat,
            "NDR10_SHADOW_MIDTONE_LIFT" to ndr.shadowMidtoneLift,
            "NDR10_MIDTONE_CONTRAST" to ndr.midtoneContrast,
            "NDR10_CHROMA_BOOST" to ndr.chromaBoost,
            "NDR10_SKY_CHROMA_BONUS" to ndr.skyChromaBonus,
            "NDR10_LOCAL_DETAIL_STRENGTH" to ndr.localDetailStrength,
            "NIVUKX_SIGNAL_GAMMA" to nivukx.signalGamma,
            "NIVUKX_TOE_WIDTH" to nivukx.toeWidth,
            "NIVUKX_TOE_AMOUNT" to nivukx.toeAmount,
            "NIVUKX_HIGHLIGHT_DESAT" to nivukx.highlightDesat,
            "NIVUKX_SHADOW_MIDTONE_LIFT" to nivukx.shadowMidtoneLift,
            "NIVUKX_MIDTONE_CONTRAST" to nivukx.midtoneContrast,
            "NIVUKX_CHROMA_BOOST" to nivukx.chromaBoost,
            "NIVUKX_SKY_CHROMA_BONUS" to nivukx.skyChromaBonus,
            "NIVUKX_LOCAL_DETAIL_STRENGTH" to nivukx.localDetailStrength
        )
        expected.forEach { (name, value) ->
            assertEquals("$name drifted from NdrToneOperator", value, floatConst(name), 5e-7)
        }
    }

    @Test
    fun liveShaderRoutesDeliveryThroughTheNitAnchoredEntrypoint() {
        // Delivery must go through the nit-anchored ndrDeliveryPqEncode(signal, peakNits), selecting the
        // NIVUKX vs NDR10 peak — i.e. the same nit-accurate transfer the oracle defines, not a raw PQ.
        assertTrue(
            "live delivery must PQ-encode via the nit-anchored ndrDeliveryPqEncode entrypoint",
            fragSource.contains("ndrDeliveryPqEncode("),
        )
        assertTrue(
            "live delivery must select NIVUKX vs NDR10 target peak nits",
            fragSource.contains("NIVUKX_TARGET_PEAK_NITS : NDR10_TARGET_PEAK_NITS"),
        )
        val finish = fragSource.substring(
            fragSource.indexOf("vec3 finishRealtimeComputedNdrDelivery"),
            fragSource.indexOf("void main()")
        )
        assertTrue(finish.contains("ndrBlackPreservingMidtoneLift"))
        assertTrue(finish.contains("ndrProfileMidtoneContrast"))
        assertTrue(finish.contains("ndrProfileColorCharacter"))
        assertTrue(finish.contains("ndrProfileLocalDetailStops"))
        assertTrue(finish.contains("return max(mapped, vec3(0.0))"))
        assertTrue(fragSource.contains("ndrSignalToSdrMonitor"))
    }

    @Test
    fun oracleKeepsDeliveryNitAccurateAtDiffuseWhite() {
        // Guards the anchor the parity lock pins to: the oracle itself must still map the diffuse-white
        // signal to exactly its diffuse-white nits (so pinning the shader to it is meaningful).
        listOf(NdrToneOperator.ndr10Plus(), NdrToneOperator.nivukxVision()).forEach { p ->
            assertEquals(p.diffuseWhiteNits, NdrToneOperator.signalToDisplayNits(1.0, p), 1e-6)
            assertTrue(
                "delivery must never exceed the target peak",
                NdrToneOperator.displayNits(1_000.0, p) <= p.peakNits + 1e-6,
            )
        }
    }
}
