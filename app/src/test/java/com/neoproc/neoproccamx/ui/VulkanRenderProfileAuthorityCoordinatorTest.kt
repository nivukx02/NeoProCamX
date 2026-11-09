package com.neoproc.neoproccamx.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRenderProfileAuthorityCoordinatorTest {

    @Test
    fun `transition never publishes new generation with old hdr mode`() {
        val coordinator = VulkanRenderProfileAuthorityCoordinator(1L, "OFF")
        val runtimeGeneration = AtomicLong(1L)
        val stateFlowMode = AtomicReference("OFF")
        val appMode = AtomicReference("VIDEO")
        val nativeRetired = CountDownLatch(1)
        val releaseTransition = CountDownLatch(1)

        val transition = thread(name = "hdr-authority-transition") {
            coordinator.advance(
                hdrMode = "NDR10+",
                retireNativeGeneration = { generation ->
                    runtimeGeneration.set(generation)
                    nativeRetired.countDown()
                    assertTrue(releaseTransition.await(5, TimeUnit.SECONDS))
                },
                commitAuthorityState = { appMode.set("PHOTO") },
                commitHdrMode = stateFlowMode::set
            )
        }

        assertTrue(nativeRetired.await(5, TimeUnit.SECONDS))
        // Native has already rejected generation 1, but the new Java/Kotlin authority is not exposed
        // until the matching mode mutation is complete. A queued old packet therefore fails closed.
        assertEquals(2L, runtimeGeneration.get())
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(1L, "OFF"),
            coordinator.snapshot()
        )
        assertEquals("OFF", stateFlowMode.get())
        assertEquals("VIDEO", appMode.get())
        val retiredCommitAttempted = CountDownLatch(1)
        val retiredCommitFinished = CountDownLatch(1)
        val retiredCommitRan = AtomicBoolean(false)
        val retiredCommit = thread(name = "retired-render-commit") {
            retiredCommitAttempted.countDown()
            val accepted = coordinator.commitIfCurrent(
                generation = 1L,
                hdrMode = "OFF",
                externalAuthorityCurrent = { runtimeGeneration.get() == 1L }
            ) { retiredCommitRan.set(true) }
            assertFalse(accepted)
            retiredCommitFinished.countDown()
        }
        assertTrue(retiredCommitAttempted.await(5, TimeUnit.SECONDS))
        // The commit shares the transition monitor: it cannot enter the check-to-write window while
        // native retirement and the StateFlow/snapshot publication are still in progress.
        assertFalse(retiredCommitFinished.await(50, TimeUnit.MILLISECONDS))

        releaseTransition.countDown()
        transition.join(5_000L)
        retiredCommit.join(5_000L)
        assertFalse(transition.isAlive)
        assertFalse(retiredCommit.isAlive)
        assertFalse(retiredCommitRan.get())
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(2L, "NDR10+"),
            coordinator.snapshot()
        )
        assertEquals("NDR10+", stateFlowMode.get())
        assertEquals("PHOTO", appMode.get())
    }

    @Test
    fun `accepted native commit and authority mutation cannot interleave`() {
        val coordinator = VulkanRenderProfileAuthorityCoordinator(7L, "NDR10+")
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val transitionFinished = AtomicBoolean(false)

        val commit = thread(name = "accepted-render-commit") {
            assertTrue(
                coordinator.commitIfCurrent(
                    generation = 7L,
                    hdrMode = "NDR10+",
                    externalAuthorityCurrent = { true }
                ) {
                    commitEntered.countDown()
                    assertTrue(releaseCommit.await(5, TimeUnit.SECONDS))
                }
            )
        }
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))

        val transition = thread(name = "off-transition") {
            coordinator.advance(
                hdrMode = "OFF",
                retireNativeGeneration = {},
                commitHdrMode = {}
            )
            transitionFinished.set(true)
        }
        Thread.sleep(50L)
        assertFalse("transition must wait for the accepted commit", transitionFinished.get())

        releaseCommit.countDown()
        commit.join(5_000L)
        transition.join(5_000L)
        assertFalse(commit.isAlive)
        assertFalse(transition.isAlive)
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(8L, "OFF"),
            coordinator.snapshot()
        )
    }

    @Test
    fun `request capture excludes authority transition and returns one coherent generation`() {
        val coordinator = VulkanRenderProfileAuthorityCoordinator(11L, "OFF")
        val captureEntered = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val transitionStarted = CountDownLatch(1)
        val transitionFinished = CountDownLatch(1)
        val captured = AtomicReference<VulkanRenderProfileAuthorityCoordinator.Snapshot?>()

        val capture = thread(name = "render-request-capture") {
            captured.set(
                coordinator.captureIfCurrent(
                    generation = 11L,
                    hdrMode = "OFF",
                    externalAuthorityCurrent = { it.generation == 11L }
                ) { authority ->
                    captureEntered.countDown()
                    assertTrue(releaseCapture.await(5, TimeUnit.SECONDS))
                    authority
                }
            )
        }
        assertTrue(captureEntered.await(5, TimeUnit.SECONDS))

        val transition = thread(name = "render-authority-transition") {
            transitionStarted.countDown()
            coordinator.advance(
                hdrMode = "NDR10+",
                retireNativeGeneration = {},
                commitHdrMode = {}
            )
            transitionFinished.countDown()
        }
        assertTrue(transitionStarted.await(5, TimeUnit.SECONDS))
        assertFalse(
            "authority transition must wait until the immutable request capture is complete",
            transitionFinished.await(50, TimeUnit.MILLISECONDS)
        )
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(11L, "OFF"),
            coordinator.snapshot()
        )

        releaseCapture.countDown()
        capture.join(5_000L)
        transition.join(5_000L)
        assertFalse(capture.isAlive)
        assertFalse(transition.isAlive)
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(11L, "OFF"),
            captured.get()
        )
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(12L, "NDR10+"),
            coordinator.snapshot()
        )
    }

    @Test
    fun `stale or externally retired request is rejected before capture block`() {
        val coordinator = VulkanRenderProfileAuthorityCoordinator(5L, "NDR10+")
        val blockRan = AtomicBoolean(false)

        val staleGeneration = coordinator.captureIfCurrent(generation = 4L) {
            blockRan.set(true)
        }
        val retiredNativeAuthority = coordinator.captureIfCurrent(
            generation = 5L,
            hdrMode = "NDR10+",
            externalAuthorityCurrent = { false }
        ) {
            blockRan.set(true)
        }

        assertNull(staleGeneration)
        assertNull(retiredNativeAuthority)
        assertFalse(blockRan.get())
    }

    @Test
    fun `failed native retirement leaves old authority intact`() {
        val coordinator = VulkanRenderProfileAuthorityCoordinator(3L, "OFF")
        val stateMutationCalled = AtomicBoolean(false)

        runCatching {
            coordinator.advance(
                hdrMode = "NIVUKXVISION",
                retireNativeGeneration = { throw IllegalStateException("native retirement failed") },
                commitHdrMode = { stateMutationCalled.set(true) }
            )
        }

        assertFalse(stateMutationCalled.get())
        assertEquals(
            VulkanRenderProfileAuthorityCoordinator.Snapshot(3L, "OFF"),
            coordinator.snapshot()
        )
    }
}
