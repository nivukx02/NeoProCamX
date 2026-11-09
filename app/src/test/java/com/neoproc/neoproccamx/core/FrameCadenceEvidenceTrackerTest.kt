package com.neoproc.neoproccamx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCadenceEvidenceTrackerTest {
    @Test
    fun `steady 25 fps cadence reports no missing frame`() {
        val tracker = FrameCadenceEvidenceTracker().apply { reset(25) }
        repeat(100) { index -> tracker.record(1_000_000_000L + index * 40_000_000L) }

        val snapshot = tracker.snapshot()
        assertEquals(100L, snapshot.observedFrames)
        assertEquals(0L, snapshot.missingCadenceFrames)
        assertEquals(40_000_000L, snapshot.maximumGapNs)
    }

    @Test
    fun `eighty millisecond interval identifies one missing 25 fps frame and its boundary`() {
        val tracker = FrameCadenceEvidenceTracker().apply { reset(25) }
        tracker.record(1_000_000_000L)
        tracker.record(1_040_000_000L)
        tracker.record(1_120_000_000L)

        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.missingCadenceFrames)
        assertEquals(80_000_000L, snapshot.maximumGapNs)
        assertEquals(1_040_000_000L, snapshot.firstGapPreviousTimestampNs)
        assertEquals(1_120_000_000L, snapshot.firstGapTimestampNs)
        assertEquals(1L, snapshot.firstGapMissingFrames)
    }

    @Test
    fun `terminal stop interval can be excluded but an earlier gap remains`() {
        val tracker = FrameCadenceEvidenceTracker().apply { reset(25) }
        listOf(1_000_000_000L, 1_080_000_000L, 1_280_000_000L).forEach(tracker::record)

        assertEquals(4L, tracker.closeTerminalInterval(1_280_000_000L))
        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.missingCadenceFrames)
        assertEquals(4L, snapshot.terminalExcludedMissingFrames)
        assertNull(tracker.closeTerminalInterval(1_280_000_000L))
    }

    @Test
    fun `late callback after terminal boundary is reported without affecting capture`() {
        val tracker = FrameCadenceEvidenceTracker().apply { reset(25) }
        tracker.record(1_000_000_000L)
        assertEquals(0L, tracker.closeTerminalInterval(1_000_000_000L))

        tracker.record(1_040_000_000L)
        assertEquals(1L, tracker.snapshot().timestampsAfterTerminal)
    }
}
