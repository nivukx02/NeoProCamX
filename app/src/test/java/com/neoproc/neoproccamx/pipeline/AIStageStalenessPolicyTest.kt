package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f01 / CP92 — bounded-staleness math (pure). Proves "a stage that skipped N frames reports
 * age >= N" and the consumer freshness gate.
 */
class AIStageStalenessPolicyTest {

    @Test
    fun oldestStageAgeFramesIsTheLargestGap() {
        assertEquals(6L, AIStageStalenessPolicy.oldestStageAgeFrames(100L, longArrayOf(100L, 97L, 94L)))
        assertEquals(0L, AIStageStalenessPolicy.oldestStageAgeFrames(100L, longArrayOf(100L, 100L)))
        // empty => nothing to be stale about
        assertEquals(0L, AIStageStalenessPolicy.oldestStageAgeFrames(100L, longArrayOf()))
    }

    @Test
    fun oldestStageAgeMsIsTheLargestGap() {
        assertEquals(180L, AIStageStalenessPolicy.oldestStageAgeMs(1_000L, longArrayOf(1_000L, 820L)))
        assertEquals(0L, AIStageStalenessPolicy.oldestStageAgeMs(1_000L, longArrayOf()))
    }

    @Test
    fun consumerFreshnessGate() {
        assertTrue(AIStageStalenessPolicy.isFreshEnough(oldestStageAgeMs = 180L, budgetMs = 200L))
        assertFalse(AIStageStalenessPolicy.isFreshEnough(oldestStageAgeMs = 250L, budgetMs = 200L))
        // budget <= 0 disables the gate (today's accept-all)
        assertTrue(AIStageStalenessPolicy.isFreshEnough(oldestStageAgeMs = 10_000L, budgetMs = 0L))
    }
}
