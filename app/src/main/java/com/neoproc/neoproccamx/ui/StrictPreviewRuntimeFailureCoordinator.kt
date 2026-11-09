package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewCompositor
import com.neoproc.neoproccamx.rendering.PersistentPreviewOutputRouter
import com.neoproc.neoproccamx.rendering.PreviewGlCompositor
import com.neoproc.neoproccamx.rendering.PreviewRuntimeFailure

/** Atomic route token shared by owner and render threads; deliberately independent of Android. */
internal class StrictPreviewRuntimeRouteGate {
    private var active = false
    private var failureClaimed = true
    private var epoch = 0L

    @Synchronized
    fun activate() {
        if (active) return
        epoch += 1L
        active = true
        failureClaimed = false
    }

    @Synchronized
    fun deactivate() {
        if (active) epoch += 1L
        active = false
        failureClaimed = true
    }

    @Synchronized
    fun claimFailure(beforeRouteHealthMutation: () -> Unit = {}): Long? {
        if (!active || failureClaimed) return null
        // Frame ingress must close before this route becomes observably unhealthy. Keeping both
        // operations under the one-shot monitor also prevents duplicate render-stage failures from
        // entering the owner-thread retirement transaction.
        beforeRouteHealthMutation()
        failureClaimed = true
        return epoch
    }

    @Synchronized
    fun isCurrent(claimedEpoch: Long): Boolean = active && epoch == claimedEpoch

    @Synchronized
    fun isHealthy(): Boolean = active && !failureClaimed
}

/**
 * Collapses failures from the three-stage strict preview chain into one owner-thread transaction.
 * Each EGL stage already fails closed before reporting; this second gate prevents a downstream
 * abandoned-queue failure from racing a duplicate Camera2 teardown after the first stage fails.
 */
internal class StrictPreviewRuntimeFailureCoordinator(
    private val router: PersistentPreviewOutputRouter,
    private val sdr: PreviewGlCompositor,
    private val hdr: Hdr10PqPreviewCompositor,
    private val runOnOwnerThread: (() -> Unit) -> Unit,
    private val onFailureDetected: () -> Unit,
    private val onFailure: (PreviewRuntimeFailure) -> Unit
) {
    private val routeGate = StrictPreviewRuntimeRouteGate()

    init {
        router.setOnRuntimeFailureListener(::dispatch)
        sdr.setOnRuntimeFailureListener(::dispatch)
        hdr.setOnRuntimeFailureListener(::dispatch)
    }

    /** Arms only after the persistent strict route has been proved and committed. */
    fun activatePersistentRoute() = routeGate.activate()

    /** Invalidates queued callbacks before teardown, close, or native-route ownership. */
    fun deactivateRoute() = routeGate.deactivate()

    fun isPersistentRouteHealthy(): Boolean = routeGate.isHealthy()

    /** Builds a close-acknowledgement callback whose generation proof runs on the owner thread. */
    fun ownerThreadRetirement(
        isCurrent: () -> Boolean,
        onRelease: () -> Unit
    ): () -> Unit = {
        runOnOwnerThread {
            if (isCurrent()) onRelease()
        }
    }

    fun close() {
        routeGate.deactivate()
        router.setOnRuntimeFailureListener(null)
        sdr.setOnRuntimeFailureListener(null)
        hdr.setOnRuntimeFailureListener(null)
    }

    private fun dispatch(failure: PreviewRuntimeFailure) {
        // Close frame and record-start admission on the reporting render thread before route health
        // flips. All Camera2/resource mutation remains marshalled to the owner thread below.
        val failureEpoch = routeGate.claimFailure(onFailureDetected) ?: return
        runOnOwnerThread {
            if (routeGate.isCurrent(failureEpoch)) onFailure(failure)
        }
    }
}
