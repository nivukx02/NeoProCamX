package com.neoproc.neoproccamx.ui

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2PreviewCaptureContinuityTest {
    @Test
    fun `idle camera captures reconcile actual visible swap identities in FIFO order`() {
        val surface = Any()
        val display = PreviewDisplayPresentationCoordinator(
            capacity = 32,
            currentSessionGeneration = { 7L },
            currentPreviewSurface = { surface },
            isOutputSurfaceValid = { true }
        ).apply { updateSurface(surface) }
        val frames = CountDownLatch(25)
        val sequences = Collections.synchronizedList(mutableListOf<Long>())
        val coordinator = Camera2FrameLineageCoordinator { timestamp, generation ->
            display.recordCapture(timestamp, generation)?.let {
                sequences.add(it.event.sequence)
                frames.countDown()
            }
        }
        try {
            for (frame in 1L..25L) {
                val timestamp = frame * 40_000_000L
                display.recordCompositedPresentation(
                    timestamp, surface, timestamp + 1_000_000L,
                    PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP
                )
                assertTrue(coordinator.enqueueCaptureObservation(timestamp, 7L, frame) {
                    error("Idle preview must not request recording-only metadata")
                })
            }
            assertTrue("Idle visible frames were not reconciled", frames.await(3, TimeUnit.SECONDS))
            assertEquals((1L..25L).toList(), sequences.toList())
            assertEquals(25L, display.snapshot().latestSequence)
            assertEquals("false", coordinator.runtimeMetadata()["camera2FrameLineageCertified"])
        } finally {
            assertTrue(coordinator.drainAndShutdownForTeardown())
        }
    }

    @Test
    fun `closed recording evidence window does not close preview observation`() {
        val observed = CountDownLatch(1)
        val coordinator = Camera2FrameLineageCoordinator { timestamp, generation ->
            assertEquals(40_000_000L, timestamp)
            assertEquals(9L, generation)
            observed.countDown()
        }
        try {
            // Exercise the exact closed-window state reached by certificate freeze. This is a
            // test-side state injection, not a production override or fabricated camera result.
            val field = Camera2FrameLineageCoordinator::class.java
                .getDeclaredField("captureAdmissionClosed").apply { isAccessible = true }
            (field.get(coordinator) as AtomicBoolean).set(true)
            assertTrue(coordinator.enqueueCaptureObservation(40_000_000L, 9L, 1L) {
                error("Frozen certificate must not collect more recording metadata")
            })
            assertTrue(observed.await(3, TimeUnit.SECONDS))
        } finally {
            assertTrue(coordinator.drainAndShutdownForTeardown())
        }
    }

    @Test
    fun `teardown drains accepted preview callbacks and rejects new ingress`() {
        val timestamps = Collections.synchronizedList(mutableListOf<Long>())
        val coordinator = Camera2FrameLineageCoordinator { timestamp, _ -> timestamps.add(timestamp) }
        for (frame in 1L..64L) {
            assertTrue(coordinator.enqueueCaptureObservation(frame * 40_000_000L, 1L, frame))
        }
        assertTrue(coordinator.drainAndShutdownForTeardown())
        assertEquals((1L..64L).map { it * 40_000_000L }, timestamps.toList())
        assertFalse(coordinator.enqueueCaptureObservation(3_000_000_000L, 1L, 65L))
        assertEquals(64, timestamps.size)
    }

    @Test
    fun `preview ingress stays bounded while its consumer is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timestamps = Collections.synchronizedList(mutableListOf<Long>())
        val coordinator = Camera2FrameLineageCoordinator { timestamp, _ ->
            if (timestamp == 40_000_000L) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            timestamps.add(timestamp)
        }
        try {
            assertTrue(coordinator.enqueueCaptureObservation(40_000_000L, 1L, 1L))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            var accepted = 0
            for (frame in 2L..513L) {
                if (coordinator.enqueueCaptureObservation(frame * 40_000_000L, 1L, frame)) accepted++
            }
            assertEquals("The existing 128-item queue ceiling must remain intact", 128, accepted)
            release.countDown()
            assertTrue(coordinator.drainAndShutdownForTeardown())
            assertEquals((1L..129L).map { it * 40_000_000L }, timestamps.toList())
        } finally {
            release.countDown()
            coordinator.drainAndShutdownForTeardown()
        }
    }

    @Test
    fun `invalid timestamps cannot produce preview or recording evidence`() {
        val coordinator = Camera2FrameLineageCoordinator { _, _ -> error("Invalid capture admitted") }
        try {
            assertFalse(coordinator.enqueueCaptureObservation(0L, 1L, 1L))
            assertFalse(coordinator.enqueueCaptureObservation(-1L, 1L, 2L))
        } finally {
            assertTrue(coordinator.drainAndShutdownForTeardown())
        }
    }
}
