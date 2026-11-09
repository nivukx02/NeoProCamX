package com.neoproc.neoproccamx.camera

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Race-free handoff for Camera2 request refreshes deferred behind a RAW resume transaction.
 *
 * A publisher first records pending work, then revalidates the exact owner it observed. If that
 * owner already retired, the publisher drains the work itself; otherwise the owner's terminal path
 * drains it. [drain] uses get-and-set so exactly one side claims each published refresh. A restart
 * that encounters a newer owner simply calls [defer] again with that newer identity.
 */
internal class RawResumeDeferredRefreshGate<T : Any>(
    private val activeOwner: () -> T?,
    private val restart: () -> Unit
) {
    private val pending = AtomicBoolean(false)

    fun defer(observedOwner: T) {
        pending.set(true)
        if (activeOwner() !== observedOwner) drain()
    }

    fun drain() {
        if (pending.getAndSet(false)) restart()
    }

    /** Retires deferred work without replay when the protected target was not safely detached. */
    fun discard() {
        pending.set(false)
    }
}
