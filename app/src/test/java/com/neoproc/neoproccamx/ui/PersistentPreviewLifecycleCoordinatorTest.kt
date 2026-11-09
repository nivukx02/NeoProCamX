package com.neoproc.neoproccamx.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPreviewLifecycleCoordinatorTest {
    @Test
    fun `NDR texture acceptance starts warmup but only distinct successful swaps commit HDR`() {
        val harness = Harness()
        try {
            harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 100L, harness.epoch)
            assertEquals(PersistentPreviewOwner.SDR, harness.coordinator.owner)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.targetOwner)
            assertTrue(harness.commits.isEmpty())
            assertNull(harness.presentationUpdates.last().first)
            assertEquals(PersistentPreviewOwner.HDR, harness.presentationUpdates.last().second)

            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 100L)
            assertEquals(PersistentPreviewOwner.SDR, harness.coordinator.readyOwner(PersistentPreviewOwner.HDR))
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 100L)
            assertTrue(harness.commits.isEmpty())

            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 101L)
            assertEquals(listOf(PersistentPreviewOwner.HDR), harness.commits)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.owner)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.readyOwner(PersistentPreviewOwner.HDR))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `cold sensor linear NDR selects project owned HDR without an SDR proxy`() {
        val harness = Harness()
        try {
            harness.coordinator.reset(releaseOwner = true)

            assertEquals(
                PersistentPreviewOwner.HDR,
                harness.coordinator.readyOwner(PersistentPreviewOwner.HDR)
            )
            assertNull(harness.coordinator.owner)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `surface replacement invalidates an otherwise current NDR swap proof`() {
        val harness = Harness()
        try {
            harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 200L, harness.epoch)
            harness.coordinator.invalidateSurface()
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 201L)
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 202L)

            assertTrue(harness.commits.isEmpty())
            assertEquals(PersistentPreviewOwner.SDR, harness.coordinator.owner)
            assertNull(harness.coordinator.targetOwner)
            assertEquals(1, harness.cancellations)
            assertEquals(PersistentPreviewOwner.SDR, harness.coordinator.readyOwner(PersistentPreviewOwner.HDR))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `new RAW epoch retains HDR while current project owned frames establish readiness`() {
        val harness = Harness()
        try {
            harness.coordinator.activateOwner(PersistentPreviewOwner.HDR)
            harness.epoch += 1L
            harness.coordinator.onPresentationEpochBegan(harness.epoch, "settings-resume")

            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.owner)
            assertNull(harness.coordinator.targetOwner)
            assertEquals(PersistentPreviewOwner.HDR, harness.selections.last())
            assertTrue(harness.commits.isEmpty())

            harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 300L, harness.epoch)
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 300L)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.owner)
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 301L)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.owner)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.readyOwner(PersistentPreviewOwner.HDR))
            assertTrue(harness.commits.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `new epoch cancels stale HDR warmup while keeping SDR selected`() {
        val harness = Harness()
        try {
            harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 400L, harness.epoch)
            harness.epoch += 1L
            harness.coordinator.onPresentationEpochBegan(harness.epoch, "session-generation")

            assertEquals(PersistentPreviewOwner.SDR, harness.coordinator.owner)
            assertNull(harness.coordinator.targetOwner)
            assertEquals(PersistentPreviewOwner.SDR, harness.selections.last())
            assertTrue(harness.commits.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `ready owner rejects stale presentation predicate even when epoch number matches`() {
        val harness = Harness()
        try {
            harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 500L, harness.epoch)
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 500L)
            harness.coordinator.onProjectOwnedFramePresented(harness.epoch, 501L)
            assertEquals(PersistentPreviewOwner.HDR, harness.coordinator.readyOwner(PersistentPreviewOwner.HDR))

            harness.presentationCurrent = false
            assertEquals(PersistentPreviewOwner.SDR, harness.coordinator.readyOwner(PersistentPreviewOwner.HDR))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `reset and close cancel an active camera handoff`() {
        val harness = Harness()
        harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 600L, harness.epoch)
        harness.coordinator.reset(releaseOwner = false)
        assertEquals(1, harness.cancellations)
        assertNull(harness.coordinator.targetOwner)

        harness.coordinator.onInputFrame(PersistentPreviewOwner.HDR, 601L, harness.epoch)
        harness.coordinator.close()
        assertEquals(2, harness.cancellations)
        assertNull(harness.coordinator.targetOwner)
        harness.scope.cancel()
    }

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var epoch = 41L
        var presentationCurrent = true
        var cancellations = 0
        val commits = mutableListOf<PersistentPreviewOwner>()
        val selections = mutableListOf<PersistentPreviewOwner>()
        val presentationUpdates =
            mutableListOf<Pair<PersistentPreviewOwner?, PersistentPreviewOwner?>>()
        val coordinator = PersistentPreviewLifecycleCoordinator<PersistentPreviewOwner>(
            scope = scope,
            runOnMain = { it() },
            pipelineActive = { true },
            sensorLinearNdrMode = { true },
            currentPresentationEpoch = { epoch },
            presentationEpochCurrent = { presentationCurrent && it == epoch },
            inputForOwner = { it },
            beginCameraHandoff = { true },
            cancelCameraHandoff = { cancellations += 1 },
            completeCameraHandoff = { true },
            selectOutput = { selections += it; true },
            updatePresentation = { visible, warming ->
                presentationUpdates += visible to warming
            },
            onHandoffCommitted = { commits += it },
            mainDispatcher = Dispatchers.Unconfined,
            elapsedRealtimeMs = { 0L }
        ).also { it.activateOwner(PersistentPreviewOwner.SDR) }

        fun close() {
            coordinator.close()
            scope.cancel()
        }
    }
}
