package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrCameraP010PrewarmTrackerTest {

    @Test
    fun `completion requires both successful draws and rotating buffer proof`() {
        val tracker = SdrCameraP010PrewarmTracker(
            targetSuccessfulFrames = 4,
            minimumDistinctBufferIdentities = 3,
            maximumAttempts = 8
        )

        tracker.recordSuccess(11L, 100L, 1_000L)
        tracker.recordSuccess(22L, 200L, 2_000L)
        tracker.recordSuccess(11L, 300L, 3_000L)
        assertFalse(tracker.snapshot().complete)

        val complete = tracker.recordSuccess(33L, 400L, 4_000L)
        assertTrue(complete.complete)
        assertTrue(complete.terminal)
        assertEquals(4L, complete.attempts)
        assertEquals(4L, complete.successes)
        assertEquals(3, complete.distinctBufferIdentities)
        assertEquals(100L, complete.firstSensorTimestampNs)
        assertEquals(400L, complete.lastSensorTimestampNs)
        assertNull(complete.bypassReason)
        assertFalse(tracker.shouldAttempt())
    }

    @Test
    fun `successful draws from one allocation still exhaust bounded attempt budget`() {
        val tracker = SdrCameraP010PrewarmTracker(
            targetSuccessfulFrames = 2,
            minimumDistinctBufferIdentities = 2,
            maximumAttempts = 3
        )

        repeat(3) { index ->
            tracker.recordSuccess(
                bufferIdentity = 91L,
                sensorTimestampNs = (index + 1L) * 100L,
                observedAtElapsedRealtimeNs = (index + 1L) * 1_000L
            )
        }

        val exhausted = tracker.snapshot()
        assertFalse(exhausted.complete)
        assertTrue(exhausted.terminal)
        assertEquals("attempt-budget-exhausted", exhausted.bypassReason)
        assertFalse(tracker.shouldAttempt())
    }

    @Test
    fun `failure budget is terminal and reset starts an independent clip epoch`() {
        val tracker = SdrCameraP010PrewarmTracker(
            targetSuccessfulFrames = 2,
            minimumDistinctBufferIdentities = 2,
            maximumAttempts = 2
        )

        tracker.recordFailure(101L, 1_001L)
        val exhausted = tracker.recordFailure(202L, 2_002L)
        assertEquals(2L, exhausted.failures)
        assertTrue(exhausted.terminal)

        tracker.reset()
        val reset = tracker.snapshot()
        assertEquals(SdrCameraP010PrewarmSnapshot.EMPTY, reset)
        assertTrue(tracker.shouldAttempt())
    }

    @Test
    fun `explicit unsupported reason is retained without fabricating readiness`() {
        val tracker = SdrCameraP010PrewarmTracker()

        val bypassed = tracker.markBypassed("source-format-image-35-ahb-35")
        tracker.markBypassed("replacement")

        assertFalse(bypassed.complete)
        assertTrue(bypassed.terminal)
        assertEquals("source-format-image-35-ahb-35", tracker.snapshot().bypassReason)
        assertFalse(tracker.shouldAttempt())
    }

    @Test
    fun `foreign analysis frames cannot terminate valid p010 prewarm`() {
        val tracker = SdrCameraP010PrewarmTracker(
            targetSuccessfulFrames = 2,
            minimumDistinctBufferIdentities = 2,
            maximumAttempts = 4
        )

        val ignored = tracker.recordIgnoredForeignSource("source-format-image-35-ahb-35")
        assertFalse(ignored.complete)
        assertFalse(ignored.terminal)
        assertEquals(1L, ignored.ignoredForeignSourceFrames)
        assertEquals("source-format-image-35-ahb-35", ignored.lastIgnoredForeignSourceReason)
        assertTrue(tracker.shouldAttempt())

        tracker.recordSuccess(11L, 100L, 1_000L)
        val complete = tracker.recordSuccess(22L, 200L, 2_000L)
        assertTrue(complete.complete)
        assertTrue(complete.terminal)
        assertNull(complete.bypassReason)
        assertEquals(1L, complete.ignoredForeignSourceFrames)
    }
}
