package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLifecycleIsolationTest {
    @Test
    fun invalidRecordingTransitionIsRejected() {
        val lifecycle = FrameLifecycleTracker()
        lifecycle.mark(FrameLifecycleState.CAPTURED)
        lifecycle.mark(FrameLifecycleState.BUFFERED)

        val invalid = lifecycle.mark(FrameLifecycleState.RECORDED)

        assertFalse(invalid.accepted)
        assertTrue(invalid.reason.contains("encoding"))
        assertEquals(1L, lifecycle.snapshot().invalidTransitionCount)
    }

    @Test
    fun previewFailureDoesNotInvalidateRecordingBranch() {
        val lifecycle = FrameLifecycleTracker()
        lifecycle.mark(FrameLifecycleState.CAPTURED)
        lifecycle.mark(FrameLifecycleState.BUFFERED)
        lifecycle.transitionDomain(FrameDomain.PREVIEW, FrameDomainOutcome.QUEUED, 1L)
        lifecycle.transitionDomain(FrameDomain.PREVIEW, FrameDomainOutcome.RUNNING, 2L)
        lifecycle.transitionDomain(FrameDomain.PREVIEW, FrameDomainOutcome.FAILED, 3L, "gpu-lost")
        lifecycle.transitionDomain(FrameDomain.RECORDING, FrameDomainOutcome.QUEUED, 1L)
        lifecycle.transitionDomain(FrameDomain.RECORDING, FrameDomainOutcome.RUNNING, 2L)
        lifecycle.mark(FrameLifecycleState.ENCODING)
        lifecycle.transitionDomain(FrameDomain.RECORDING, FrameDomainOutcome.COMPLETED, 4L)
        lifecycle.mark(FrameLifecycleState.RECORDED)

        val snapshot = lifecycle.snapshot()
        assertEquals(FrameDomainOutcome.FAILED, snapshot.domainStatus.getValue(FrameDomain.PREVIEW).outcome)
        assertEquals(FrameDomainOutcome.COMPLETED, snapshot.domainStatus.getValue(FrameDomain.RECORDING).outcome)
        assertTrue(snapshot.has(FrameLifecycleState.RECORDED))
    }

    @Test
    fun rawOrStillStorageDoesNotRequireVideoEncoding() {
        val lifecycle = FrameLifecycleTracker()
        lifecycle.mark(FrameLifecycleState.CAPTURED)
        lifecycle.mark(FrameLifecycleState.BUFFERED)

        val pending = lifecycle.mark(FrameLifecycleState.STORAGE_PENDING)
        val stored = lifecycle.mark(FrameLifecycleState.STORED)

        assertTrue(pending.accepted)
        assertTrue(stored.accepted)
        assertFalse(lifecycle.snapshot().has(FrameLifecycleState.ENCODING))
        assertEquals(0L, lifecycle.snapshot().invalidTransitionCount)
    }
}
