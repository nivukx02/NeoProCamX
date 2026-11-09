package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RecordingStartupHandoffTest {

    @Test
    fun failedStartCompletionAlwaysReleasesStartupOwnership() {
        val handoff = RecordingStartupHandoff()

        assertTrue(handoff.tryBeginStart())
        val completion = handoff.completeStart(backendActive = false) {
            throw AssertionError("A failed start must not reserve a backend stop")
        }

        assertFalse(completion.stopWasRequested)
        assertFalse(completion.backendStopReserved)
        assertFalse(handoff.isStarting)
        assertFalse(handoff.isCancellationRequested)
        assertTrue(handoff.tryBeginStart())
    }

    @Test
    fun stopBeforeCancellationRegistrationIsDeliveredWhenActionAttaches() {
        val handoff = RecordingStartupHandoff()
        val cancellations = AtomicInteger(0)

        assertTrue(handoff.tryBeginStart())
        val stop = handoff.requestStopDuringStart()
        assertTrue(stop.handledDuringStartup)
        assertTrue(stop.newlyRequested)

        handoff.registerCancellationAction { cancellations.incrementAndGet() }

        assertEquals(1, cancellations.get())
        assertTrue(handoff.isCancellationRequested)
    }

    @Test
    fun duplicateStartupStopsCancelOnceAndReserveOneBackendStop() {
        val handoff = RecordingStartupHandoff()
        val cancellations = AtomicInteger(0)
        val stopOwnerReservations = AtomicInteger(0)
        val backendStopClaims = AtomicInteger(0)

        assertTrue(handoff.tryBeginStart())
        handoff.registerCancellationAction { cancellations.incrementAndGet() }

        val first = handoff.requestStopDuringStart { stopOwnerReservations.incrementAndGet() }
        val duplicate = handoff.requestStopDuringStart { stopOwnerReservations.incrementAndGet() }
        val completion = handoff.completeStart(backendActive = true) {
            assertEquals(1, stopOwnerReservations.get())
            backendStopClaims.incrementAndGet()
            true
        }

        assertTrue(first.handledDuringStartup)
        assertTrue(first.newlyRequested)
        assertTrue(duplicate.handledDuringStartup)
        assertFalse(duplicate.newlyRequested)
        assertEquals(1, cancellations.get())
        assertEquals(1, stopOwnerReservations.get())
        assertEquals(1, backendStopClaims.get())
        assertTrue(completion.stopWasRequested)
        assertTrue(completion.backendStopReserved)
        assertFalse(handoff.isStarting)
    }

    @Test
    fun stopWinningBoundaryPublishesOwnerBeforeCompletionCanClaimIt() {
        val handoff = RecordingStartupHandoff()
        val stopOwnerPublished = AtomicBoolean(false)

        assertTrue(handoff.tryBeginStart())
        val stop = handoff.requestStopDuringStart {
            assertTrue(stopOwnerPublished.compareAndSet(false, true))
        }
        val completion = handoff.completeStart(backendActive = true) {
            assertTrue(stopOwnerPublished.get())
            true
        }

        assertTrue(stop.handledDuringStartup)
        assertTrue(stop.newlyRequested)
        assertTrue(completion.stopWasRequested)
        assertTrue(completion.backendStopReserved)
    }

    @Test
    fun completionWinningBoundaryCannotPublishOrStealLaterNormalStopOwner() {
        val handoff = RecordingStartupHandoff()
        val startupStopReservations = AtomicInteger(0)

        assertTrue(handoff.tryBeginStart())
        val completion = handoff.completeStart(backendActive = true) { true }
        val lateStop = handoff.requestStopDuringStart {
            startupStopReservations.incrementAndGet()
        }

        assertFalse(completion.stopWasRequested)
        assertFalse(completion.backendStopReserved)
        assertFalse(lateStop.handledDuringStartup)
        assertFalse(lateStop.newlyRequested)
        assertEquals(0, startupStopReservations.get())
    }

    @Test
    fun stopAtCompletionBoundaryAlwaysReachesCancellationOrNormalStopPath() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(1_000) {
                val handoff = RecordingStartupHandoff()
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val stopHandledDuringStartup = AtomicBoolean(false)
                val backendStopReserved = AtomicBoolean(false)
                val stopOwnerPublished = AtomicBoolean(false)
                val coordinator = RecordingRestartAfterStopCoordinator()
                val startupLease =
                    AtomicReference<RecordingRestartAfterStopCoordinator.StopLease?>(null)
                val completionLease =
                    AtomicReference<RecordingRestartAfterStopCoordinator.StopLease?>(null)

                assertTrue(handoff.tryBeginStart())
                handoff.registerCancellationAction { }

                val stopFuture = executor.submit {
                    ready.countDown()
                    go.await()
                    stopHandledDuringStartup.set(
                        handoff.requestStopDuringStart {
                            assertTrue(stopOwnerPublished.compareAndSet(false, true))
                            val lease = requireNotNull(coordinator.tryBeginStop())
                            assertTrue(startupLease.compareAndSet(null, lease))
                        }.handledDuringStartup
                    )
                }
                val completionFuture = executor.submit {
                    ready.countDown()
                    go.await()
                    backendStopReserved.set(
                        handoff.completeStart(backendActive = true) {
                            assertTrue(stopOwnerPublished.get())
                            completionLease.set(requireNotNull(startupLease.get()))
                            true
                        }.backendStopReserved
                    )
                }

                assertTrue(ready.await(2, TimeUnit.SECONDS))
                go.countDown()
                stopFuture.get(2, TimeUnit.SECONDS)
                completionFuture.get(2, TimeUnit.SECONDS)

                // If stop sees STARTING, completion must reserve the backend stop. If completion wins,
                // stop sees IDLE and the caller proceeds through the normal active-backend stop path.
                assertTrue(
                    !stopHandledDuringStartup.get() || backendStopReserved.get()
                )
                assertEquals(stopHandledDuringStartup.get(), stopOwnerPublished.get())
                if (stopHandledDuringStartup.get()) {
                    val published = requireNotNull(startupLease.get())
                    assertSame(published, completionLease.get())
                    assertTrue(startupLease.compareAndSet(published, null))
                    assertSame(
                        RecordingRestartAfterStopCoordinator.StopReleaseDecision.Released,
                        coordinator.releaseStop(published)
                    )
                } else {
                    // completeStart won. No startup lease was published, so the caller may reserve a
                    // normal stop generation afterwards and the completed startup cannot steal it.
                    assertNull(startupLease.get())
                    assertNull(completionLease.get())
                    val normalStopLease = requireNotNull(coordinator.tryBeginStop())
                    assertSame(normalStopLease, coordinator.activeStopLeaseForOwner())
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun restartBeforeCancelledStartupCompletesIsRetainedAndDuplicatesCoalesce() {
        val handoff = RecordingStartupHandoff()
        val coordinator = RecordingRestartAfterStopCoordinator()
        val stopLease = AtomicReference<RecordingRestartAfterStopCoordinator.StopLease?>(null)

        assertTrue(handoff.tryBeginStart {
            coordinator.requestStart() is RecordingRestartAfterStopCoordinator.StartDecision.Claimed
        })
        val stop = handoff.requestStopDuringStart {
            stopLease.set(requireNotNull(coordinator.tryBeginStop()))
        }
        assertTrue(stop.handledDuringStartup)
        assertTrue(stop.newlyRequested)

        fun requestRestart(): Pair<Boolean, RecordingRestartAfterStopCoordinator.StartDecision?> {
            var decision: RecordingRestartAfterStopCoordinator.StartDecision? = null
            val claimed = handoff.tryBeginStart(
                retainRestartDuringPendingStop = { decision = coordinator.requestStart() }
            ) {
                throw AssertionError("Pending-stop restart must not enter ordinary start admission")
            }
            return claimed to decision
        }

        val (firstClaimed, firstDecision) = requestRestart()
        val (duplicateClaimed, duplicateDecision) = requestRestart()
        assertFalse(firstClaimed)
        assertFalse(duplicateClaimed)
        val first = firstDecision as RecordingRestartAfterStopCoordinator.StartDecision.Deferred
        val duplicate = duplicateDecision as RecordingRestartAfterStopCoordinator.StartDecision.Deferred
        assertTrue(first.newlyRetained)
        assertFalse(duplicate.newlyRetained)
        assertEquals(first.stopGeneration, duplicate.stopGeneration)

        val completion = handoff.completeStart(backendActive = false) {
            throw AssertionError("Cancelled startup has no backend stop to reserve")
        }
        assertTrue(completion.stopWasRequested)
        assertFalse(completion.backendStopReserved)
        val release = coordinator.releaseStop(requireNotNull(stopLease.get())) as
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady
        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED,
            coordinator.claimReplay(release.permit)
        )
        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.STALE_PERMIT,
            coordinator.claimReplay(release.permit)
        )
    }
}
