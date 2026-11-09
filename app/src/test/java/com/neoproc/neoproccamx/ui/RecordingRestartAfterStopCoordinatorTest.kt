package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRestartAfterStopCoordinatorTest {
    @Test
    fun `duplicate restart intents coalesce and replay exactly once after release`() {
        val coordinator = RecordingRestartAfterStopCoordinator()
        val stopLease = requireNotNull(coordinator.tryBeginStop())
        var startClaims = 0

        val first = coordinator.requestStart() as
            RecordingRestartAfterStopCoordinator.StartDecision.Deferred
        val duplicate = coordinator.requestStart() as
            RecordingRestartAfterStopCoordinator.StartDecision.Deferred

        assertEquals(stopLease.generation, first.stopGeneration)
        assertTrue(first.newlyRetained)
        assertEquals(stopLease.generation, duplicate.stopGeneration)
        assertFalse(duplicate.newlyRetained)
        assertEquals(0, startClaims)

        var externalOwnerReleased = false
        val release = coordinator.releaseStop(stopLease) {
            externalOwnerReleased = true
        } as RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady
        assertTrue(externalOwnerReleased)
        assertEquals(0, startClaims)

        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED,
            coordinator.claimReplay(release.permit)
        )
        assertTrue(externalOwnerReleased)
        startClaims += 1
        assertEquals(1, startClaims)
        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.STALE_PERMIT,
            coordinator.claimReplay(release.permit)
        )
        assertEquals(1, startClaims)
    }

    @Test
    fun `teardown cancels retained and already issued replay without claiming start`() {
        val retainedCoordinator = RecordingRestartAfterStopCoordinator()
        val retainedLease = requireNotNull(retainedCoordinator.tryBeginStop())
        retainedCoordinator.requestStart()

        assertTrue(retainedCoordinator.cancelForTeardown())
        assertSame(
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.Released,
            retainedCoordinator.releaseStop(retainedLease)
        )
        assertNull(retainedCoordinator.tryBeginStop())
        assertSame(
            RecordingRestartAfterStopCoordinator.StartDecision.RejectedTeardown,
            retainedCoordinator.requestStart()
        )

        val issuedCoordinator = RecordingRestartAfterStopCoordinator()
        val issuedLease = requireNotNull(issuedCoordinator.tryBeginStop())
        issuedCoordinator.requestStart()
        val permit = (issuedCoordinator.releaseStop(issuedLease) as
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady).permit
        assertTrue(issuedCoordinator.cancelForTeardown())
        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CANCELLED_BY_TEARDOWN,
            issuedCoordinator.claimReplay(permit)
        )
    }

    @Test
    fun `stale stop generation cannot release or steal current generation`() {
        val coordinator = RecordingRestartAfterStopCoordinator()
        val firstLease = requireNotNull(coordinator.tryBeginStop())
        assertSame(
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.Released,
            coordinator.releaseStop(firstLease)
        )

        val secondLease = requireNotNull(coordinator.tryBeginStop())
        var staleReleaseSideEffect = false
        assertSame(
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.StaleLease,
            coordinator.releaseStop(firstLease) { staleReleaseSideEffect = true }
        )
        assertFalse(staleReleaseSideEffect)
        assertSame(secondLease, coordinator.activeStopLeaseForOwner())

        val deferred = coordinator.requestStart() as
            RecordingRestartAfterStopCoordinator.StartDecision.Deferred
        assertEquals(secondLease.generation, deferred.stopGeneration)
        val release = coordinator.releaseStop(secondLease) as
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady
        assertEquals(secondLease.generation, release.permit.stoppedGeneration)
    }

    @Test
    fun `outstanding replay excludes a new stop until exact permit is consumed`() {
        val coordinator = RecordingRestartAfterStopCoordinator()
        val lease = requireNotNull(coordinator.tryBeginStop())
        coordinator.requestStart()
        val permit = (coordinator.releaseStop(lease) as
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady).permit

        assertNull(coordinator.tryBeginStop())
        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED,
            coordinator.claimReplay(permit)
        )
        assertTrue(coordinator.tryBeginStop() != null)
    }
}
