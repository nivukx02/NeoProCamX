package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the reversible contract of the single-worker RAW→LOG CPU+GPU overlap gate: DEFAULT ON (the
 * overlap is a byte-identical, no-downgrade speed win) and flippable OFF at runtime via
 * `debug.raw_develop_overlap` for a device that shows memory pressure during an offline convert.
 */
class RawDevelopPipelineOverlapPolicyTest {

    @After
    fun reset() {
        RawDevelopPipelineOverlapPolicy.set(true)
    }

    @Test
    fun `default is on so the flagship RAW conversion overlaps GPU and CPU`() {
        assertTrue(RawDevelopPipelineOverlapPolicy.enabled)
    }

    @Test
    fun `set toggles the process-global gate both ways`() {
        RawDevelopPipelineOverlapPolicy.set(false)
        assertFalse(RawDevelopPipelineOverlapPolicy.enabled)
        RawDevelopPipelineOverlapPolicy.set(true)
        assertTrue(RawDevelopPipelineOverlapPolicy.enabled)
    }

    @Test
    fun `overlap engages on a roomy heap for a full-sensor frame`() {
        // Full-sensor ~4096x3072: overlap peak ≈ 2 ACEScg planes (~302 MiB) + finish (~125 MiB) ≈ 427 MiB.
        // A 2 GiB heap has ample room (needs peak <= maxHeap/2 = 1 GiB).
        assertTrue(
            RawDevelopPipelineOverlapPolicy.shouldOverlap(4096, 3072, maxHeapBytes = 2L * 1024 * 1024 * 1024)
        )
    }

    @Test
    fun `overlap falls back to sequential on a tight heap`() {
        // The same full-sensor frame must NOT overlap when half the heap can't hold the two-plane peak.
        assertFalse(
            RawDevelopPipelineOverlapPolicy.shouldOverlap(4096, 3072, maxHeapBytes = 384L * 1024 * 1024)
        )
    }

    @Test
    fun `disabled flag forces sequential regardless of heap`() {
        RawDevelopPipelineOverlapPolicy.set(false)
        assertFalse(
            RawDevelopPipelineOverlapPolicy.shouldOverlap(4096, 3072, maxHeapBytes = 8L * 1024 * 1024 * 1024)
        )
    }

    @Test
    fun `degenerate dimensions never overlap`() {
        assertFalse(RawDevelopPipelineOverlapPolicy.shouldOverlap(0, 0, maxHeapBytes = 8L * 1024 * 1024 * 1024))
    }
}
