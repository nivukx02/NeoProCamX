package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChronosTemporalPolicyTest {

    private fun resolve(
        flow: Boolean = true,
        cut: Boolean = false,
        strict: Boolean = false,
        thermal: Boolean = false
    ) = ChronosTemporalPolicy.resolve(
        flowAvailable = flow,
        sceneCut = cut,
        highEndStrict = strict,
        thermalThrottling = thermal
    )

    @Test
    fun noFlowDisablesTemporalAccumulation() {
        val plan = resolve(flow = false)
        assertFalse(plan.active)
        assertEquals(0f, plan.warpParams.maxTemporalAlpha, 0.0f)
        assertEquals("NO_FLOW", plan.reason)
    }

    @Test
    fun sceneCutResetsTemporalHistory() {
        val plan = resolve(cut = true)
        assertFalse(plan.active)
        assertEquals(0f, plan.warpParams.maxTemporalAlpha, 0.0f)
        assertEquals("SCENE_CUT", plan.reason)
    }

    @Test
    fun activeTemporalBlendStartsFromZeroConfidence() {
        val plan = resolve()
        assertTrue(plan.active)
        assertEquals(0f, plan.warpParams.baseTemporalAlpha, 0.0f)
        assertTrue(plan.warpParams.maxTemporalAlpha > plan.warpParams.baseTemporalAlpha)
        assertEquals(0.85f, plan.warpParams.maxTemporalAlpha, 0.0001f)
        assertEquals("MOTION_REJECTING_TEMPORAL", plan.reason)
    }

    @Test
    fun strictRaisesAndThermalLowersMaxBlend() {
        assertEquals(0.92f, resolve(strict = true).warpParams.maxTemporalAlpha, 0.0001f)
        assertEquals(0.60f, resolve(thermal = true).warpParams.maxTemporalAlpha, 0.0001f)
        // Thermal wins over strict so accumulation stays bounded under heat.
        assertEquals(0.60f, resolve(strict = true, thermal = true).warpParams.maxTemporalAlpha, 0.0001f)
        // Shimmer guard: never fully replace the current frame.
        assertTrue(resolve(strict = true).warpParams.maxTemporalAlpha < 1.0f)
    }

    @Test
    fun sceneCutDetectionUsesNormalizedThreshold() {
        assertTrue(ChronosTemporalPolicy.isSceneCut(0.40f))
        assertFalse(ChronosTemporalPolicy.isSceneCut(0.20f))
        assertEquals(6, resolve().warpParams.toPushConstants().size)
    }

    // ───────────────────── s3-f02 / CP52b — live warp temporal lever ─────────────────────

    @Test
    fun liveWarpAlphasNeutralEqualsCp52NativeDefault() {
        // No-downgrade contract: the LIVE warp lever must default to the CP52 native struct values
        // (0.0 / 0.9) — not the older resolve() value (0.85) — so the live warp is bit-for-bit unchanged.
        val (base, max) = ChronosTemporalPolicy.liveWarpAlphas(thermalThrottling = false)
        assertEquals(0.0f, base, 0.0f)
        assertEquals(0.9f, max, 0.0f)
        assertEquals(0.0f, ChronosTemporalPolicy.LIVE_BASE_TEMPORAL_ALPHA, 0.0f)
        assertEquals(0.9f, ChronosTemporalPolicy.LIVE_MAX_TEMPORAL_ALPHA, 0.0f)
    }

    @Test
    fun liveWarpAlphasBackOffUnderThermalNeverRaised() {
        val (_, neutralMax) = ChronosTemporalPolicy.liveWarpAlphas(false)
        val (thermalBase, thermalMax) = ChronosTemporalPolicy.liveWarpAlphas(true)
        assertTrue("thermal must not raise the max blend: $thermalMax vs $neutralMax", thermalMax <= neutralMax)
        assertEquals(0.6f, thermalMax, 0.0f)
        assertTrue("max stays above base", thermalMax > thermalBase)
        assertTrue("base never replaces the current frame", thermalMax < 1.0f)
    }
}
