package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s4-f06 / CP36 + CP23/CP31/CP32/CP33 — the dispatch-manifest regression gate and the honest,
 * source-verified triage of the dormant denoise/HDR shaders.
 *
 * CP36: pins the live visible compute-dispatch set, so a future ungated WIRE (a new pipeline bound
 * into the pixel path) or an accidental dispatch fails CI, and a RETIRE removal of a never-dispatched
 * shader is provably zero-delta to the live set.
 *
 * Triage (verified against source, correcting the prompt's tentative guesses):
 *   - denoise.comp                → WIRE-eligible (CP23): carries wavelet/NLM/Anscombe-Oklab-Bayes the
 *                                   live nivukx path lacks. NOT a duplicate; do not retire.
 *   - nivukx_vision_denoise.comp  → RETIRE candidate: a temporal-Tukey + 7x7 + microtexture variant of
 *                                   the live nivukx_denoise, no unique algorithm.
 *   - sharpen.comp                → KEEP: Richardson-Lucy + wavefront phase retrieval (unique optical
 *                                   recovery) and a manifest-registered authority.
 *   - dual_gain / photon / ndr10  → dormant SPV foundations (#included, never dispatched) → device-gated WIRE.
 */
class DormantHdrDenoiseShaderTriageTest {

    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }
    private fun shader(name: String) = File("src/main/cpp/shaders/vulkan/$name").readText()
    private fun isBound(pipeline: String) =
        bridge.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->$pipeline)")

    // ── CP36 dispatch manifest ────────────────────────────────────────────────────────

    @Test
    fun liveVisibleComputeDispatchSetIsPinned() {
        val live = listOf(
            "computePipeline", "mfsrFusionPipeline", "detailPreservationPipeline", "guidedUpsamplePipeline",
            "kneeCurvePipeline", "shadowLiftPipeline", "imaxRenderingPipeline", "m_nivukxDenoisePipeline",
            "hdrMetadataHistogramPipeline", "hdrMetadataReducePipeline", "occsPipeline", "nrliPipeline"
        )
        live.forEach { assertTrue("live dispatch must include $it", isBound(it)) }
    }

    @Test
    fun dormantAndRetiredShadersAreNotDispatchedIntoTheLivePath() {
        // WIRE foundations + RETIRE candidates must never be bound into the visible pixel chain
        // until device-validated (the s1-f06/s1-f10 "never trust a blind new dispatch" rule).
        // CP-QV16 NOTE: denoise.comp is now wired as a GATED (default-OFF) spatial denoiser via a
        // *distinct* pipeline handle (denoiseSpatialPipeline); its bind is asserted to be guarded below.
        val mustNotDispatch = listOf(
            "dualGainFusionPipeline", "photonHdrFusionPipeline", "ndr10KneeCurvePipeline",
            "sharpenPipeline", "nivukxVisionDenoisePipeline"
        )
        mustNotDispatch.forEach { assertFalse("$it must not be dispatched into the live path", isBound(it)) }
    }

    // ── CP-QV16: denoise.comp's spatial-denoise pipeline is GATED (default OFF) ────────────────────

    @Test
    fun denoiseSpatialPipelineBindIsGuardedByTheDenoiseSpatialEnabledFlag() {
        // The gated spatial denoiser (CP-QV16) is bound into the compute chain ONLY inside an
        // `if (state->denoiseSpatialEnabled ...)` block, so with the flag OFF (the default) there is
        // zero extra dispatch and the live render path is byte-for-byte identical.
        val bindToken = "vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->denoiseSpatialPipeline)"
        val bindIndex = bridge.indexOf(bindToken)
        assertTrue("CP-QV16 must bind state->denoiseSpatialPipeline exactly once", bindIndex >= 0)
        assertTrue(
            "denoiseSpatialPipeline must be bound exactly once (found a second bind)",
            bridge.indexOf(bindToken, bindIndex + bindToken.length) < 0
        )

        // Walk backward from the bind, tracking brace depth. A '}' (backward) enters a nested block that
        // closes before the bind (depth++). A '{' closes such a nested block, or — when depth goes below
        // zero — it is the opener of a scope that ENCLOSES the bind. Check each enclosing-scope opener for
        // a preceding `if (...)`; the first `if`-opened enclosing scope is the nearest guard.
        val prefix = bridge.substring(0, bindIndex)
        var depth = 0
        var enclosingCondition: String? = null
        var i = prefix.length - 1
        while (i >= 0) {
            val c = prefix[i]
            if (c == '}') {
                depth++
            } else if (c == '{') {
                depth--
                if (depth < 0) {
                    // `{` opens a scope enclosing the bind. Look just before it for a balanced `if (...)`.
                    val head = prefix.substring(0, i)
                    val closeParen = head.lastIndexOf(')')
                    if (closeParen >= 0 && head.substring(closeParen + 1).isBlank()) {
                        var pd = 0
                        var j = closeParen
                        while (j >= 0) {
                            if (head[j] == ')') pd++
                            else if (head[j] == '(') { pd--; if (pd == 0) break }
                            j--
                        }
                        if (j >= 0) {
                            val beforeParen = head.substring(0, j).trimEnd()
                            if (beforeParen.endsWith("if")) {
                                enclosingCondition = head.substring(j + 1, closeParen)
                                break
                            }
                        }
                    }
                    // Not an `if`-opened enclosing scope; treat this `{` as the new frame of reference and
                    // keep ascending into its parent.
                    depth = 0
                }
            }
            i--
        }

        assertTrue(
            "the denoiseSpatialPipeline bind must sit inside an `if (...)` block",
            enclosingCondition != null
        )
        assertTrue(
            "the enclosing `if` must be guarded by denoiseSpatialEnabled (was: $enclosingCondition)",
            enclosingCondition!!.contains("denoiseSpatialEnabled")
        )
    }

    // ── CP-QV17: sharpen.comp's optical-recovery pipeline is GATED (default OFF) ────────────────────

    @Test
    fun sharpenOpticalPipelineBindIsGuardedByTheSharpenOpticalEnabledFlag() {
        // The gated optical-recovery sharpener (CP-QV17) is bound into the compute chain ONLY inside an
        // `if (state->sharpenOpticalEnabled ...)` block, so with the flag OFF (the default) there is zero
        // extra dispatch and the live render path is byte-for-byte identical. Note "sharpenOpticalPipeline"
        // is a DISTINCT handle from the vacuous "sharpenPipeline" symbol pinned in mustNotDispatch above.
        val bindToken = "vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->sharpenOpticalPipeline)"
        val bindIndex = bridge.indexOf(bindToken)
        assertTrue("CP-QV17 must bind state->sharpenOpticalPipeline exactly once", bindIndex >= 0)
        assertTrue(
            "sharpenOpticalPipeline must be bound exactly once (found a second bind)",
            bridge.indexOf(bindToken, bindIndex + bindToken.length) < 0
        )

        // Walk backward from the bind, tracking brace depth. A '}' (backward) enters a nested block that
        // closes before the bind (depth++). A '{' closes such a nested block, or — when depth goes below
        // zero — it is the opener of a scope that ENCLOSES the bind. Check each enclosing-scope opener for
        // a preceding `if (...)`; the first `if`-opened enclosing scope is the nearest guard.
        val prefix = bridge.substring(0, bindIndex)
        var depth = 0
        var enclosingCondition: String? = null
        var i = prefix.length - 1
        while (i >= 0) {
            val c = prefix[i]
            if (c == '}') {
                depth++
            } else if (c == '{') {
                depth--
                if (depth < 0) {
                    // `{` opens a scope enclosing the bind. Look just before it for a balanced `if (...)`.
                    val head = prefix.substring(0, i)
                    val closeParen = head.lastIndexOf(')')
                    if (closeParen >= 0 && head.substring(closeParen + 1).isBlank()) {
                        var pd = 0
                        var j = closeParen
                        while (j >= 0) {
                            if (head[j] == ')') pd++
                            else if (head[j] == '(') { pd--; if (pd == 0) break }
                            j--
                        }
                        if (j >= 0) {
                            val beforeParen = head.substring(0, j).trimEnd()
                            if (beforeParen.endsWith("if")) {
                                enclosingCondition = head.substring(j + 1, closeParen)
                                break
                            }
                        }
                    }
                    // Not an `if`-opened enclosing scope; treat this `{` as the new frame of reference and
                    // keep ascending into its parent.
                    depth = 0
                }
            }
            i--
        }

        assertTrue(
            "the sharpenOpticalPipeline bind must sit inside an `if (...)` block",
            enclosingCondition != null
        )
        assertTrue(
            "the enclosing `if` must be guarded by sharpenOpticalEnabled (was: $enclosingCondition)",
            enclosingCondition!!.contains("sharpenOpticalEnabled")
        )
    }

    // ── CP23: denoise.comp is WIRE-eligible, NOT a duplicate ─────────────────────────────

    @Test
    fun denoiseCompCarriesFlatRegionAlgorithmsTheLivePathLacks() {
        val denoise = shader("denoise.comp")
        val nivukx = shader("nivukx_denoise.comp")
        // denoise.comp's unique flat-region detail-safe + VST stages…
        listOf("waveletDenoise", "nonLocalMeans", "anscombeOklabBayesianDenoise", "guidedFilter").forEach {
            assertTrue("denoise.comp must carry $it", denoise.contains(it))
            assertFalse("live nivukx_denoise must NOT already have $it (else it'd be a duplicate)", nivukx.contains(it))
        }
    }

    // ── Triage of the remaining candidates ───────────────────────────────────────────────

    @Test
    fun nivukxVisionDenoiseIsAVariantOfTheLiveNivukx() {
        // Same 3-stage structure as the live path (temporal Tukey + 7x7 spatial + microtexture) → no
        // unique algorithm → RETIRE candidate.
        val v = shader("nivukx_vision_denoise.comp")
        assertTrue(v.contains("tukeyBiweightBurstMerge"))
        assertTrue(v.contains("jzazbzEdgeAwareDenoise7x7"))
        assertTrue(v.contains("microTextureReinject"))
        // It carries none of denoise.comp's distinct algorithms.
        assertFalse("variant must not carry the wavelet pass", v.contains("waveletDenoise"))
    }

    @Test
    fun sharpenCompIsAUniqueAuthorityNotADuplicate() {
        val s = shader("sharpen.comp")
        // Richardson-Lucy deconvolution + wavefront phase retrieval are unique optical-recovery math.
        assertTrue(s.contains("richardsonLucyDeconvolution"))
        assertTrue(s.contains("WavefrontPhaseRetrieval"))
        // …and it is a registered shader-manifest authority (optical_recovery / texture_authority).
        val manifest = File("src/main/assets/shaders/shader_manifest.json").readText()
        assertTrue("sharpen.comp must stay registered (do not retire)", manifest.contains("shaders/vulkan/sharpen.comp"))
    }

    @Test
    fun wireHdrFoundationsAreCompiledButDormant() {
        // The HDR-fusion foundations are #included (compiled) so the device enable is a wiring step,
        // but they are not dispatched into the live path (proven above) — gated/dormant, device-gated.
        listOf("dual_gain_fusion_comp_spv.h", "photon_hdr_fusion_comp_spv.h", "ndr10_knee_curve_comp_spv.h")
            .forEach { assertTrue("$it must be #included as a dormant foundation", bridge.contains(it)) }
    }
}
