package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFpsTrackerTest {

    @Test
    fun recordFrame_reportsFpsAfterOneSecondWindow() {
        val tracker = PreviewFpsTracker()

        assertNull(tracker.recordFrame(1_000_000_000L))
        assertNull(tracker.recordFrame(1_500_000_000L))
        val fps = tracker.recordFrame(2_000_000_000L)

        assertEquals(3f, fps!!, 0.0001f)
    }

    @Test
    fun recordFrame_ignoresDuplicateTimestamps() {
        val tracker = PreviewFpsTracker()

        assertNull(tracker.recordFrame(1_000_000_000L))
        assertNull(tracker.recordFrame(1_000_000_000L))
        val fps = tracker.recordFrame(2_000_000_000L)

        assertEquals(2f, fps!!, 0.0001f)
    }

    @Test
    fun recordFrame_exposesPacingSnapshotAfterWindow() {
        val tracker = PreviewFpsTracker()

        tracker.recordFrame(1_000_000_000L)
        tracker.recordFrame(1_450_000_000L)
        tracker.recordFrame(2_000_000_000L)

        val snapshot = tracker.latestPacingSnapshot()
        assertNotNull(snapshot)
        assertEquals(3f, snapshot!!.fps, 0.0001f)
        assertTrue(snapshot.smoothedFps > 0f)
        assertTrue(snapshot.maxFps >= snapshot.minFps)
        assertTrue(snapshot.jitterMs >= 0f)
    }

    @Test
    fun recordFrame_resetsWindowWhenTimestampGoesBackward() {
        val tracker = PreviewFpsTracker()

        assertNull(tracker.recordFrame(2_000_000_000L))
        assertNull(tracker.recordFrame(1_900_000_000L))
        assertNull(tracker.recordFrame(2_100_000_000L))
    }

    @Test
    fun reset_clearsAccumulatedState() {
        val tracker = PreviewFpsTracker()
        tracker.recordFrame(1_000_000_000L)
        tracker.reset()

        assertNull(tracker.recordFrame(2_000_000_000L))
        assertNull(tracker.latestPacingSnapshot())
    }
}
