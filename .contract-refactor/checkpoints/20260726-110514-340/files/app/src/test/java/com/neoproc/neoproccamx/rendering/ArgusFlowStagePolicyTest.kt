package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgusFlowStagePolicyTest {

    private fun resolve(
        thermal: Boolean = false,
        budgetMs: Double = 16.6,
        recentMs: Double = 8.0,
        strict: Boolean = false,
        prior: Boolean = true,
        width: Int = 1920,
        height: Int = 1080
    ) = ArgusFlowStagePolicy.resolve(
        thermalThrottling = thermal,
        frameBudgetMs = budgetMs,
        recentFrameMs = recentMs,
        highEndStrict = strict,
        previousFrameAvailable = prior,
        frameWidth = width,
        frameHeight = height
    )

    @Test
    fun noPriorFrameYieldsStaticFlow() {
        val plan = resolve(prior = false)
        assertEquals(ArgusFlowStage.NONE, plan.stage)
        assertFalse(plan.runsBlockMatch)
        assertFalse(plan.runsVariational)
        assertEquals(0, plan.variationalIterations)
        assertFalse(plan.blockMatch.previousValid)
        assertEquals("NO_PRIOR_FRAME", plan.reason)
        assertNull(plan.refine)
        assertNull(plan.select)
    }

    @Test
    fun thermalThrottlingDropsStageBFirst() {
        val plan = resolve(thermal = true)
        assertEquals(ArgusFlowStage.STAGE_A_BLOCK_MATCH, plan.stage)
        assertTrue(plan.runsBlockMatch)
        assertFalse(plan.runsVariational)
        assertEquals(0, plan.variationalIterations)
        assertEquals(8, plan.flowResolutionDivisor)
        assertEquals("STAGE_B_DROPPED_THERMAL", plan.reason)
        assertNull(plan.refine)
        assertNull(plan.select)
    }

    @Test
    fun overBudgetDropsStageB() {
        val plan = resolve(budgetMs = 16.6, recentMs = 24.0)
        assertEquals(ArgusFlowStage.STAGE_A_BLOCK_MATCH, plan.stage)
        assertEquals(0, plan.variationalIterations)
        assertEquals("STAGE_B_DROPPED_BUDGET", plan.reason)
        assertNull(plan.refine)
        assertNull(plan.select)
    }

    @Test
    fun withinBudgetRunsStageAandB() {
        val plan = resolve(recentMs = 8.0)
        assertEquals(ArgusFlowStage.STAGE_A_AND_B_VARIATIONAL, plan.stage)
        assertTrue(plan.runsVariational)
        assertEquals(3, plan.variationalIterations)
        assertEquals(4, plan.flowResolutionDivisor)
        val refine = requireNotNull(plan.refine)
        val select = requireNotNull(plan.select)
        assertEquals(4, refine.toPushConstants().size)
        assertEquals(4, select.toPushConstants().size)
        assertTrue(refine.regularization > 0f)
        assertTrue(select.occlusionThreshold > 0f)
    }

    @Test
    fun highEndStrictRefinesMoreAndFiner() {
        val strict = resolve(strict = true)
        val normal = resolve(strict = false)
        assertEquals(6, strict.variationalIterations)
        assertEquals(2, strict.flowResolutionDivisor)
        assertEquals(3, strict.blockMatch.patchRadius)
        assertTrue(strict.variationalIterations > normal.variationalIterations)
        assertTrue(strict.flowResolutionDivisor < normal.flowResolutionDivisor)
    }

    @Test
    fun blockMatchParamsAreBoundedAndScaleWithResolution() {
        listOf(640, 1280, 1920, 3840, 7680).forEach { w ->
            val params = resolve(width = w).blockMatch
            assertTrue("searchRadius=${params.searchRadius}", params.searchRadius in 4..16)
            assertTrue("coarseStep=${params.coarseStep}", params.coarseStep in 1..4)
            assertTrue("patchRadius=${params.patchRadius}", params.patchRadius in 2..3)
            assertEquals(params.searchRadius.toFloat(), params.maxFlow, 0.0f)
        }
        // Larger frames search at least as far as smaller ones (monotastic, bounded by the cap).
        assertTrue(resolve(width = 3840).blockMatch.searchRadius >= resolve(width = 640).blockMatch.searchRadius)
    }

    // ───────────── s3-f01 / CP54b — live refine-iteration lever + scene-cut detector ─────────────

    @Test
    fun liveRefineIterationsDefaultsToEightAtNeutral() {
        // No-downgrade contract: a cool device with budget headroom must reproduce today's native
        // kOmniRefineIterations = 8 exactly, so the live flow field is bit-stable until the governor moves.
        val iters = ArgusFlowStagePolicy.liveRefineIterations(
            thermalThrottling = false, frameBudgetMs = 16.6, recentFrameMs = 8.0,
        )
        assertEquals(8, iters)
        assertEquals(8, ArgusFlowStagePolicy.LIVE_REFINE_ITERATIONS_DEFAULT)
    }

    @Test
    fun liveRefineIterationsBacksOffMonotonicallyAndStaysEven() {
        val neutral = ArgusFlowStagePolicy.liveRefineIterations(false, 16.6, 8.0)
        val overBudget = ArgusFlowStagePolicy.liveRefineIterations(false, 16.6, 24.0)
        val thermal = ArgusFlowStagePolicy.liveRefineIterations(true, 16.6, 8.0)
        val both = ArgusFlowStagePolicy.liveRefineIterations(true, 16.6, 24.0)
        // Monotonic: more pressure never raises the iteration count.
        assertTrue("over-budget must not exceed neutral", overBudget <= neutral)
        assertTrue("thermal must not exceed over-budget", thermal <= overBudget)
        assertTrue("thermal+budget must not exceed thermal", both <= thermal)
        // Every level is even and within [MIN, DEFAULT] so the ping-pong always lands in flowLowA.
        listOf(neutral, overBudget, thermal, both).forEach {
            assertEquals("must be even (ends in flowLowA): $it", 0, it % 2)
            assertTrue("must stay >= MIN: $it", it >= ArgusFlowStagePolicy.LIVE_REFINE_ITERATIONS_MIN)
            assertTrue("must stay <= DEFAULT: $it", it <= ArgusFlowStagePolicy.LIVE_REFINE_ITERATIONS_DEFAULT)
        }
        assertTrue("backoff must actually reduce work under load", both < neutral)
    }

    @Test
    fun liveRefineIterationsRecoversWhenHeadroomReturns() {
        val underLoad = ArgusFlowStagePolicy.liveRefineIterations(true, 16.6, 24.0)
        val recovered = ArgusFlowStagePolicy.liveRefineIterations(false, 16.6, 8.0)
        assertTrue("must recover toward the default when load clears", recovered > underLoad)
        assertEquals(ArgusFlowStagePolicy.LIVE_REFINE_ITERATIONS_DEFAULT, recovered)
    }

    @Test
    fun isSceneCutSpikeClassifiesByThreshold() {
        val t = ArgusFlowStagePolicy.SCENE_CUT_RESIDUAL_THRESHOLD
        // A large post-flow residual (no flow reconciles the frames) is a cut; a tiny one is not.
        assertTrue(ArgusFlowStagePolicy.isSceneCutSpike(0.5f))
        assertFalse(ArgusFlowStagePolicy.isSceneCutSpike(0.02f))
        // Exactly at the threshold counts as a cut; just below does not. Negative residual is clamped off.
        assertTrue(ArgusFlowStagePolicy.isSceneCutSpike(t))
        assertFalse(ArgusFlowStagePolicy.isSceneCutSpike(t - 1e-3f))
        assertFalse(ArgusFlowStagePolicy.isSceneCutSpike(-1f))
    }

    @Test
    fun pushConstantsReinterpretIntFieldsAndKeepFloats() {
        val params = resolve(strict = true).blockMatch
        val packed = params.toPushConstants()
        assertEquals(6, packed.size)
        assertEquals(params.searchRadius, packed[0].toRawBits())
        assertEquals(params.coarseStep, packed[1].toRawBits())
        assertEquals(params.patchRadius, packed[2].toRawBits())
        assertEquals(params.maxFlow, packed[3], 0.0f)
        assertEquals(1.0f, packed[4], 0.0f)
        assertEquals(params.minTexture, packed[5], 0.0f)
        // No prior frame -> previousValid float slot is 0.
        assertEquals(0.0f, resolve(prior = false).blockMatch.toPushConstants()[4], 0.0f)
    }
}
