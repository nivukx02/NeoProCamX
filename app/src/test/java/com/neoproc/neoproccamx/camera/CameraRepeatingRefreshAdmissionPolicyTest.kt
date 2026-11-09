package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRepeatingRefreshAdmissionPolicyTest {
    @Test
    fun `repeating request is deferred while mode batch mutates the target graph`() {
        assertTrue(
            CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
                batchDepth = 1,
                batchRefreshHandoffPending = false,
                queuedSessionRefreshDispatches = 0,
                sessionRefreshPending = false
            )
        )
    }

    @Test
    fun `outermost batch handoff closes gap before camera thread claims refresh`() {
        assertTrue(
            CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
                batchDepth = 0,
                batchRefreshHandoffPending = true,
                queuedSessionRefreshDispatches = 0,
                sessionRefreshPending = false
            )
        )
    }

    @Test
    fun `executor and handler queue gap is covered by dispatch ownership`() {
        assertTrue(
            CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
                batchDepth = 0,
                batchRefreshHandoffPending = false,
                queuedSessionRefreshDispatches = 1,
                sessionRefreshPending = false
            )
        )
    }

    @Test
    fun `active session replacement remains authoritative`() {
        assertTrue(
            CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
                batchDepth = 0,
                batchRefreshHandoffPending = false,
                queuedSessionRefreshDispatches = 0,
                sessionRefreshPending = true
            )
        )
    }

    @Test
    fun `stable configured session admits repeating request`() {
        assertFalse(
            CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
                batchDepth = 0,
                batchRefreshHandoffPending = false,
                queuedSessionRefreshDispatches = 0,
                sessionRefreshPending = false
            )
        )
    }

    @Test
    fun `batch replay occurs only when no session refresh will publish latest request`() {
        assertTrue(
            CameraRepeatingRefreshAdmissionPolicy.shouldReplayAfterBatch(
                repeatingRefreshDeferred = true,
                sessionRefreshQueued = false
            )
        )
        assertFalse(
            CameraRepeatingRefreshAdmissionPolicy.shouldReplayAfterBatch(
                repeatingRefreshDeferred = true,
                sessionRefreshQueued = true
            )
        )
        assertFalse(
            CameraRepeatingRefreshAdmissionPolicy.shouldReplayAfterBatch(
                repeatingRefreshDeferred = false,
                sessionRefreshQueued = false
            )
        )
    }
}
