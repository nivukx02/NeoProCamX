package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.recording.RecordingPersistentInputSurfaceSelection.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-lock (characterization) of the persistent-input-surface selection state machine and the
 * record-start accounting helpers (s6-f10). Expected values are derived from the live
 * [RecordingManager] field mutations the mirror reproduces (prepare :849-857, attach :889-908,
 * setAllowed :861-865, release :868-877, releaseInternal :3275-3284), NOT invented.
 */
class RecordingPersistentInputSurfaceSelectionTest {

    private val sel = RecordingPersistentInputSurfaceSelection

    @Test
    fun initialStateIsAllowedNotRejectedNotArmedCodecOwned() {
        assertEquals(
            State(
                allowed = true,
                rejected = false,
                surfaceArmed = false,
                usesPersistent = false,
                encoderSurfaceIsPersistent = false,
            ),
            State(),
        )
    }

    @Test
    fun prepareSuccessArmsSurfaceAndClearsRejected() {
        // A prior failure left rejected = true; a later successful prepare clears it (:851).
        val after = sel.prepareSucceeded(State(rejected = true))
        assertEquals(State(allowed = true, rejected = false, surfaceArmed = true), after)
    }

    @Test
    fun prepareFailureSetsRejectedAndLeavesSurfaceUnarmed() {
        val after = sel.prepareFailed(State())
        assertEquals(State(allowed = true, rejected = true, surfaceArmed = false), after)
    }

    @Test
    fun attachUsesPersistentWhenArmedAndNotRejected() {
        val armed = sel.prepareSucceeded(State())
        assertTrue(sel.wouldUsePersistent(armed))
        val attached = sel.attachSucceeded(armed)
        assertEquals(
            State(allowed = true, surfaceArmed = true, usesPersistent = true, encoderSurfaceIsPersistent = true),
            attached,
        )
    }

    @Test
    fun attachRejectionReleasesSurfaceResetsRejectedAndFallsBackCodecOwned() {
        val armed = sel.prepareSucceeded(State())
        // Codec rejects the persistent surface: :895 sets rejected=true, then :896 release() resets
        // it to false (:877) and nulls the surface. Net state is codec-owned, not "rejected".
        val rejected = sel.attachRejected(armed)
        assertEquals(
            State(allowed = true, rejected = false, surfaceArmed = false, usesPersistent = false),
            rejected,
        )
        assertFalse(sel.wouldUsePersistent(rejected))
        // A subsequent attach can only be codec-owned (surface is gone).
        assertFalse(sel.attachSucceeded(rejected).usesPersistent)
    }

    @Test
    fun setAllowedFalsePreservesOwnershipWitnessButForcesCodecOwnedSelection() {
        val armed = sel.prepareSucceeded(State())
        val disallowed = sel.setAllowed(armed, allowed = false)
        assertEquals(
            State(allowed = false, rejected = false, surfaceArmed = true, usesPersistent = false),
            disallowed,
        )
        assertFalse(sel.wouldUsePersistent(disallowed))
    }

    @Test
    fun releasePersistentSurfaceResetsRejectedAndDetachesWhenAttached() {
        val attached = State(
            allowed = true,
            rejected = true,
            surfaceArmed = true,
            usesPersistent = true,
            encoderSurfaceIsPersistent = true,
        )
        val released = sel.releasePersistentSurface(attached)
        assertEquals(
            State(allowed = true, rejected = false, surfaceArmed = false, usesPersistent = false, encoderSurfaceIsPersistent = false),
            released,
        )
    }

    @Test
    fun sessionResetKeepsPersistentSurfaceAndClearsUsesPersistent() {
        val attached = sel.attachSucceeded(sel.prepareSucceeded(State()))
        val reset = sel.sessionReset(attached)
        // Persistent surface stays armed across recordings; only the per-session usage is cleared.
        assertEquals(
            State(allowed = true, rejected = false, surfaceArmed = true, usesPersistent = false, encoderSurfaceIsPersistent = false),
            reset,
        )
    }

    @Test
    fun reconfigureAfterReleaseNeverAttachesStaleReleasedSurface() {
        val armed = sel.prepareSucceeded(State())
        val released = sel.releasePersistentSurface(armed)
        assertFalse(released.surfaceArmed)
        // Reconfigure: attach must fall back to codec-owned, never re-attach the released surface.
        val reattached = sel.attachSucceeded(released)
        assertFalse(reattached.usesPersistent)
        assertFalse(reattached.encoderSurfaceIsPersistent)
    }

    @Test
    fun headFrameGapIsZeroWhenInputEqualsMuxed() {
        assertEquals(0L, sel.headFrameGap(inputFramesFromT0 = 300L, muxedFramesFromT0 = 300L))
        // Muxed never exceeds input meaningfully; a higher muxed count clamps to 0, not negative.
        assertEquals(0L, sel.headFrameGap(inputFramesFromT0 = 300L, muxedFramesFromT0 = 305L))
    }

    @Test
    fun headFrameGapReportsPositiveDeficitWhenMuxedBelowInput() {
        assertEquals(3L, sel.headFrameGap(inputFramesFromT0 = 300L, muxedFramesFromT0 = 297L))
    }

    @Test
    fun recordStartLatencyWithinBudgetAtOneFrameIntervalBoundaryThirtyFps() {
        assertEquals(33_333L, sel.frameIntervalUs(30))
        assertTrue(sel.recordStartLatencyWithinBudget(latencyUs = 0L, fps = 30))
        assertTrue(sel.recordStartLatencyWithinBudget(latencyUs = 33_333L, fps = 30))
    }

    @Test
    fun recordStartLatencyExceedsBudgetAboveOneFrameInterval() {
        assertFalse(sel.recordStartLatencyWithinBudget(latencyUs = 33_334L, fps = 30))
        assertFalse(sel.recordStartLatencyWithinBudget(latencyUs = 40_000L, fps = 30))
        // Unmeasured / invalid fps never claims within-budget.
        assertFalse(sel.recordStartLatencyWithinBudget(latencyUs = -1L, fps = 30))
        assertFalse(sel.recordStartLatencyWithinBudget(latencyUs = 0L, fps = 0))
    }

    @Test
    fun recordStartLatencyUnmeasuredReturnsNotMeasuredSentinel() {
        assertEquals("NOT_MEASURED", sel.recordStartLatencyEvidence(latencyUs = -1L, fps = 30))
        assertEquals("true", sel.recordStartLatencyEvidence(latencyUs = 33_333L, fps = 30))
        assertEquals("false", sel.recordStartLatencyEvidence(latencyUs = 33_334L, fps = 30))
        assertEquals("NOT_MEASURED", sel.headFrameGapEvidence(headFrameGap = -1))
        assertEquals("0", sel.headFrameGapEvidence(headFrameGap = 0))
        assertEquals("3", sel.headFrameGapEvidence(headFrameGap = 3))
    }
}
