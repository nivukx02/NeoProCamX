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

        assertEquals(2f, fps!!, 0.0001f)
    }

    @Test
    fun recordFrame_ignoresDuplicateTimestamps() {
        val tracker = PreviewFpsTracker()

        assertNull(tracker.recordFrame(1_000_000_000L))
        assertNull(tracker.recordFrame(1_000_000_000L))
        val fps = tracker.recordFrame(2_000_000_000L)

        assertEquals(1f, fps!!, 0.0001f)
    }

    @Test
    fun recordFrame_exposesPacingSnapshotAfterWindow() {
        val tracker = PreviewFpsTracker()

        tracker.recordFrame(1_000_000_000L)
        tracker.recordFrame(1_450_000_000L)
        tracker.recordFrame(2_000_000_000L)

        val snapshot = tracker.latestPacingSnapshot()
        assertNotNull(snapshot)
        assertEquals(2f, snapshot!!.fps, 0.0001f)
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

    @Test
    fun recordFrame_reportsExactTwentyFiveFpsFromPresentationIntervals() {
        val tracker = PreviewFpsTracker()
        val startNs = 5_000_000_000L

        assertNull(tracker.recordFrame(startNs))
        var reportedFps: Float? = null
        for (interval in 1..25) {
            reportedFps = tracker.recordFrame(startNs + interval * 40_000_000L) ?: reportedFps
        }

        assertEquals(25f, reportedFps!!, 0.0001f)
        assertEquals(25f, tracker.latestPacingSnapshot()!!.fps, 0.0001f)
        assertEquals(0f, tracker.latestPacingSnapshot()!!.jitterMs, 0.0001f)
    }

    @Test
    fun stableEvidence_requiresFullWindowAndReportsExactTwentyFiveFps() {
        val tracker = PreviewFpsTracker()
        val startNs = 10_000_000_000L

        tracker.recordFrame(startNs)
        // Twelve 25-FPS intervals are intentionally excluded as generation warm-up. A complete
        // 3.2-second evidence window begins on interval 12 and finishes on interval 92.
        for (interval in 1 until 92) {
            tracker.recordFrame(startNs + interval * 40_000_000L)
        }
        assertNull(tracker.latestStableMeasuredFps())

        tracker.recordFrame(startNs + 92L * 40_000_000L)
        assertEquals(25f, tracker.latestStableMeasuredFps()!!, 0.0001f)
    }

    @Test
    fun stableEvidence_excludesPostGenerationPresentationBurst() {
        val tracker = PreviewFpsTracker()
        val startNs = 20_000_000_000L

        tracker.recordFrame(startNs)
        // A transient six-frame burst is plausible while a replacement Surface becomes current.
        for (interval in 1..6) {
            tracker.recordFrame(startNs + interval * 5_000_000L)
        }
        for (interval in 1..92) {
            tracker.recordFrame(startNs + 480_000_000L + interval * 40_000_000L)
        }

        assertEquals(25f, tracker.latestStableMeasuredFps()!!, 0.0001f)
    }
}
