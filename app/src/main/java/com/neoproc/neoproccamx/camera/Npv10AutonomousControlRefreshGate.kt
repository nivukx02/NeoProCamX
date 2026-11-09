package com.neoproc.neoproccamx.camera

/**
 * Separates camera-engine feedback from user/external control mutations during the short NPV10
 * producer-startup transaction.
 *
 * Automatic AE/AWB/AF/detail engines continuously refine mutable controller state. Replacing the
 * Camera2 repeating request while the NPV10 producer lease is proving cadence is unsafe, but treating
 * those expected refinements as an external mutation makes recording startup fail nondeterministically.
 * This gate coalesces every automatic refinement into a retained-lease replay before writer
 * attachment. The strict producer lease remains the sole authority for user/external submissions,
 * so this class cannot weaken its fail-closed behavior.
 *
 * All transitions are synchronized. A request racing retirement is therefore either included in the
 * single replay returned by [retire] or observes no active window and executes normally; it cannot be
 * lost between those outcomes.
 */
internal class Npv10AutonomousControlRefreshGate {
    internal data class ReplayClaim(
        val claimed: Boolean,
        val coalescedRefreshCount: Long
    )

    internal data class RetireResult(
        val retired: Boolean,
        val replayLatestControls: Boolean,
        val coalescedRefreshCount: Long
    )

    private data class Window(
        val lease: Npv10ProducerStartupLeaseGate.Lease,
        var coalescedRefreshCount: Long = 0L
    )

    private var active: Window? = null

    @Synchronized
    fun begin(lease: Npv10ProducerStartupLeaseGate.Lease) {
        check(active == null) { "An NPV10 autonomous-control startup window is already active" }
        active = Window(lease)
    }

    /**
     * Returns true only when the caller's latest state must be retained without submitting Camera2.
     */
    @Synchronized
    fun deferIfActive(sessionGeneration: Long): Boolean {
        val current = active ?: return false
        if (current.lease.sessionGeneration != sessionGeneration || current.lease.revoked.get()) {
            return false
        }
        if (current.coalescedRefreshCount < Long.MAX_VALUE) {
            current.coalescedRefreshCount += 1L
        }
        return true
    }

    /**
     * Claims the latest coalesced engine state without retiring the startup window.
     *
     * CameraController invokes this while holding the same submission lock used by [deferIfActive].
     * An update that arrives after the claim therefore remains counted for another stabilization
     * pass instead of being lost or replayed after the durable timeline has started.
     */
    @Synchronized
    fun claimReplayWhileRetained(
        lease: Npv10ProducerStartupLeaseGate.Lease
    ): ReplayClaim {
        val current = active
        if (current == null ||
            current.lease !== lease ||
            lease.releaseRequested.get() ||
            lease.revoked.get()
        ) {
            return ReplayClaim(claimed = false, coalescedRefreshCount = 0L)
        }
        val claimedCount = current.coalescedRefreshCount
        if (claimedCount <= 0L) {
            return ReplayClaim(claimed = false, coalescedRefreshCount = 0L)
        }
        current.coalescedRefreshCount = 0L
        return ReplayClaim(claimed = true, coalescedRefreshCount = claimedCount)
    }

    /**
     * Retires only the exact owning lease and returns one replay decision for all accumulated updates.
     */
    @Synchronized
    fun retire(lease: Npv10ProducerStartupLeaseGate.Lease): RetireResult {
        val current = active
        if (current == null || current.lease !== lease) {
            return RetireResult(
                retired = false,
                replayLatestControls = false,
                coalescedRefreshCount = 0L
            )
        }
        active = null
        return RetireResult(
            retired = true,
            replayLatestControls = current.coalescedRefreshCount > 0L,
            coalescedRefreshCount = current.coalescedRefreshCount
        )
    }

    /** A session-generation change makes every queued control snapshot stale; never replay it. */
    @Synchronized
    fun invalidate(): Boolean {
        val hadActiveWindow = active != null
        active = null
        return hadActiveWindow
    }
}
