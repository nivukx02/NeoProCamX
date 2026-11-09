package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s1-f05 — record-path dropped-frame accounting. Locks the elite acceptance criteria:
 * 0 unintended drops in steady state, and any drop during a reconfigure window is bounded
 * and reported (never silent). Pure-JVM; no Android framework.
 */
class CameraRecordFrameDeliveryLedgerTest {

    @Test
    fun `steady state with only completed frames is clean and drop free`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        repeat(300) { ledger.onFrameCompleted(recordingActive = true) }

        val snapshot = ledger.snapshot()
        assertEquals(300L, snapshot.completedFrames)
        assertEquals(0L, snapshot.steadyStateUnintendedDrops)
        assertTrue(snapshot.steadyStateClean)
        assertTrue(snapshot.reconfigureDropsWithinBound())
    }

    @Test
    fun `non flushed failure outside a reconfigure is an unintended steady state drop`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        ledger.onFrameCompleted(recordingActive = true)
        ledger.onCaptureFailed(recordingActive = true, flushed = false)

        val snapshot = ledger.snapshot()
        assertEquals(1L, snapshot.steadyStateErrorDrops)
        assertEquals(1L, snapshot.steadyStateUnintendedDrops)
        assertFalse(snapshot.steadyStateClean)
    }

    @Test
    fun `flushed failure is always a bounded transition drop never a steady state drop`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        // No reconfigure window open, but a FLUSHED failure is an intended transition consequence.
        ledger.onCaptureFailed(recordingActive = true, flushed = true)

        val snapshot = ledger.snapshot()
        assertEquals(0L, snapshot.steadyStateUnintendedDrops)
        assertEquals(1L, snapshot.reconfigureBoundedDrops)
        assertTrue(snapshot.steadyStateClean)
    }

    @Test
    fun `drops during an open reconfigure window are bounded and reported not steady state`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        ledger.markReconfigureStart()
        ledger.onCaptureFailed(recordingActive = true, flushed = false)
        ledger.onBufferLost(recordingActive = true)
        ledger.markReconfigureSettled()

        val snapshot = ledger.snapshot()
        assertEquals(0L, snapshot.steadyStateUnintendedDrops)
        assertEquals(2L, snapshot.reconfigureBoundedDrops)
        assertEquals(1L, snapshot.reconfigureWindowsOpened)
        assertTrue(snapshot.steadyStateClean)
        assertFalse(snapshot.currentlyReconfiguring)
    }

    @Test
    fun `window markers only count edges and a settled window re-attributes later drops`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        ledger.markReconfigureStart()
        ledger.markReconfigureStart() // idempotent — still one window
        ledger.onCaptureFailed(recordingActive = true, flushed = false)
        ledger.markReconfigureSettled()
        // After settle, a non-flushed failure is once again a steady-state drop.
        ledger.onCaptureFailed(recordingActive = true, flushed = false)

        val snapshot = ledger.snapshot()
        assertEquals(1L, snapshot.reconfigureWindowsOpened)
        assertEquals(1L, snapshot.reconfigureWindowDrops)
        assertEquals(1L, snapshot.steadyStateErrorDrops)
        assertFalse(snapshot.steadyStateClean)
    }

    @Test
    fun `buffer loss in steady state is unintended but bounded during reconfigure`() {
        val steady = CameraRecordFrameDeliveryLedger()
        steady.onBufferLost(recordingActive = true)
        assertFalse(steady.snapshot().steadyStateClean)
        assertEquals(1L, steady.snapshot().steadyStateBufferLosses)

        val transition = CameraRecordFrameDeliveryLedger()
        transition.markReconfigureStart()
        transition.onBufferLost(recordingActive = true)
        assertTrue(transition.snapshot().steadyStateClean)
        assertEquals(1L, transition.snapshot().reconfigureWindowBufferLosses)
    }

    @Test
    fun `frames and drops are ignored when not on the record path`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        ledger.markReconfigureStart()
        ledger.onFrameCompleted(recordingActive = false)
        ledger.onCaptureFailed(recordingActive = false, flushed = false)
        ledger.onBufferLost(recordingActive = false)

        val snapshot = ledger.snapshot()
        assertEquals(0L, snapshot.completedFrames)
        assertEquals(0L, snapshot.steadyStateUnintendedDrops)
        assertEquals(0L, snapshot.reconfigureBoundedDrops)
    }

    @Test
    fun `reconfigure drop bound scales with the number of windows opened`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        // One window, default bound 4 → 4 bounded drops is within bound, 5 is not.
        ledger.markReconfigureStart()
        repeat(4) { ledger.onCaptureFailed(recordingActive = true, flushed = true) }
        assertTrue(ledger.snapshot().reconfigureDropsWithinBound())

        ledger.onCaptureFailed(recordingActive = true, flushed = true)
        assertFalse(ledger.snapshot().reconfigureDropsWithinBound())

        // A second window raises the allowance back over the running total.
        ledger.markReconfigureSettled()
        ledger.markReconfigureStart()
        assertTrue(ledger.snapshot().reconfigureDropsWithinBound())
    }

    @Test
    fun `reset clears every counter and the window state`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        ledger.markReconfigureStart()
        ledger.onFrameCompleted(recordingActive = true)
        ledger.onCaptureFailed(recordingActive = true, flushed = false)
        ledger.reset()

        val snapshot = ledger.snapshot()
        assertEquals(0L, snapshot.completedFrames)
        assertEquals(0L, snapshot.reconfigureWindowsOpened)
        assertEquals(0L, snapshot.steadyStateUnintendedDrops)
        assertEquals(0L, snapshot.reconfigureBoundedDrops)
        assertFalse(snapshot.currentlyReconfiguring)
        assertTrue(snapshot.steadyStateClean)
    }

    @Test
    fun `compact metadata exposes the honest record path metrics`() {
        val ledger = CameraRecordFrameDeliveryLedger()
        ledger.onFrameCompleted(recordingActive = true)
        ledger.onCaptureFailed(recordingActive = true, flushed = false)

        val metadata = ledger.snapshot().toCompactMetadataMap()
        assertEquals("1", metadata["recordFrameDeliveryCompletedFrames"])
        assertEquals("1", metadata["recordFrameDeliverySteadyStateUnintendedDrops"])
        assertEquals("false", metadata["recordFrameDeliverySteadyStateClean"])
        assertTrue(metadata.containsKey("recordFrameDeliveryReconfigureWithinBound"))
    }
}
