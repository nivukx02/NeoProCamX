package com.neoproc.neoproccamx.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCaptureTimeoutGateTest {
    @Test
    fun `replacement lease prevents prior timeout from executing`() {
        val gate = StillCaptureTimeoutGate()
        val stale = gate.replace()
        val current = gate.replace()
        var staleRan = false
        var currentRan = false

        assertFalse(gate.runIfCurrent(stale) { staleRan = true })
        assertTrue(gate.runIfCurrent(current) { currentRan = true })
        assertFalse(staleRan)
        assertTrue(currentRan)
    }

    @Test
    fun `timeout action and lease replacement are serialized`() {
        val gate = StillCaptureTimeoutGate()
        val lease = gate.replace()
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)

        val actionThread = thread(start = true) {
            gate.runIfCurrent(lease) {
                actionEntered.countDown()
                assertTrue(releaseAction.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(actionEntered.await(2, TimeUnit.SECONDS))
        val replacementThread = thread(start = true) {
            replacementStarted.countDown()
            gate.replace()
            replacementFinished.countDown()
        }

        assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
        assertFalse(replacementFinished.await(50L, TimeUnit.MILLISECONDS))
        releaseAction.countDown()
        actionThread.join(2_000L)
        replacementThread.join(2_000L)
        assertTrue(replacementFinished.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `invalidation prevents timeout execution during teardown`() {
        val gate = StillCaptureTimeoutGate()
        val lease = gate.replace()
        gate.invalidate()

        assertFalse(gate.runIfCurrent(lease) { error("stale timeout executed") })
    }
}
