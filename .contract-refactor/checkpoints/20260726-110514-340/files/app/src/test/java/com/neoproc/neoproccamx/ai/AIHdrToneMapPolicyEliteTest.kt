package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.recording.HdrImagingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s4-f05 / CP30 — locks the elite hardening of the AI HDR tone-map policy: intent hysteresis (no
 * tone flicker), bounded profiles (no NaN/blow-out/crush), skin preservation, and the param bridge
 * that drives the s4-f03 GPU peak-nits curve. The default CPU path is golden-locked (no-downgrade).
 */
class AIHdrToneMapPolicyEliteTest {

    private fun stats(clip: Float, mean: Float, shadow: Float, peak: Float = mean, drSpan: Float = 0f) =
        HdrLinearStats(clippedRatio = clip, meanLuma = mean, shadowRatio = shadow, peakLuma = peak, dynamicRangeSpan = drSpan)

    // ── Intent hysteresis ──────────────────────────────────────────────────────────────

    @Test
    fun intentHysteresisKillsFlickerOnAWobblingScene() {
        // clippedRatio wobbles ±0.003 around the BALANCED↔HIGHLIGHT boundary (0.015).
        val clip = floatArrayOf(0.013f, 0.018f, 0.0135f, 0.0185f, 0.014f, 0.019f, 0.0135f, 0.018f)
        var rawTransitions = 0
        var hystTransitions = 0
        var prevRaw: HdrSceneIntent? = null
        var prevHyst: HdrSceneIntent? = null
        for (c in clip) {
            val s = stats(c, 0.40f, 0.10f, peak = 1.0f)
            val raw = AIHdrToneMapPolicy.resolveProfile(s).sceneIntent
            val hyst = AIHdrToneMapPolicy.selectIntentWithHysteresis(s, prevHyst)
            if (prevRaw != null && raw != prevRaw) rawTransitions++
            if (prevHyst != null && hyst != prevHyst) hystTransitions++
            prevRaw = raw; prevHyst = hyst
        }
        assertTrue("instantaneous classifier must flip-flop on the wobble ($rawTransitions)", rawTransitions >= 3)
        assertTrue("hysteresis must hold a stable intent ($hystTransitions)", hystTransitions <= 1)
    }

    @Test
    fun hysteresisStillSwitchesOnADecisiveSceneChange() {
        val dark = stats(0.0f, 0.10f, 0.50f) // SHADOW_PRIORITY
        val bright = stats(0.10f, 0.60f, 0.0f, peak = 3.0f, drSpan = 1.5f) // EXTREME_DYNAMIC_RANGE
        val first = AIHdrToneMapPolicy.selectIntentWithHysteresis(dark, null)
        val second = AIHdrToneMapPolicy.selectIntentWithHysteresis(bright, first)
        assertTrue("must still react to a decisive change", second != first)
        assertEquals(HdrSceneIntent.EXTREME_DYNAMIC_RANGE, second)
    }

    // ── Bounds ───────────────────────────────────────────────────────────────────────────

    @Test
    fun boundedProfileNeverBlowsForDegenerateStats() {
        val clips = floatArrayOf(-1f, 0f, 0.5f, 1f, 5f)
        val means = floatArrayOf(-1f, 0f, 0.18f, 0.5f, 2f, 100f)
        val shadows = floatArrayOf(0f, 0.5f, 1f, 3f)
        for (c in clips) for (m in means) for (s in shadows) {
            val p = AIHdrToneMapPolicy.boundedProfile(AIHdrToneMapPolicy.resolveProfile(stats(c, m, s, peak = m, drSpan = c)))
            assertTrue("exposureBoost bounded ($p)", p.exposureBoost in 0.25f..4.0f)
            assertTrue("whitePoint bounded ($p)", p.whitePoint in 1.0f..12.0f)
            assertTrue("shadowLift never crushes ($p)", p.shadowLift in 0.0f..0.10f)
            assertTrue("blackFloor bounded ($p)", p.blackFloor in 0.0f..0.03f)
            assertTrue("maxCLL bounded ($p)", p.maxContentLightLevel in 203f..10000f)
            assertTrue("colorPreservation bounded ($p)", p.colorPreservation in 0.0f..1.0f)
        }
    }

    // ── GPU-curve param bridge (drives s4-f03) ───────────────────────────────────────────

    @Test
    fun toKneePushConstantsMatchesF03LayoutAndStaysInSafeRange() {
        val profile = AIHdrToneMapPolicy.resolveProfile(stats(0.08f, 0.5f, 0.05f, peak = 2.6f, drSpan = 1.3f))
        val pc = AIHdrToneMapPolicy.toKneePushConstants(profile)
        // [maxCLL, kneePoint, kneeHardness, targetPeakNits] — slot order matches KneeCurveParams.
        assertEquals("4 slots like the knee push block", 4, pc.size)
        assertTrue("maxCLL in range", pc[0] in 203f..10000f)
        assertTrue("kneePoint self-clamps to the shader-safe band", pc[1] in 0.55f..0.75f)
        assertTrue("kneeHardness self-clamps", pc[2] in 1.0f..3.0f)
        assertTrue("targetPeakNits in range", pc[3] in 203f..10000f)
        // Extreme-DR scenes pick the lower (NIVUKX-style) knee.
        assertEquals(0.58f, pc[1], 0f)
    }

    @Test
    fun gpuDriveDefaultsOnForHighEndToneParity() {
        assertTrue("policy drives the GPU curve by default for high-end tone parity",
            AIHdrToneMapPolicy.POLICY_DRIVES_GPU_TONE_DEFAULT)
    }

    // ── Skin preservation ─────────────────────────────────────────────────────────────────

    @Test
    fun skinToneWeightIsHighForSkinAndLowForNonSkin() {
        val skin = HdrSkinToneMath.skinToneWeight(0.34f, 0.20f, 0.14f)   // warm mid skin
        val sky = HdrSkinToneMath.skinToneWeight(0.10f, 0.18f, 0.40f)    // cool blue
        assertTrue("skin must register ($skin)", skin > 0.25f)
        assertTrue("non-skin must not ($sky)", sky < 0.10f)
    }

    @Test
    fun lumaPreservingToneMapKeepsSkinWarmAndBounded() {
        val out = HdrImagingMath.toneMapBt2100LumaPreservingRgb(r = 0.30, g = 0.18, b = 0.12)
        assertTrue("skin must stay warm: r ≥ g (${out.r} vs ${out.g})", out.r >= out.g - 0.02)
        assertTrue("skin must stay warm: g ≥ b (${out.g} vs ${out.b})", out.g >= out.b - 0.02)
        assertTrue("output bounded", out.r in 0.0..1.0 && out.g in 0.0..1.0 && out.b in 0.0..1.0)
    }

    // ── No-downgrade golden lock (CPU path unchanged) ─────────────────────────────────────

    @Test
    fun defaultResolveProfileIsGoldenLocked() {
        val p = AIHdrToneMapPolicy.resolveProfile(stats(0.0f, 0.40f, 0.10f))
        assertEquals(HdrSceneIntent.BALANCED, p.sceneIntent)
        assertEquals(0.99f, p.colorPreservation, 0f)
        assertEquals(0.0001f, p.blackFloor, 0f)
        assertTrue(p.dynamicMetadataEnabled)
    }
}
