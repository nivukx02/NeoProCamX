package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * s7-f12 / CP9B — GOLDEN LOCK that the additive zeroCopyLiveImport field is neutral by default and
 * is NEVER set by the accumulator (only refreshCapabilitySummary sources it from the native token).
 */
class VulkanImportStatsGoldenTest {

    @Test
    fun defaultImportStatsAreNeutralAndZeroCopyFalse() {
        val s = VulkanImportStats()
        assertFalse("new field must default false (== today's 'unavailable')", s.zeroCopyLiveImport)
        assertEquals(0L, s.importedFrames)
        assertEquals(0L, s.consecutiveFailedImports)
        assertFalse(s.frameImportEnabled)
    }

    @Test
    fun accumulatorNeverSetsZeroCopyFlag() {
        val acc = VulkanImportStatsAccumulator(VulkanImportStats(frameImportEnabled = true))
        repeat(12) { i -> acc.recordSuccessfulImport(width = 1920, height = 1080, format = 35, elapsedMs = 1_000L + i) }
        assertFalse(acc.current.zeroCopyLiveImport)
        acc.recordFailedImport()
        assertFalse(acc.current.zeroCopyLiveImport)
        acc.resetSession(elapsedMs = 5_000L)
        assertFalse(acc.current.zeroCopyLiveImport)
    }
}
