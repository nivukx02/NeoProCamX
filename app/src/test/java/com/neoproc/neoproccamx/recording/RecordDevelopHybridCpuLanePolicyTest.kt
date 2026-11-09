package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pure routing + memory-guard decisions for the concurrent CPU+GPU develop hybrid
 * ([RecordDevelopHybridCpuLanePolicy]) — deterministic and Android-free.
 */
class RecordDevelopHybridCpuLanePolicyTest {

    @Test
    fun productionShareUsesBothLanesWithoutDominatingTheGpuPath() {
        assertTrue(RecordDevelopHybridCpuLanePolicy.PRODUCTION_CPU_SHARE_BPS > 0)
        assertTrue(
            RecordDevelopHybridCpuLanePolicy.PRODUCTION_CPU_SHARE_BPS <
                RecordDevelopHybridCpuLanePolicy.BASIS / 2
        )
        val cpuFrames = (0L until RecordDevelopHybridCpuLanePolicy.BASIS.toLong()).count {
            RecordDevelopHybridCpuLanePolicy.routeToCpuLane(
                it,
                RecordDevelopHybridCpuLanePolicy.PRODUCTION_CPU_SHARE_BPS
            )
        }
        assertEquals(RecordDevelopHybridCpuLanePolicy.PRODUCTION_CPU_SHARE_BPS, cpuFrames)
    }

    @Test
    fun routeToCpuLaneSplitsExactlyByShareAndSpreadsEvenly() {
        val n = 10_000L
        for (bps in intArrayOf(0, 3000, 5000, 6000, 8000)) {
            var cpu = 0L
            for (i in 0 until n) if (RecordDevelopHybridCpuLanePolicy.routeToCpuLane(i, bps)) cpu++
            // Bresenham over exactly BASIS frames telescopes to exactly `bps` CPU frames.
            assertEquals("share $bps", bps.toLong(), cpu)
            assertTrue(cpu in 0..n)
        }
    }

    @Test
    fun frameZeroStaysOnGpuLane() {
        // Frame 0 warms the accelerator's first-frame parity gate, so it never routes to the CPU lane.
        assertFalse(RecordDevelopHybridCpuLanePolicy.routeToCpuLane(0L, 6000))
        assertFalse(RecordDevelopHybridCpuLanePolicy.routeToCpuLane(0L, 8000))
    }

    @Test
    fun zeroShareNeverRoutesToCpu() {
        for (i in 0L until 100L) assertFalse(RecordDevelopHybridCpuLanePolicy.routeToCpuLane(i, 0))
    }

    @Test
    fun shareIsClampedToBounds() {
        RecordDevelopHybridCpuLanePolicy.setCpuShareBps(999_999)
        assertEquals(RecordDevelopHybridCpuLanePolicy.MAX_CPU_SHARE_BPS, RecordDevelopHybridCpuLanePolicy.cpuShareBps)
        RecordDevelopHybridCpuLanePolicy.setCpuShareBps(-5)
        assertEquals(RecordDevelopHybridCpuLanePolicy.MIN_CPU_SHARE_BPS, RecordDevelopHybridCpuLanePolicy.cpuShareBps)
        RecordDevelopHybridCpuLanePolicy.setCpuShareBps(RecordDevelopHybridCpuLanePolicy.DEFAULT_CPU_SHARE_BPS)
    }

    @Test
    fun developConcurrencyGuardsHeapAndFlags() {
        val frame = 222L * 1024 * 1024
        val roomyHeap = 1024L * 1024 * 1024 // 1 GiB: 1.5 frames (333 MiB) fits under 72%.
        val tightHeap = 400L * 1024 * 1024  // 400 MiB: 333 MiB > 72% (288 MiB) -> refuse.

        // Flag OFF -> single lane regardless of heap.
        assertEquals(1, RecordDevelopHybridCpuLanePolicy.developConcurrency(true, frame, roomyHeap, flagEnabled = false, forceEnabled = false))
        // No GPU -> single lane.
        assertEquals(1, RecordDevelopHybridCpuLanePolicy.developConcurrency(false, frame, roomyHeap, flagEnabled = true, forceEnabled = false))
        // Default cpuShareBps=0 means strict GPU-only colour develop: single lane.
        assertEquals(1, RecordDevelopHybridCpuLanePolicy.developConcurrency(true, frame, roomyHeap, flagEnabled = true, forceEnabled = false))
        RecordDevelopHybridCpuLanePolicy.setCpuShareBps(1_000)
        // Flag ON + nonzero CPU share + roomy heap -> two lanes.
        assertEquals(2, RecordDevelopHybridCpuLanePolicy.developConcurrency(true, frame, roomyHeap, flagEnabled = true, forceEnabled = false))
        // Flag ON + tight heap -> single lane (guard refuses, no OOM).
        assertEquals(1, RecordDevelopHybridCpuLanePolicy.developConcurrency(true, frame, tightHeap, flagEnabled = true, forceEnabled = false))
        // Force ON -> two lanes even on a tight heap (A/B measurement only).
        assertEquals(2, RecordDevelopHybridCpuLanePolicy.developConcurrency(true, frame, tightHeap, flagEnabled = true, forceEnabled = true))
        RecordDevelopHybridCpuLanePolicy.setCpuShareBps(RecordDevelopHybridCpuLanePolicy.DEFAULT_CPU_SHARE_BPS)
    }
}
