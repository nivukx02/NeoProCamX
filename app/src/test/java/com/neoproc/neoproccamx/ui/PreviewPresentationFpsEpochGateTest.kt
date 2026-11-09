package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class PreviewPresentationFpsEpochGateTest {
    private val gate = PreviewPresentationFpsEpochGate()

    @Test
    fun `first accepted identity resets before anchoring its first frame`() {
        val decision = gate.evaluate(event(generation = 4L, epoch = 2L, sequence = 1L), snapshot(4L, 2L, 1L))

        assertTrue(decision.accepted)
        assertTrue(decision.resetBeforePresentation)
    }

    @Test
    fun `steady frames in one identity preserve the measurement window`() {
        gate.evaluate(event(4L, 2L, 1L), snapshot(4L, 2L, 1L))
        val decision = gate.evaluate(event(4L, 2L, 2L), snapshot(4L, 2L, 2L))

        assertTrue(decision.accepted)
        assertFalse(decision.resetBeforePresentation)
    }

    @Test
    fun `new session or surface resets immediately before its first accepted frame`() {
        gate.evaluate(event(4L, 2L, 1L), snapshot(4L, 2L, 1L))

        assertTrue(gate.evaluate(event(5L, 2L, 2L), snapshot(5L, 2L, 2L)).resetBeforePresentation)
        assertTrue(gate.evaluate(event(5L, 3L, 3L), snapshot(5L, 3L, 3L)).resetBeforePresentation)
    }

    @Test
    fun `stale presentation neither enters FPS tracking nor changes accepted identity`() {
        gate.evaluate(event(4L, 2L, 1L), snapshot(4L, 2L, 1L))
        val stale = gate.evaluate(event(4L, 2L, 2L), snapshot(5L, 2L, 2L))
        val current = gate.evaluate(event(4L, 2L, 3L), snapshot(4L, 2L, 3L))

        assertFalse(stale.accepted)
        assertFalse(stale.resetBeforePresentation)
        assertTrue(current.accepted)
        assertFalse(current.resetBeforePresentation)
    }

    @Test
    fun `duplicate and descending sequences are rejected while a skipped newer sequence is accepted`() {
        gate.evaluate(event(4L, 2L, 4L), snapshot(4L, 2L, 4L))

        assertFalse(gate.evaluate(event(4L, 2L, 4L), snapshot(4L, 2L, 6L)).accepted)
        assertFalse(gate.evaluate(event(4L, 2L, 3L), snapshot(4L, 2L, 6L)).accepted)
        val skippedNewer = gate.evaluate(event(4L, 2L, 6L), snapshot(4L, 2L, 6L))
        assertTrue(skippedNewer.accepted)
        assertFalse(skippedNewer.resetBeforePresentation)
    }

    @Test
    fun `newer sequence with regressed presentation time is rejected`() {
        gate.evaluate(event(4L, 2L, 4L), snapshot(4L, 2L, 5L))
        val regressed = gate.evaluate(
            event(4L, 2L, 5L).copy(presentationTimestampNs = 120_000_000L),
            snapshot(4L, 2L, 5L)
        )

        assertFalse(regressed.accepted)
        assertFalse(regressed.resetBeforePresentation)
    }

    @Test
    fun `stale future identity cannot consume the next valid identity reset`() {
        gate.evaluate(event(4L, 2L, 1L), snapshot(4L, 2L, 1L))

        assertFalse(gate.evaluate(event(5L, 3L, 2L), snapshot(4L, 2L, 2L)).accepted)
        val validNewIdentity = gate.evaluate(event(5L, 3L, 3L), snapshot(5L, 3L, 3L))
        assertTrue(validNewIdentity.accepted)
        assertTrue(validNewIdentity.resetBeforePresentation)
    }

    @Test
    fun `concurrent publication leaves the highest sequence sealed`() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        try {
            (1L..100L).forEach { sequence ->
                executor.execute {
                    start.await()
                    gate.evaluate(
                        event(4L, 2L, sequence),
                        snapshot(4L, 2L, 100L)
                    )
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(5L, TimeUnit.SECONDS))

            assertFalse(gate.evaluate(event(4L, 2L, 100L), snapshot(4L, 2L, 101L)).accepted)
            assertTrue(gate.evaluate(event(4L, 2L, 101L), snapshot(4L, 2L, 101L)).accepted)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `generation reset waits until accepted publication side effects finish`() {
        val executor = Executors.newFixedThreadPool(2)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val resetEntered = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        try {
            executor.execute {
                gate.publishIfAccepted(
                    event(4L, 2L, 1L),
                    { snapshot(4L, 2L, 1L) }
                ) {
                    publicationEntered.countDown()
                    releasePublication.await()
                    order += "publish"
                }
            }
            assertTrue(publicationEntered.await(2L, TimeUnit.SECONDS))
            executor.execute {
                gate.runExclusive {
                    order += "reset"
                    resetEntered.countDown()
                }
            }
            assertFalse(resetEntered.await(100L, TimeUnit.MILLISECONDS))
            releasePublication.countDown()
            assertTrue(resetEntered.await(2L, TimeUnit.SECONDS))
            assertEquals(listOf("publish", "reset"), order)
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
        }
    }

    private fun event(generation: Long, epoch: Long, sequence: Long) = PreviewDisplayPresentEvent(
        sequence = sequence,
        sessionGeneration = generation,
        surfaceEpoch = epoch,
        sensorTimestampNs = sequence * 40_000_000L,
        presentationTimestampNs = sequence * 40_000_000L
    )

    private fun snapshot(generation: Long, epoch: Long, sequence: Long) =
        PreviewDisplayPresentSnapshot(
            latestSequence = sequence,
            currentSessionGeneration = generation,
            currentSurfaceEpoch = epoch
        )
}
