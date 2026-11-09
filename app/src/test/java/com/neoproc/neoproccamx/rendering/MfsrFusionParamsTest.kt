package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.CameraSensorNoiseModelPolicy
import com.neoproc.neoproccamx.camera.RawDescriptorNoiseInputs
import com.neoproc.neoproccamx.camera.SensorNoiseModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * s1-f06 / CP12 / CP13 — golden/host tests for [MfsrFusionParams]:
 *  - the noise-model → push-constant mapping (the missing s1-f06 hop),
 *  - the heteroscedastic 3σ ghost veto host port (admit good / reject ghost, and the `noise==0 ⇒
 *    reject-all` pathology that proves why the dummy path fuses nothing),
 *  - the per-frame flow-layer indexing invariant (CP13),
 *  - adaptive burst-depth clamping.
 * All pure JVM — no device, no Vulkan.
 */
class MfsrFusionParamsTest {

    // ── Noise model → push constants (the s1-f06 boundary) ───────────────────────────────────────

    @Test
    fun fromNoiseInputsMapsShotReadToNoiseCoefficients() {
        val params = MfsrFusionParams.fromNoiseInputs(
            RawDescriptorNoiseInputs(noiseS = 0.0031f, noiseO = 0.00042f),
            activeFrameCount = 6
        )
        assertEquals(0.0031f, params.noiseS, 0f)
        assertEquals(0.00042f, params.noiseO, 0f)
        assertEquals(6, params.activeFrameCount)
        assertEquals(1f, params.gyroOffsetScale, 0f)
    }

    @Test
    fun fromNoiseModelMatchesDescriptorNoiseInputsChain() {
        val model = SensorNoiseModel(
            shotNoiseCoefficient = 0.0026f,
            readNoiseCoefficient = 0.00050f,
            source = "test"
        )
        val viaModel = MfsrFusionParams.fromNoiseModel(model, activeFrameCount = 4)
        // Must equal going through the real descriptor boundary CameraSensorNoiseModelPolicy uses.
        val inputs = CameraSensorNoiseModelPolicy.descriptorNoiseInputs(model)
        val viaInputs = MfsrFusionParams.fromNoiseInputs(inputs, activeFrameCount = 4)
        assertEquals(viaInputs, viaModel)
        assertEquals(model.shotNoiseCoefficient, viaModel.noiseS, 0f)
        assertEquals(model.readNoiseCoefficient, viaModel.noiseO, 0f)
    }

    @Test
    fun realProfileReachesPushConstantsNonZero() {
        // The whole point of CP12: a real profile must never be stranded at noise=0 by this boundary.
        val params = MfsrFusionParams.fromNoiseModel(SensorNoiseModel.FALLBACK, activeFrameCount = 5)
        val packed = params.toPushConstants()
        assertEquals(5, packed[0].toRawBits())
        assertTrue("noiseS must be strictly positive for a real profile", packed[1] > 0f)
        assertTrue("noiseO must be strictly positive for a real profile", packed[2] > 0f)
        assertEquals(1f, packed[3], 0f)
    }

    // ── Heteroscedastic 3σ ghost veto (host port of mfsr_fusion.comp:90-91, 113-115) ─────────────

    @Test
    fun sigmaThresholdMatchesShaderFormula() {
        val s = 0.0026f
        val o = 0.00050f
        val ref = 0.5f
        val expected = 3f * sqrt(s * ref + o)
        assertEquals(expected, MfsrFusionParams.ghostSigmaThreshold(ref, s, o), 0f)
    }

    @Test
    fun zeroNoiseVetoRejectsEveryDifferingNonReferenceFrame() {
        // noiseS == noiseO == 0  ⇒  sigmaThresh == 0  ⇒  any non-reference frame that differs at all is a
        // ghost. This is exactly the dummy/zero-noise pathology: only the (garbage) reference contributes.
        assertEquals(0f, MfsrFusionParams.ghostSigmaThreshold(0.5f, 0f, 0f), 0f)
        // Reference frame (f == 0) is always admitted.
        assertFalse(MfsrFusionParams.isGhost(0, sampleSignal = 0.9f, referenceSignal = 0.5f, noiseS = 0f, noiseO = 0f))
        // f > 0 with any positive deviation -> vetoed.
        assertTrue(MfsrFusionParams.isGhost(1, sampleSignal = 0.5001f, referenceSignal = 0.5f, noiseS = 0f, noiseO = 0f))
        assertTrue(MfsrFusionParams.isGhost(3, sampleSignal = 0.4f, referenceSignal = 0.5f, noiseS = 0f, noiseO = 0f))
        // Exactly equal (delta == 0) is the strict-inequality boundary: admitted.
        assertFalse(MfsrFusionParams.isGhost(2, sampleSignal = 0.5f, referenceSignal = 0.5f, noiseS = 0f, noiseO = 0f))
    }

    @Test
    fun realNoiseAdmitsAlignedFrameAndRejectsGhost() {
        val s = SensorNoiseModel.FALLBACK.shotNoiseCoefficient
        val o = SensorNoiseModel.FALLBACK.readNoiseCoefficient
        val ref = 0.5f
        val sigma = MfsrFusionParams.ghostSigmaThreshold(ref, s, o)
        assertTrue("sanity: a real profile yields a positive 3σ window", sigma > 0f)

        // A well-aligned good frame deviates well within 3σ -> admitted (contributes to the fusion).
        val good = ref + sigma * 0.25f
        assertTrue(MfsrFusionParams.admitsFrame(1, sampleSignal = good, referenceSignal = ref, noiseS = s, noiseO = o))

        // A moving subject deviates far beyond 3σ -> rejected (no ghosting).
        val ghost = ref + sigma * 4f
        assertTrue(MfsrFusionParams.isGhost(2, sampleSignal = ghost, referenceSignal = ref, noiseS = s, noiseO = o))
    }

    // ── CP13 per-frame flow indexing ─────────────────────────────────────────────────────────────

    @Test
    fun perFrameFlowIndexingIsIdentityAndDistinct() {
        // Each fused frame reads its OWN flow layer (layer == frame index) — not one shared field (the bug).
        val layers = (0 until MfsrFusionParams.MAX_ACTIVE_FRAMES).map { MfsrFusionParams.flowLayerForFrame(it) }
        assertEquals((0 until MfsrFusionParams.MAX_ACTIVE_FRAMES).toList(), layers)
        assertEquals("flow layers must be distinct per frame", layers.size, layers.toSet().size)
    }

    // ── Adaptive burst depth ─────────────────────────────────────────────────────────────────────

    @Test
    fun adaptiveFrameCountClampsToBurstRange() {
        assertEquals(MfsrFusionParams.MIN_ACTIVE_FRAMES, MfsrFusionParams.clampActiveFrameCount(1))
        assertEquals(MfsrFusionParams.MIN_ACTIVE_FRAMES, MfsrFusionParams.clampActiveFrameCount(3))
        assertEquals(4, MfsrFusionParams.clampActiveFrameCount(4))
        assertEquals(MfsrFusionParams.MAX_ACTIVE_FRAMES, MfsrFusionParams.clampActiveFrameCount(8))
        assertEquals(MfsrFusionParams.MAX_ACTIVE_FRAMES, MfsrFusionParams.clampActiveFrameCount(20))
        // fromNoiseInputs applies the clamp.
        assertEquals(
            MfsrFusionParams.MAX_ACTIVE_FRAMES,
            MfsrFusionParams.fromNoiseInputs(RawDescriptorNoiseInputs(0.003f, 0.0005f), activeFrameCount = 99).activeFrameCount
        )
    }
}
